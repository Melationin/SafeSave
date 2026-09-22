# SafeSave 设计文档（v5 · 对应 FORMAT_VERSION = 5）

本文记录 **当前的改动清单、每处改动的意图，以及做它的理由** —— 包括被否决的替代方案、开发中发现的 bug，以及哪些结论有实测支撑、哪些只是代码推论。

| 文档 | 内容 |
|---|---|
| `README.md` | 功能说明 —— 做什么、怎么用、已验证行为 |
| **本文 `DESIGN.md`** | 当前架构 + 每个决定的**意图**与取舍 |
| `docs/` | 专题分析：同会话重载、锂/C2ME 兼容、实体序号、原版区块加载机制 |

**本文只描述 v5。** 旧的 v3 架构（全部数据走单一侧存 `safesave.dat`）已被 v5 取代，差异见 §10。

参考源码：

- 原版 **26.1**：`F:\source\minecraft-merged-07da9a845a-26.1-sources`（本文行号均以此为准）
- 原版 **26.2**：`F:\source\minecraft-merged-5f54b0a1c5-26.2-sources`
- 兼容性参考：`ref/lithium`、`ref/C2ME-fabric`（只读，非本 mod 代码）

---

## 1. 动因：上游到底丢了什么

以下每一条都在 26.1 源码中核实过。

### 1.1 计划刻与方块事件

| # | 缺陷 | 证据（26.1） |
|---|---|---|
| L1 | **绝对触发时刻丢失**。`LevelChunk.unpackTicks(gameTime)` 把相对 `delay` 重锚到「该区块开始 block-ticking 的时刻」 | `ScheduledTick.java:53-55` `toSavedTick()` 只存 `(int)(triggerTick - currentTick)`；`SavedTick.java:49-51` `unpack()` 再 `currentTick + delay` |
| L2 | **`subTickOrder` 被按区块重编号为 `-N..-1`**，跨区块全局顺序被摧毁 | `LevelChunkTicks.java:108-118`（`subTickBase = -pendingTicks.size()`）；`ScheduledTick.java:10-19` `subTickOrder` 是最终裁决项 |
| L3 | **`Level.subTickCount` 完全不持久化**，重启归零 | 全仓仅 `Level.java:140`（声明）与 `:1093`（`nextSubTickCount()`）；**无任何序列化路径** |
| L5 | **`schedule` 不调用 `markUnsaved`**，只多了一条计划刻的区块不会被重写 | `LevelTicks.java:40-44` `chunkScheduleUpdater` 只更新调度，不标脏；`net.minecraft.world.ticks` 包内无任何 `markUnsaved` |
| — | **`ServerLevel.blockEvents` 完全不落盘**，在途方块事件全丢 | `ServerLevel.java:217` `public final ObjectLinkedOpenHashSet<BlockEventData> blockEvents`；无序列化 |

### 1.2 移动中的活塞

| # | 缺陷 | 证据 |
|---|---|---|
| #2 | **存 `progressO` 而非 `progress`**，存档倒退半步，每个存读周期丢 1 tick；蜜块乘客多拖 0.5 | `PistonMovingBlockEntity` 存档/save 路径 |
| #3 | **跨区块推动在加载后不原子**。PME tick 逐区块门控，区块错峰上线 → 一次推动分批 finalize | `LevelChunk` tick 门控 |
| #4 | **BE tick 顺序改变**。存盘前是创建序，重载后是 `BlockPos` 哈希序 | `Level.blockEntityTickers` 是 `ArrayList`（`:116`），重载按 `HashMap` 重注册 |
| #5 | **`lastTicked` 不落盘**，`checkIfExtend` 少一个决定 `TRIGGER_DROP`/`TRIGGER_CONTRACT` 的分支 | PME 构造 |
| #6 | **MOVING_PISTON 丢 BE = 永久鬼影**，`newBlockEntity` 返回 `null` 无法自建 | **未修**，见 §7 |

### 1.3 实体 tick 顺序

原版 `EntityTickList`（`EntityTickList.java:12` `private Int2ObjectMap<Entity> active = new Int2ObjectLinkedOpenHashMap<>()`）的 tick 序 = 实体进入列表的顺序，由区块晋级时机与异步反序列化完成顺序决定，**重启后必然变化**。

---

## 2. 当前架构（v5）：双层存储

v5 与 v3 的根本区别：**计划刻与方块事件搬进了区块自己的 NBT**，旁置文件只保留**维度级**数据。

```
┌─ 区块 NBT 的 safeSave 子标签 ──────────────────── 随区块落盘，粒度 = 区块
│    block[]        区块内方块计划刻
│    fluid[]        区块内流体计划刻
│    block_events[] 区块内方块事件
│    snapshot_game_time  保存时的 gameTime（用于重锚定）
│
│  SafeTick      { i, x, y, z, tt=绝对触发刻, p=优先级, so=原始全局subTickOrder }
│  SafeBlockEvent{ i, x, y, z, a, b, o=全局递增序号 }
│
└─ <world>/dimensions/<ns>/<dim>/data/safesave.dat ── 维度级旁置，粒度 = 维度
     version = 5
     debug { serverTickCount }              // 仅诊断
     levels [{ dimension, subTickCount,     // ← 关键，见 §5.1
               gameTime,                    // 仅诊断
               regions [{ name, chunks[], required_at_startup }] }]
```

**为什么是这两层**（§5.1 / §5.2 详述）：

- **计划刻、方块事件 → 区块 NBT**：它们天然属于区块，跟着区块一起卸载/重载，不需要额外的快照失效逻辑。
- **`subTickCount`、ProtectedRegion → 旁置**：它们是**维度级标量/定义**，无法挂在任何单个区块上；且 `subTickCount` 必须在任何区块被解析之前就位。

---

## 3. 生命周期与注入点（26.1 行号已逐条核实）

### 3.1 启动

```
Carpet onServerLoaded                     ← 实测对应 MinecraftServer.loadLevel HEAD (:429)
  └─ SafeSaveManager.onServerLoaded
       ├─ SafeSaveSession.begin()          新建会话
       └─ SafeSaveFiles.loadAll()          扫描 <world>/dimensions/*/*/data/safesave.dat

MinecraftServer.createLevels (:435)
MinecraftServer.prepareLevels (:578) HEAD  ← MinecraftServerMixin
  └─ SafeSaveManager.onLevelsCreated
       ├─ ScheduledTickManager.restoreSubTickCount()    ★ 必须早于任何区块解析，见 §5.1
       └─ ProtectedRegionCodec.load()                  恢复 Region 定义与启动目标

MinecraftServer.tickServer (:990) HEAD     ← MinecraftServerMixin
  └─ SafeSaveManager.onFirstServerTick       启动冻结 / 区域屏障判据跃迁
```

`loadLevel`(:429) → `createLevels`(:435) → `prepareLevels`(:437) 的先后是本设计的硬前提：**旁置文件必须先于区块解析进入内存**。

### 3.2 每刻（`ServerLevel.tick` HEAD，`ServerLevelMixin`）

```
SafeSaveManager.onLevelTickStart
  ├─ RegionLifecycle.beforeTick(level)      区域票据增删 + 主线程屏障（不论冻结与否）
  ├─ PistonManager.onLevelTickStart()       活塞刻顺序重建（不论冻结与否）
  └─ if (runsNormally())                    冻结期间跳过，且不更新 knownChunks
       ├─ ChunkRebuildCoordinator.rebuildNewChunks()   消费 pendingChunks
       └─ EntityOrderManager.rebuildChunks()
```

> 冻结期间刻意不更新 `knownChunks`：冻结期间加载的区块会在解冻后第一个正常 tick 被统一视为"新加载"并恢复。
> 冻结期间 `ServerLevel.tick` 本身照跑（不受 `tickRateManager` 门控），所以区域与活塞逻辑仍需在冻结中工作。

### 3.3 区块读 / 写

```
读：SerializableChunkData.parse (:110) HEAD          ← SerializableChunkDataMixin
      └─ SafeSaveManager.onChunkTagRead
           └─ ChunkNbtBridge.onChunkTagRead   → levelState.pendingChunks

写：SerializableChunkData.copyOf (:336) RETURN       ← SerializableChunkDataMixin
      └─ SafeSaveManager.onChunkSerializing → 存入 record 实例的 @Unique 字段
    SerializableChunkData.write (:415) RETURN        ← @ModifyReturnValue
      └─ SafeSaveManager.injectChunkData    → root.put("safeSave", tag)
```

**这套交接为什么成立**（关键，已核实）：

```java
// ChunkMap.java:762-763
SerializableChunkData data = SerializableChunkData.copyOf(this.level, chunk);
CompletableFuture<CompoundTag> encodedData = CompletableFuture.supplyAsync(data::write, Util.backgroundExecutor());
```

**同一个 `data` 实例**从服务器线程的 `copyOf` 交给后台线程的 `data::write`，且经 `CompletableFuture` 存在 happens-before。因此用 record 实例上的 `@Unique CompoundTag SS$safeSaveTag` 做跨线程交接是安全的，不需要原来的 `IdentityHashMap` 交接表。卸载路径（`ChunkMap.java:528`）走同一个私有 `save()`(:741)，行为一致。

### 3.4 存档

```
MinecraftServer.saveAllChunks (:621) HEAD            ← MinecraftServerMixin
  └─ SafeSaveManager.saveAll → SafeSaveFiles.saveAll
       ├─ 逐维度写旁置文件（subTickCount / regions）
       └─ 区块数据由 SerializableChunkDataMixin 在随后的每个区块保存中写入
```

**为什么挂在 HEAD 而非 RETURN**：`flush=true` 时原版会在保存过程中跑 `ChunkMap.processUnloads`（`ChunkMap.java:438`），那会 `unregisterTickContainerFromLevel`；到 RETURN 时一部分世界已从 `allContainers` 消失。

### 3.5 关闭

```
Carpet onServerClosed  ← stopServer (:674) HEAD
  └─ SafeSaveManager.saveAll       只写旁置；会话刻意保留，见 §5.5
MinecraftServer.stopServer 内最终存档：this.saveAllChunks(false, true, false)  (:708)
```

关闭时**跳过 `requiredAtStartup` 重算**（`!server.isStopped()`）：关闭流程先排干并卸载全部区块，此刻"完整加载"判据必然失败，重算会把上次正常保存捕获的启动目标全部抹掉。

### 3.6 注入点速查表

| Mixin | 目标 | 注入点 | 26.1 位置 | 为什么是这里 |
|---|---|---|---|---|
| `MinecraftServerMixin` | `prepareLevels` | HEAD | `:578` | 「levels 与 store 同时可用」的最早时刻；恢复 `subTickCount` 必须早于任何新刻被分配（§5.1） |
| 同上 | `tickServer` | HEAD | `:990` | freeze 必须在任何东西前进之前 |
| 同上 | `saveAllChunks` | HEAD | `:621` | 见 §3.4 |
| `ServerLevelMixin` | `tick` | HEAD | — | 每刻维护（区域票据、活塞重建、新加载区块统一重建）的落点 |
| 同上 | `shouldTickBlocksAt(J)` | HEAD cancellable | `:461` | 区域外区块禁随机刻 |
| 同上 | `tickChunk` | HEAD cancellable | `:486` | 同上 |
| 同上 | `tickNonPassenger` / `tickPassenger` | HEAD cancellable | `:812` / `:826`(private) | 区域外实体不 tick；玩家例外 |
| 同上 | `blockEvent` | **TAIL** | `:1238` | 见 §6.3（已知选点缺陷，暂不改） |
| `SerializableChunkDataMixin` | `parse` | HEAD | `:110` static | 唯一能看到**原始区块 NBT** 的加载点，首参即 `ServerLevel`（维度已知） |
| 同上 | `copyOf` | RETURN | `:336` static | 保存侧唯一能同时拿到**世界**与**区块**的点 |
| 同上 | `write` | `@ModifyReturnValue` | `:415` | 保存侧唯一能拿到**最终 NBT** 的点 |
| `ChunkMapMixin` | `saveAllChunks` | HEAD | `:419` | 存档前把受保护区块标脏 |
| 同上 | `save(ChunkAccess)` | `@WrapOperation` `require=2, allow=2` | `:528` + `:727` | 恰有两处直调点；**不要在 `save` 内部标脏**，flush 循环会永不结束 |
| `ServerChunkCacheMixin` | `addTicket(UNKNOWN,·)` in `getChunkFutureMainThread` | `@WrapOperation` | `:240`（唯一一处） | 内部 `getBlockState`/`getBlockEntity` 会在区域票据下反复续 UNKNOWN 票，把活动机器变成永久区块加载器 |
| `ServerChunkCacheMixin` | `tickSpawningChunk` | HEAD cancellable | — | 区域外不刷怪 |
| `LevelTicksMixin` | `allContainers` | `@Shadow` | `:34` | 暴露「本维度已加载至至少 FULL 的每个区块」 |
| `LevelChunkTicksMixin` | `pendingTicks` / `ticksPerPosition` | `@Shadow` | `:19` / `:20` | 见 §6.4 |
| `EntityTickListMixin` | `active` / `add` | `@Shadow` / HEAD | `:12` / `:31` | `add` 是所有实体进入 tick 列表的唯一入口 |
| `PistonMovingBlockEntityMixin` | 6 参 `<init>` | TAIL | — | 用完整描述符精确锁定 `moveBlocks` 用的那个构造器；2 参构造器是反序列化路径，序号从 NBT 来 |
| 同上 | `saveAdditional` / `loadAdditional` | TAIL | — | 在 vanilla 写完/读完之后追加 |
| `EntityMixin` 等 | `saveWithoutId`/`load` 等 | TAIL | — | 同上，追加 `safeSave` 子标签 |
| `TagValueOutputMixin` | `output` 字段 | 接口注入 | `:19`（`private final`，经 classtweaker 放开） | 需要"取已存在的子标签"而非"新建" |

---

## 4. 数据格式细节

### 4.1 区块 `safeSave` 子标签

```java
// SafeSaveStore.saveChunkData / loadChunkData
block[]               SafeTick 列表
fluid[]               SafeTick 列表
block_events[]        SafeBlockEvent 列表，按 order 升序
snapshot_game_time    long；缺失 = Long.MIN_VALUE = 旧数据，跳过重锚定
```

`tt` 是**绝对** `triggerTick`（不是 `delay`），`so` 是**原始全局** `subTickOrder` —— 这两个正是原版丢掉的。

`snapshot_game_time` 只用于重锚定：区块卸载期间游戏时间继续走，重新加载时已过期的绝对触发时刻需按保存时的**剩余间隔**顺延，语义等价于原版 `SavedTick.delay` 的重新锚定：

```java
// util/ResumeTime.java
public static long rebase(long time, long savedAt, long resumedAt) {
    return savedAt == Long.MIN_VALUE ? time : resumedAt + (time - savedAt);
}
```

### 4.2 旁置文件

```
{ version: 5,
  debug: { serverTickCount: int },                    // 仅诊断
  levels: [{ dimension: "minecraft:overworld",
             subTickCount: long,                      // ★ 恢复 Level.subTickCount，见 §5.1
             gameTime: long,                          // 仅诊断
             regions: [{ name, chunks: long[], required_at_startup: bool }] }] }
```

版本不符即 `throw new IllegalStateException("unsupported safe-save format version " + version + " (expected " + FORMAT_VERSION + ")")`；`SafeSaveFiles.loadFile` 捕获后告警并跳过该维度。**时序数据读错比读不到更糟**，所以宁可明确失败也不猜着读。

`debug.serverTickCount` 与每维度 `gameTime` **恒定只用于诊断**，恢复路径从不读取。唯一用途是在它与实时 `gameTime` 不一致时发一条 stale 告警（`ScheduledTickManager.warnIfStale`），提示侧存文件与 `level.dat` 脱节（通常是某次会话把规则关了）。

### 4.3 活塞字段（走 PME 自己的 NBT）

```
safeSave: { progress, progress_o, lastTicked, snapshotGameTime, order }
```

**vanilla 的 `progress` 键一字不改**，只**追加** `safeSave` 子标签。意图：卸载本 mod 后世界退化成 vanilla 行为，而不是留下 vanilla 读不懂的数据。

### 4.4 实体字段（走实体自己的 NBT）

```
safeSave: { motion, tick_count, first_tick, stuck_speed_multiplier, piston_deltas,
            piston_deltas_game_time, no_physics, requires_precise_position, move_dist,
            fly_dist, in_powder_snow, was_in_powder_snow, was_touching_water,
            was_eye_in_water, main_supporting_block_pos, on_ground_no_blocks, pose,
            entity_order }
```

`entity_order` 为 `Long.MIN_VALUE` 表示未知（本会话新生成）。

---

## 5. 关键设计决策

### 5.1 ★ 为什么 `subTickCount` 一定要走旁路

这是整个持久化设计里唯一**无法回避**的旁置数据，值得完整论证。

**（a）`subTickOrder` 是什么**

```java
// LevelAccessor.java:31,36
new ScheduledTick<>(type, pos, this.getGameTime() + tickDelay, priority, this.nextSubTickCount())
// Level.java:1093-1094
public long nextSubTickCount() { return this.subTickCount++; }
// ScheduledTick.java:10-19
DRAIN_ORDER = triggerTick → priority → subTickOrder
```

`subTickOrder` 是**最终、也是唯一的完全平手裁决项**。它的语义是：**"我这条刻被排入时，这个世界此前一共排过多少条刻"** —— 每维度全局单调的发行序号。

**（b）原版把它整个丢掉**

```java
ScheduledTick.java:53-55  toSavedTick(t) → new SavedTick<>(type, pos, (int)(triggerTick - t), priority)  // so 丢弃
SavedTick.java:49-51      unpack(t, s)   → new ScheduledTick<>(..., t + delay, priority, s)
LevelChunkTicks.java:108-118  int subTickBase = -pendingTicks.size(); ... unpack(currentTick, subTickBase++)
```

`Level.subTickCount` 在整棵 26.1 源码树里**只有声明与自增两处**，`level.dat` 里不存在 → 重启归零。原版的补救是**伪造** `-N..-1`：保证"磁盘刻排在会话新刻之前"，但磁盘刻彼此之间、以及与未来新刻之间的**真实历史顺序全部丢失**。

**（c）SafeSave 为什么必须恢复它 —— 三个互相独立的要求**

> **R1 唯一性（防别名）**
> SafeSave 重新注入的是**原始绝对值**，占用区间 `[0, savedSubTickCount)`。计数器若从 0 重启，`nextSubTickCount()` 会把**已被恢复刻占用**的值再发一遍。两条活着的刻一旦 `(triggerTick, priority, subTickOrder)` 全相等，`DRAIN_ORDER` 返回 0 → 顺序落到 `PriorityQueue` 的堆内位置 → **任意且依赖插入序列**。全序退化成偏序。

> **R2 跨重启的时间序（比想象中更强）**
> `subTickOrder` 不只是区块内平手裁决项。`LevelTicks.java:32`：
> ```java
> CONTAINER_DRAIN_ORDER = (o1, o2) -> ScheduledTick.INTRA_TICK_DRAIN_ORDER.compare(o1.peek(), o2.peek());
> // INTRA_TICK_DRAIN_ORDER = priority → subTickOrder（没有 triggerTick）
> ```
> `containersToTick` 是「按容器队首排序的优先队列」，决定**先抽哪个区块的刻**。其比较器里**没有 `triggerTick`，只有 `priority → subTickOrder`** —— 所以 `subTickOrder` **直接决定跨区块的抽取顺序**。
>
> 计数器不恢复时：恢复刻 A 带原值 3702，重启后新刻 B 拿到 0 → A、B 平手时 **B 先执行**，而 A 在世界历史中早于 B 排入 → **顺序被反转**。恢复计数器后，所有后续发行值严格大于任何恢复值 → 正确。

> **R3 时序（这一条才真正决定"必须走旁路"）**
> `restoreSubTickCount` 挂在 `prepareLevels` HEAD。原因是：**区块解析/解包本身就会排刻**（方块实体、后处理、邻居更新），每次排刻都调用 `nextSubTickCount()` 并**永久消耗**一个值。计数器必须在**第一个区块被解析之前**抬到保存值，否则已发出的低值立刻与恢复值别名（回到 R1）。
>
> 于是形成一个死结：
>
> > **上界必须在「任何区块被读取」之前已知；而从区块里推导上界，恰恰需要读取区块 —— 读取区块就是产生那些会造成冲突的值的动作本身。**
>
> 已核实时序成立：`loadLevel`(:429) → `createLevels`(:435) → `prepareLevels`(:437)，旁置在读盘阶段载入，早于区块解析。

**（d）v5 让旁路变得**更**必要，而不是更不必要**

v3 时侧存保存了全部待执行刻，计数器与刻在**同一次 `saveAllChunks` 中写盘**，是一个自洽快照。

v5 把刻搬进各区块 NBT，而区块的保存/卸载时刻是**错开**的（`ChunkMap.java:528` 卸载路径、`:727` saveChunkIfNeeded）。更关键的是 v5 的恢复是**惰性、逐刻**的（`ChunkRebuildCoordinator.rebuildNewChunks` 每非冻结 tick 恢复新加载区块）：一个在 tick 5000 才恢复的区块会带回**重启前**的序号，而 tick 1..4999 期间新排的刻早已占用了 0..N —— 又是别名。

> 本质区别：**恢复值来自另一个时间纪元，新发行值属于当前纪元。只有在新纪元开始之前确立的上界，才能把两者分开。** 侧存就是这个上界；区块里的数据给不了，因为它们到达得比"它们必须压过的那批发行"更晚。

**（e）为什么"一个标量"就够**

`SafeSaveFiles.saveAll` 在 `saveAllChunks` HEAD 写 `data.subTickCount = level.subTickCount`。由于 `nextSubTickCount()` 是**后自增**，任意时刻该维度**曾经发行过的每一个值都严格小于当前 `subTickCount`**。所以：

> 保存下来的这个标量是**保存时刻该维度全境（含更早或更晚写盘的各版本区块）所有 `subTickOrder` 的严格上界**。

在 `prepareLevels` HEAD 一次性恢复它，就使该不变量对**之后任意时刻恢复的任意区块**都成立。代价极小：每维度一个小 NBT 文件，读取代价与世界大小无关；若改从区块推导，代价是一次全地图区块扫描。

**（f）边界情况**

```java
if (data.subTickCount > current) { level.subTickCount = data.subTickCount; }   // 绝不让计数器倒退
```
- `DimensionData.subTickCount` 默认 `-1L`，`SafeSaveStore.load` 用 `getLongOr(KEY_SUB_TICK_COUNT, -1L)` → 缺失不恢复 ✓
- 世界从未排过刻时保存值为 0 → `0 > 0` 为假 → 不恢复。此时无任何恢复值可别名，正确 ✓

**（g）能不能不要它**

| 替代方案 | 结论 |
|---|---|
| 存相对 `delay` 而非绝对序号 | 不需要计数器，但**平手无解**，恰好丢掉本模组存在的理由（L2） |
| 恢复时压缩重编号（照抄 `-N..-1`） | 只需 `max(恢复值)+1`，但既破坏 R2 要求的相对间距，又仍需先知道全部恢复值（R3 死结未解） |
| 从区块推导上界 | 需启动时全量扫区块；且在 v5 惰性恢复下仍然别名 |

**结论**：对于「保留绝对全局顺序」+「惰性恢复区块」这套设计，旁置的 `subTickCount` 不是优化，而是那个上界**唯一可能存放的地方** —— R1/R2/R3 三条约束的唯一交集解。

### 5.2 计划刻从侧存改为区块 NBT（v3 → v5）

**不是**为了对称，而是 v3 的侧存模型有三个具体代价：

1. **全量重写**：每次 `saveAllChunks` 都要把全维度所有待执行刻重新序列化进一个文件，世界越大越贵。
2. **失效逻辑**：侧存里的条目与活着的区块是两个真相源，需要 `pendingRestore` 之类的机制来对齐（v3 的多个 bug 都出在这里，见 §6）。
3. **孤儿数据**：区块被删除/世界被裁剪后，侧存里会留下永远不会被消费的条目。

v5 让数据**跟着区块走**：区块卸载时随之落盘、加载时随之回来，不需要独立的失效逻辑。

**接受的代价**（L5 重新暴露）：原版 `schedule` 不 `markUnsaved`（§1.1 L5），所以"只多了一条计划刻、区块没有任何其它改动"时，区块不会被重写，该刻不会落盘。

**这是被接受的设计取舍**：该情形下**退化到原版行为**（原版同样丢），而不是劣于原版；本设计的底线是任何路径都不能不如 vanilla。受保护区域区块另有兜底：`ChunkMapMixin.SS$markBatch`（`ChunkMap.saveAllChunks` HEAD）与 `SS$forceProtectedSave`（`save` 的 `WrapOperation`）会在存档前把受保护区块标脏。

### 5.3 活塞与实体字段走各自的 NBT

它们跟着方块实体 / 实体走，区块卸载/重载自动保持一致，不需要额外的快照/失效逻辑。放侧存反而要为它们再造一套 unload 快照机制。

### 5.4 重建是「替换 + 合并」，不是纯替换

恢复时先 `SS$replaceAll(绝对刻)`，然后把重建前抓到的既有刻重新 `schedule` 回去：

```java
// ScheduledTickManager.restoreChunkTicks —— keep 作为实参，在 applyTicks 执行 replaceAll 之前求值
int keptBlock = applyTicks(..., ((SafeTickContainer) blockContainer).SS$snapshotQueue(), ...);
// 内部：JS 实参从左到右求值 → snapshotQueue() 是 replaceAll 之前的物化副本（getAll().toList()）
```

**意图**：区块停在 FULL 但未 block-ticking 时仍可被排刻，纯替换会丢掉这些本会话新刻 —— 那是**比 vanilla 更差**。`schedule()` 按 `(type,pos)` 去重，因此恢复已覆盖的既有刻会在这里被自动丢弃而非重复。

### 5.5 `onServerClosed` 不清 store

Carpet 的 `onServerClosed` 在 `stopServer` HEAD 触发，而停服存档在 `ChunkMap`/`MinecraftServer.stopServer()` 的 `this.saveAllChunks(false, true, false)`（**`:708`**），**之后**。在这里清状态会静默跳过整个功能最重要的一次保存。改为只写旁置；所有会话状态由 `onServerLoaded` 重新初始化，不会泄漏到下一个世界。

### 5.6 快照「只读不消费」（peek 不 take）

`ChunkNbtBridge.onChunkSerializing` 只 **peek** `pendingChunks`，不 remove：

```java
// 待恢复快照只有在 rebuildNewChunks 消费后才会移除。这里只读取（peek），
// 这样在 load→rebuild 窗口内被保存多少次，写回磁盘的都是原始绝对快照。
SafeSaveStore.ChunkSnapshot snapshot = levelState.pendingChunks.get(key);
```

消费点唯一：`ChunkRebuildCoordinator.rebuildNewChunks` 在**恢复成功之后**才 `pendingChunks.remove(key)`。恢复失败则移除并丢弃快照（记 warn）。

### 5.7 ProtectedRegion：不做局部冻结，只做启动屏障

Region **不做局部冻结**：每次保存时，只把当时**全部区块均完整加载**的 Region 标记为下次启动目标（`requiredAtStartup`）；region 解冻模式会**全局冻结**服务器，直到这些目标再次全部加载或超时。

- 票据层级 `new Ticket(REGION, 31)`；26.1 常量 `FULL_CHUNK_LEVEL=33`、`BLOCK_TICKING_LEVEL=32`、`ENTITY_TICKING_LEVEL=31` → 该区块为 **ENTITY_TICKING**，FULL 状态外扩 `33-31=2` 区块，与 `RegionTicketPolicy.reaches(source, key, 2)` 严格对应。
- `RegionTicketPolicy.required` 是**纯函数**、从外部根重算（**绝不以昨天的区域票据为根**，否则自我续期永不卸载），可脱离 MC 生命周期单测（`regression/RegionRegressionTest.java`）。
- 超时用**服务器刻**而非 `gameTime`：freeze 期间 `gameTime` 不前进，用它做超时会永远不触发。

### 5.8 按维度用「生成计数器」而非布尔脏标记

`PistonMovingBlockEntity.loadAdditional` 运行时 BE 还没有 level，此刻不知道维度。所以让每个 level 记住自己上次重排的 generation（`SafeSaveLevelState.pistonOrderRebuiltAt` vs `SafeSaveSession.pistonOrderGeneration`）。

### 5.9 只重写活塞占用的槽位

不是整体排序 `blockEntityTickers`。收集移动活塞占用的下标，按创建序填回**同一批下标**，其余 ticker 一个不动。筛选用 `getBlockState(pos).is(Blocks.MOVING_PISTON)` 而非 `getBlockEntity`：后者走 `EntityCreationType.IMMEDIATE`，会把全 level 的 pending BE 提前实例化 —— 比 vanilla 更早创建 BE 是可观测的行为改变。这个选择顺带绕开了 26.1/26.2 的 `BlockEntityType`/`BlockEntityTypes` 命名差异。

---

## 6. 开发过程中发现并修掉的 bug

| 问题 | 后果 | 修法 |
|---|---|---|
| `onServerClosed` 清 store | 停服存档被静默跳过 —— 最重要的一次保存 | §5.5 |
| 启动 flush 存档覆盖未恢复条目 | 恢复被 vanilla 重锚数据顶掉 | §5.6 的 peek 语义 |
| 恢复丢弃本会话新排刻 | 比 vanilla 更差 | §5.4 |
| `snapshotChunk` 强转无 `instanceof` 保护 | `ImposterProtoChunk` 返回 `BlackholeTickAccess.emptyContainer()` → CCE | 补 guard |
| `pistonTickOrderDirty` 全局布尔但重排按 level | 下界的活塞被主世界的 tick 清了标记，永不重排 | 改生成计数器（§5.8） |
| 工程：`core.autocrlf=true` | `gradlew` 转 CRLF，Linux 上跑不起来 | `.gitattributes` 强制 `eol=lf` |
| **`LevelChunkTicks` 直读 `tickQueue`** | 锂的 `world.tick_scheduler` 会把该字段置 null → NPE | 改走公共 API `getAll()` + `removeIf` + `ticksPerPosition` 判空，见 `docs/lithium-c2me-conflict-analysis.md` |
| **锂 `allTicks` 残留索引拦截恢复刻** | 未解包容器不计入 `ready`，留到后续 tick 重试 | §3.2 的 `isReady` 判据 |

---

## 7. 已知限制

| 限制 | 说明 |
|---|---|
| **崩溃 / `kill -9`** | 自动存档异步写盘；非正常退出会丢掉上次存档之后的一切。正常 `/stop` 是安全的（§5.5 刻意不短路） |
| **L5 重新暴露** | 见 §5.2「接受的代价」。已确认 26.1 `LevelTicks.chunkScheduleUpdater`(:40-44) 不标脏，`world.ticks` 包内无 `markUnsaved` |
| **`blockEvent` 注入点为 TAIL** | `ServerLevelMixin` 在事件已入队后采样，无法直接识别 `ObjectLinkedOpenHashSet` 的去重；`BlockEventManager.onBlockEvent` 用自建序号表判重，而该表只在快照（存档）时经 `refreshOrders` 清理 → 已执行并已从队列移除、但表项仍在的 `BlockEventData`，会把**过期小序号**传给之后新入队的同参数事件。**已知缺陷，暂不修** |
| **恢复失败会固化漂移** | `ChunkRebuildCoordinator` 中 `restoreChunkTicks` 抛异常时丢弃快照；此时容器里是 vanilla 重锚值，下次存档会把它当作"绝对时刻 + 当前 gameTime"写回。仅一条 warn |
| **区块 `safeSave` 标签无 version 字段** | 与旁置文件的严格精确匹配策略不一致：旁置版本不符会明确报错，区块则会静默解析出部分/空数据 |
| **规则必须持久化** | 规则在 `loadLevel` HEAD 读取；`/carpet safeSave true` 若不点 `[Change permanently?]` 则只对本会话生效 |
| **#6 鬼影未修** | `MovingPistonBlock.newBlockEntity` 返回 `null`，方块无法自建 BE。正常路径下两者同份 chunk NBT 一致落盘，只在损坏或注册表变动时触发 |
| **从未 block-ticking 的区块** | 其 `pendingTicks` 没有绝对时序可存，不快照 |
| **注册表条目消失** | 对应刻/事件被丢弃并告警。`BLOCK`/`FLUID` 是 `DefaultedRegistry`，所以显式 `containsKey` 校验，而不是让 `getValue()` 静默返回 AIR/EMPTY |
| **常驻每刻开销** | `rebuildNewChunks` 每非冻结 tick 遍历全部容器；`RegionLifecycle.beforeTick` 每 tick 遍历全部票据；`RegionTicketPolicy.required` 是 O(regions² × chunks) 的不动点循环；`coveredByRegionTicket` 对每次区块加载线性扫描。单机无感，大体量场景需留意 |

---

## 8. 兼容性

**26.1 / 26.2 API 对等性**逐个核实过：`LevelTicks`、`LevelChunkTicks`、`ScheduledTick`、`SavedTick`、`TickPriority`、`ChunkPos`、`NbtIo`、`Identifier`、`ServerLevel` 成员、`LevelChunk.unpackTicks`/`getLevel`、`Registry.containsKey`、`ValueInput`/`ValueOutput` 全部一致，**无需 stonecutter 条件编译**。

实际撞到的**唯一**差异：`BlockEntityTypes`（26.2）在 26.1 叫 `BlockEntityType`。已通过改用 `Blocks.MOVING_PISTON` 判定绕开（§5.9）。

**降级行为**：规则关闭 → 完全 vanilla 行为，连 `safeSave` 子标签都不写。卸载 mod → 世界退化成 vanilla 行为（§4.3/§4.4）。

锂 / C2ME 的冲突分析与修复见 `docs/lithium-c2me-conflict-analysis.md`。

---

## 9. 构建与运行

```
JAVA_HOME=<jdk25> ./gradlew build          # SafeSave-26.1 / SafeSave-26.2
./gradlew regionRegressionTest             # 纯函数回归（已挂到 check）
tools/setup-void-server.sh [run-dir] [port]
tools/start-server.sh                      # 分离式，FIFO 控制台
```

需要 JDK 25 工具链（`sourceCompatibility = 25`）。版本配置：根 `gradle.properties` 提供公共项，`versions/<ver>/gradle.properties` 覆盖 `loader_version` / `fabric_api_version` / `carpet_core_version` / `minecraft_ver`。

⚠️ **`runServer` 运行期间不要跑 `./gradlew build`** —— Gradle 不允许同一项目并发两个 build，会终止 runServer（关得干净，但确实会停）。这条已写进 `tools/start-server.sh` 注释。

---

## 10. v3 → v5 差异（本文重写说明）

本文取代了旧的 v3 版 `DESIGN.md`。主要差异：

| 方面 | v3（旧文档） | v5（现状） |
|---|---|---|
| `FORMAT_VERSION` | 3，含 `MIN_READABLE_VERSION` 可读区间 | **5**，只接受精确匹配 |
| 计划刻存放 | 侧存 `chunks[]` 全量 | **区块 NBT 的 `safeSave` 子标签** |
| 方块事件存放 | 侧存 `block_events[]`（维度级） | **区块 NBT**（按区块）+ 全局 `order` |
| 旁置文件内容 | 计划刻 + 方块事件 + 活塞等待集 + `subTickCount` | **仅 `subTickCount` + `regions`** + 诊断字段 |
| 活塞字段键名 | `safesave_progress` 等平铺键 | `safeSave` 子标签下的 `progress` / `progress_o` / `lastTicked` / `order` |
| 恢复时机 | `LevelChunk.unpackTicks` HEAD/TAIL | **每非冻结 tick 开头统一重建新加载区块** |
| 恢复队列 | `DimensionData.pendingRestore`（侧存内） | `SafeSaveLevelState.pendingChunks`（内存态，peek 不 take） |
| 交接表 | `IdentityHashMap` | record 实例的 `@Unique` 字段（`CompletableFuture` 提供 happens-before） |
| 包名 | `com.example.carpet` | `com.carpet.safesave` |
| 文件名回落 | `LEGACY_FILE_NAME` 自动迁移 | **无**（已移除） |
| 调试设施 | `DebugSwitches`【编译期 DEBUG】/ `TickOwnerAware` / `commands/DebugCommand` | **均不存在**；仅保留 `debug/DebugLog`（info/warn/warnOnce） |
| `/safesave` 命令 | 调试命令树 | **仅 `region` 子树**（add/remove/addChunk/removeChunk/list/info） |
| 区域支持 | 无 | **ProtectedRegion + 区域票据 + 启动冻结屏障**（§5.7） |
| 实体序号 | 无 | **有**（§4.4） |

**已作废的旧结论**：

- 旧 §5.1「计划刻**必须**走侧存，因为写进区块 NBT 就绕不开 L5」—— 该论证已被 v5 的取舍取代，见 §5.2。真正**必须**走旁置的只有 `subTickCount`，理由见 §5.1。
- 旧 §8「一个未定位的 NPE：`LevelChunkTicks.tickQueue` 为 null」—— **已结案**。26.1 中该字段是 `private final Queue<ScheduledTick<T>> tickQueue = new PriorityQueue(ScheduledTick.DRAIN_ORDER)`（`LevelChunkTicks.java:17`，带内联初始化器），原版不可能为 null；该报告必然来自锂环境（锂按设计置 null，见 `docs/lithium-c2me-conflict-analysis.md`）。
- 旧 §3 中 `blockEvent` 选 HEAD 的理由，与现状（TAIL）不符，缺陷记录见 §7。
