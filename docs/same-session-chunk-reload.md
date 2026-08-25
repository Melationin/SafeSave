# 同会话区块卸载-重载的计划刻恢复

> 状态：**已实施（阶段 1）**（2026-08-25 代码完成，待实机验证）
>
> 场景：不重启游戏，区块卸载后重新加载（玩家来回走动、模拟距离变化、C2ME 卸载重载等）。
>
> 相关文档：[文档索引](README.md) · [锂/C2ME 兼容性分析](lithium-c2me-conflict-analysis.md) · [实体序号接入](entity-order-integration.md) · [原版区块加载机制分析](vanilla-chunk-loading.md)

## 问题（阶段 1 之前的形态）

同会话内区块卸载 → 重新加载，该区块的 **safesave 计划刻数据无法恢复**（退回 vanilla 重新锚定行为，绝对 `triggerTick`/全局 `subTickOrder` 丢失），且**还会破坏跨会话重启恢复**（见"额外隐患"）。

## 根因（阶段 1 之前的形态）

恢复链路依赖 `pendingRestore`（恢复队列），而它**只在启动读文件时填充**：

| 环节 | 行为 | 源码 |
|---|---|---|
| 启动加载 | `SafeSaveStore.load`：对所有 chunk 条目 `pendingRestore.add(packed)` | `SafeSaveStore.java:277` |
| 卸载快照 | `snapshotChunk` → `snapshot` → `store.put`：**只更新 `chunks` map，不加 `pendingRestore`** | `SafeSaveStore.java:148-155` |
| 重载检查 | `hasPendingRestore`：`pendingRestore.contains(chunkKey)` → 同会话重载时为 **false** | `ScheduledTickManager.hasPendingRestore` |
| 消费 | `take`：`pendingRestore.remove` 失败则返回 null → 不恢复 | `SafeSaveStore.java:161-167` |

同会话重载的区块走 `LevelChunk.unpackTicks` TAIL → `restoreChunk` → `hasPendingRestore` 为 false → **不恢复**，容器保留 vanilla 重新锚定的刻（`triggerTick = 当前 + delay`、`subTickOrder = -N..-1`）。

## 阶段 1 的修复

> 阶段 1 同时把整个计划刻恢复架构从「`unpackTicks` 钩子 + 首刻扫描」改为「**每个非冻结 tick 开头统一重建新加载区块**」。这里记录与旧缺口直接相关的部分，完整时序见 [原版区块加载机制分析](vanilla-chunk-loading.md) 和 [DESIGN.md](../DESIGN.md)。

### 1. 卸载路径快照成功后标记待恢复

```java
// ScheduledTickManager.snapshot（卸载路径 addToPendingRestore=true）：
store.put(dimensionId, packedChunkPos, new ChunkSnapshot(block, fluid));
data.pendingRestore.add(packedChunkPos);
```

- `pendingRestore` 保证同一会话内重新加载的区块也会被后续正常 tick 开头消费。
- 全量保存路径（`snapshotLevel`）仍**不加** `pendingRestore`：否则所有加载区块都会进恢复队列，卸载时快照会被 `pendingRestore` 守卫跳过，恢复旧快照 → 重复执行。

### 2. 移除 `unpackTicks` 钩子，恢复改到非冻结 tick 开头

- 删除 `scheduled.LevelChunkMixin`（`unpackTicks` HEAD/TAIL），并从 `safesave.mixins.json` 移除。
- `ScheduledTickManager.onLevelTickStart` 每个**非冻结** tick：
  1. 从 `LevelTicks.allContainers`（方块 + 流体）取“已注册且已解包”的容器集合 `ready`；
  2. 与上次正常 tick 记录的 `knownChunks` 求差 → 新加载区块；
  3. 对 `ready ∩ pendingRestore` 的全部候选统一调用 `restoreInto` 重建；
  4. 更新 `knownChunks = ready`。
- 冻结期间（`runsNormally() == false`）跳过且不更新 `knownChunks`，所以冻结期间加载的区块在解冻后的第一个正常 tick 一并重建。

### 3. 安全性论证（阶段 1）

- **不会重复执行**：`restoreInto` → `take` 消费（`pendingRestore.remove` + `chunks.remove`）→ 快照只应用一次；重载后容器内是恢复的绝对刻，下次快照重新捕获最新数据。
- **快照时刻最新**：卸载时刻的快照是最新最准的（不是 `saveAllChunks` 时刻），恢复不会引入已执行的旧刻。
- **新加载检测与解包顺序解耦**：`ready` 只包含无 `pendingTicks` 的容器，未解包的区块会留在后续 tick 重试；兼容锂的 `allTicks` 残留索引问题（见 [锂/C2ME 兼容性分析](lithium-c2me-conflict-analysis.md)）。
- **文件格式不变**：`pendingRestore` 不序列化，启动时从 `chunks` 全量重建，现状不变。
- **副作用**：`/safesave status` 的 `pendingChunkCount` 在"卸载未重载"窗口内短暂虚高（可接受）。

## 额外隐患（阶段 1 已消除）

同会话重载后容器里是 vanilla 重新锚定的刻 → **下次 `saveAllChunks` 快照（`snapshotLevel` → `snapshot` → `put`）会覆盖** chunks map 里的绝对时间快照 → 连**跨会话重启**也恢复不了这批刻的绝对时间。

阶段 1 中重载会走 `pendingRestore` 消费，容器很快恢复为绝对刻；即使窗口期发生全量保存，`snapshot` 的 `pendingRestore` 守卫也会跳过该区块、保留旧绝对快照。

## 相关源码

### SafeSave 项目（GitHub: Melationin/SafeSave）

- [SafeSaveStore.put / take](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/safesave/SafeSaveStore.java#L148-L167) — 快照写入 / 消费（要求 pendingRestore）
- [SafeSaveStore.load](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/safesave/SafeSaveStore.java#L277) — 启动时填充 pendingRestore
- [ScheduledTickManager.snapshotChunk / snapshot](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/safesave/scheduled/ScheduledTickManager.java) — 卸载快照路径（写入后加 pendingRestore）
- [ScheduledTickManager.onLevelTickStart / restoreInto](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/safesave/scheduled/ScheduledTickManager.java) — 新加载区块统一重建
- [safesave.mixins.json](../src/main/resources/safesave.mixins.json) — 已移除 `scheduled.LevelChunkMixin`

### 原版 Minecraft 26.1（本地 loom 反编译源码）

```
D:\Java\SafeSave2\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-07da9a845a\26.1\
  minecraft-merged-07da9a845a-26.1-sources\net\minecraft\server\level\ServerLevel.java:345-458    (ServerLevel.tick)
  ...\net\minecraft\server\level\ServerChunkCache.java:320-338                                   (ServerChunkCache.tick)
  ...\net\minecraft\server\level\ChunkMap.java:450-513                                           (ChunkMap.tick / processUnloads)
```

官方仓库：[github.com/Mojang/minecraft](https://github.com/Mojang/minecraft)（对应 26.1 版本分支）
