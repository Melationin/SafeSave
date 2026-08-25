# 原版区块加载机制分析（26.1）

> 状态：**分析文档**（2026-08-25）
>
> 原版 tick 中区块加载的控制阶段、卸载保存模型、冻结期间行为，及对 SafeSave 的意义。
> 相关文档：[文档索引](README.md) · [同会话卸载-重载缺口](same-session-chunk-reload.md) · [锂/C2ME 兼容性](lithium-c2me-conflict-analysis.md)

## 一、入口：`ServerLevel.tick` 的 "chunkSource" 阶段

`ServerLevel.tick`（`ServerLevel.java:345-458`）——区块加载是**整个 tick 的第 7 步**：

```
1. [runs] world border → weather
2. 睡觉唤醒
3. updateSkyBrightness
4. [runs] tickTime（gameTime+1）
5. [runs] blockTicks.tick / fluidTicks.tick      ← 计划刻（冻结时跳过）
6. [runs] raids
7. chunkSource.tick(haveTime, true)               ← ★ 区块加载（不受 runs 门控！）
8. [runs] runBlockEvents                          ← 方块事件（冻结时跳过）
9. emptyTime 计算
10. [emptyTime<300] 实体 tick + tickBlockEntities
11. entityManager.tick
```

**关键**：第 7 步在 `runs`（`tickRateManager.runsNormally()`）判断**之外**——冻结期间照常运行。这就是"冻结期间区块仍会加载/卸载"的机制来源（safesave 的恢复与快照钩子也因此照常触发）。

## 二、内部：`ServerChunkCache.tick`（`ServerChunkCache.java:320-338`）

```
purge        [runsNormally() || !tickChunks] ticketStorage.purgeStaleTickets
             ← 冻结时（runsNormally=false, tickChunks=true）→ 不 purge
runDistanceManagerUpdates()   ★ 加载需求核心（不受门控）
             ├ distanceManager.runAllUpdates(chunkMap)  ← ticket → 目标级别传播
             ├ chunkMap.promoteChunkMap()
             └ chunkMap.runGenerationTasks()            ← worldgen 任务
chunks       tickChunks()（自然生成/随机刻，内部再按 runs 门控）
             + chunkMap.tick()                          ← 玩家 ticket 更新（updateChunkTracking）
unload       chunkMap.tick(haveTime)                    ← POI + processUnloads（区块卸载）
clearCache
```

## 三、任务实际执行：`MinecraftServer` 的任务循环

`runDistanceManagerUpdates` 只**产生** ChunkHolder 晋级/加载任务（提交到 ChunkMap 主线程队列）；任务本体在 `MinecraftServer` 的 `runAllTasks()`（`MinecraftServer.java:891`）→ `pollTaskInternal`（927）→ **`level.getChunkSource().pollTask()`**（933）执行——每 tick 前后 `waitUntilNextTick` 循环里批量跑 ChunkHolder 任务（读盘 → 生成 → 晋级 → FULL → 解包）。

## 四、加载需求（ticket）来源

- **玩家移动**：`ChunkMap.tick()` 里 `updateChunkTracking(player)` 每 tick 更新玩家 ticket（视野/模拟距离）
- 强制加载（forceload）、传送、生成

## 五、卸载不立刻存盘（异步保存模型）

`ChunkMap.tick(haveTime)` → `processUnloads`（`ChunkMap.java:474-513`）：

1. **`scheduleUnload` 是异步 CompletableFuture 链**（`ChunkMap.java:515-524`）：`saveSyncFuture.thenRunAsync(...)`——区块从内存移除（`visibleChunkMap.remove`）要等保存完成，但保存本身在异步线程（IO worker / RegionFile 缓存），主线程不阻塞
2. **`saveChunksEagerly` 每 tick 限流**（`ChunkMap.java:497-513`）：每 tick 最多写 20 个（`eagerlySavedCount < 20`）、全局并发写不超过 128（`activeChunkWrites < 128`）——卸载高峰时区块先在 `chunksToEagerlySave` 队列里等
3. **`isUnsaved` 标记**：未及时异步保存的区块靠标记拖到下次 `saveAllChunks`（自动保存/暂停/关服，flush 全量同步写盘）

| 时机 | 发生什么 |
|---|---|
| 区块卸载（主线程） | `unload` → 容器从 `LevelTicks` 注销 → 标记 unsaved → 进 eager 队列 |
| 每 tick（主线程） | `saveChunksEagerly` 限流触发异步保存（IO 线程写 RegionFile） |
| 自动保存 / 暂停 / 关服 | `saveAllChunks`：所有 unsaved 区块同步写盘（flush） |
| 保存完成后 | 异步链把区块从内存彻底移除 |

## 六、对 SafeSave 的意义

| SafeSave 钩子 | 触发阶段 | 冻结期间 |
|---|---|---|
| `ServerLevel.tick` HEAD（计划刻统一重建） | 每个非冻结 tick 最开头 | ⛔ 跳过且不更新 knownChunks（解冻后重建） |
| `unload` HEAD（计划刻快照） | `chunkMap.tick(haveTime)` 的 processUnloads | ✅ 照常快照 |
| `ServerLevel.tick` HEAD（活塞/实体管理） | tick 最开头 | ✅ 照常运行 |

要点：
- **阶段 1 起，计划刻恢复不再挂在 `unpackTicks` 上**，而是每个**非冻结** tick 开头对 `LevelTicks.allContainers` 做 diff，统一重建新加载区块。
- **冻结期间区块的加载/卸载/快照全部照常发生**，只有方块刻执行（step 5）、方块事件（step 8）和计划刻重建被冻结门控；解冻后第一个正常 tick 会把冻结期间加载的区块一并重建。
- **快照是主线程同步读内存容器**（unload 发生在 processUnloads 之前），不依赖异步保存，数据总是完整
- 但 side file 只在 `saveAllChunks` 写、vanilla 区块 NBT 在卸载后异步写 → **崩溃场景可能"区块 NBT 比 side file 新"**（卸载→崩溃之间的刻只在区块 NBT 里）→ 重启后 safesave 旧刻覆盖较新的 vanilla 刻 → 回滚到上次 `saveAllChunks` 时间点，语义上就是"回滚到自动保存点"，可接受

## 七、C2ME 的差异

C2ME 用自研 `NewChunkStatus` 状态机接管了 `runDistanceManagerUpdates`/ChunkHolder 推进：

- **卸载路径**：`ReadFromDisk.downgradeFromThis`（ref 版本已把 `ServerLevel.unload` 逻辑内联：`setLoadedToWorld(false)` + lifecycle + `unloadEntities`）
- **解包时机不同**：C2ME 的 `ServerBlockTicking` 晋级只发 `ChunkLevelType` 事件、不直接调 `unpackTicks`——解包仍由 vanilla 调用链触发，但**时机随状态机变化**（模拟距离外区块可能长时间不解包）→ `ready` 集合不含未解包容器 → 该区块留到后续 tick 重试；若始终不 unpack 则该区块刻不保存（功能减弱，非崩溃）
- **异步序列化**：C2ME 在子线程 `SerializedChunk.fromChunk` 读 tick 容器（含锂结构）→ 与主线程写存在数据竞争（C2ME 与锂之间的既有问题，见[兼容性分析](lithium-c2me-conflict-analysis.md)）

## 八、相关源码

### 原版 Minecraft 26.1（本地 loom 反编译源码）

```
D:\Java\SafeSave2\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-07da9a845a\26.1\
  minecraft-merged-07da9a845a-26.1-sources\net\minecraft\server\level\ServerLevel.java:345-458    (ServerLevel.tick)
  ...\net\minecraft\server\level\ServerChunkCache.java:285-338                                  (runDistanceManagerUpdates / tick)
  ...\net\minecraft\server\level\ServerChunkCache.java:320-338                                 (tick)
  ...\net\minecraft\server\level\ChunkMap.java:450-524                                           (tick / processUnloads / scheduleUnload)
  ...\net\minecraft\server\level\ChunkMap.java:1170-1202                                         (tick() 玩家 ticket)
  ...\net\minecraft\server\MinecraftServer.java:891-933                                          (runAllTasks / pollTaskInternal)
  ...\net\minecraft\server\MinecraftServer.java:1119-1182                                        (tickChildren)
```

官方仓库：[github.com/Mojang/minecraft](https://github.com/Mojang/minecraft)（对应 26.1 版本分支）

### SafeSave 项目（GitHub: Melationin/SafeSave）

- [ServerLevelMixin（tick HEAD / unload HEAD）](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/mixin/ServerLevelMixin.java)
- [ScheduledTickManager（统一重建/快照）](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/safesave/scheduled/ScheduledTickManager.java)
