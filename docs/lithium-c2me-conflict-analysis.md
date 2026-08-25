# 锂（Lithium）/ C2ME 与 SafeSave 的兼容性分析

> 状态：**已实施**（2026-08-25 完成代码修复；运行时验证通过）
>
> 结论：**唯一真冲突是计划刻容器 `LevelChunkTicks`**——锂的 `world.tick_scheduler`（默认开启）会把 `tickQueue`/`ticksPerPosition` 字段置 null。其余部分（实体顺序、活塞、方块事件、各生命周期钩子）均无冲突。

## 冲突机制

锂 `world.tick_scheduler.LevelChunkTicksMixin`（默认开启）在**构造器 RETURN** 注入：

```java
@Mutable @Shadow @Final private Set<ScheduledTick<?>> ticksPerPosition;
@Mutable @Shadow @Final private Queue<ScheduledTick<T>> tickQueue;
// reinit: pendingTicks 的 (type,pos) 索引 → 自建 allTicks；随后 ticksPerPosition = null; tickQueue = null;
// 刻改存自建 tickQueuesByTimeAndPriority（AVL 树）+ allTicks（IntOpenHashSet）
// @Overwrite schedule/peek/poll/hasScheduledTick/removeIf/getAll/count/pack/unpack
```

任何经过构造器的 `LevelChunkTicks` 实例，`tickQueue`/`ticksPerPosition` 恒为 null。`pendingTicks` 字段保留。

## 三方分析（锂 + C2ME 组合）

- **破坏者 = 锂**：构造器注入置空字段（堆栈无锂帧属正常——锂不在调用链上）
- **触发路径 = C2ME**：`ReadFromDisk.downgradeFromThis`（用户环境版本行 233）调 `ServerLevel.unload` → SafeSave 的 `unload` HEAD 快照钩子（ref 版本已把 unload 逻辑内联，行为相同）
- **单 C2ME（无锂）不崩**：C2ME 不创建/替换 `LevelChunkTicks` 实例、序列化只读不修改容器（`MixinChunkSerializer` 只碰 POI/section copy）
- **C2ME 自己做了锂适配**（参考实现）：`ChunkDataSerializer` 旁 `utils/LithiumUtil.java` 反射检测锂注入的 `tickQueuesByTimeAndPriority` 字段（`IS_LITHIUM_TICK_QUEUE_ACTIVE`），锂在场读 AVL 树、不在场用 `IChunkTickScheduler` accessor（getTicks/getTickQueue）

## 已实施的修复

`SafeTickContainer`（`mixin/scheduled/LevelChunkTicksMixin.java`）改为纯公共 API + 判空：

| 方法 | 原版 | 锂 |
|---|---|---|
| `SS$snapshotQueue()` → `getAll().toList()` | tickQueue 流 | @Overwrite 遍历 AVL 树 |
| `SS$replaceAll()` → `removeIf(t→true)` + `ticksPerPosition` 判空补清 + 逐个 `schedule()` | 原版 removeIf 只清 tickQueue、**不同步去重集合** → 必须补清 `ticksPerPosition`（判空） | removeIf 同步清 AVL 树 + `allTicks` + `nextTickQueue` 缓存；字段为 null 跳过 |
| `SS$hasPendingTicks()` → 读 `pendingTicks` | unpack 置 null | 字段保留 |

`ScheduledTickManager.onLevelTickStart` 每非冻结 tick 的新加载区块统一重建：**未解包（pendingTicks 非空）的容器不计入 `ready`，因此不会触发恢复**，留到后续 tick 重试——锂的 `reinit` 会把 pendingTicks 的 (type,pos) 索引提前放进 `allTicks`，未解包时 removeIf 清不到这些残留索引，会拦截恢复刻；unpack 完成后残留消失，容器进入 `ready` 才恢复。详见 [计划刻恢复的锂残留索引问题](#锂的-allticks-残留索引问题)。

### 出队序一致性（恢复效果不变的关键）

锂 `OrderedTickQueue` 按 `subTickOrder` 排序出队（`Comparator.comparingLong(ScheduledTick::subTickOrder)`），原版 FIFO 靠"插入序=subTickOrder 序"——safesave 恢复的刻按存档序（已排序）逐个 schedule，且 `restoreSubTickCount` 保证 keep 刻序号更大 → **两种实现出队序相同**。

## 锂的 allTicks 残留索引问题

**症状**：锂环境下恢复的计划刻被去重拦截（数量偏少）。

**机制**：锂的 `removeIf` 只清"已入桶"刻的 `allTicks` 索引；构造器 `reinit` 会把**未解包 `pendingTicks`** 的 (type,pos) 索引提前放入 `allTicks`——这些刻还没进桶，removeIf 清不到 → 残留索引拦截之后相同 (type,pos) 的恢复刻。

**修复**：恢复前检查 `SS$hasPendingTicks()`，未解包的容器不计入 `ready`，不在本 tick 重建；等它解包（pendingTicks 已清空、刻已进桶）后的某个正常 tick 进入 `ready`，removeIf 清得完整。曾用反射 `allTicks.clear()` 验证过假设（试验 mixin，已删除）。

## 已排除无冲突的部分

- **实体 `EntityTickList`**：锂 @Overwrite `ensureActiveIsNotIterated`（clone 保序）vs SafeSave @Inject `add` HEAD + `active` 重建（`Int2ObjectLinkedOpenHashMap`）——方法不重叠；clone 保留插入顺序；类型假设兼容
- **活塞 `PistonMovingBlockEntity`**：锂只注入 `getCollisionShape`（形状缓存）；SafeSave 注入 `<init>`/`saveAdditional`/`loadAdditional`。`alloc.enum_values.piston_*` 只是 `Direction.values()` → 常量数组
- **方块事件 `ServerLevel.blockEvents`**：锂完全不碰（`blockEvent` 命中仅 3 个 sleeping 相关 mixin，是方块实体自身的事件同步）
- **`ServerLevel.tick` / `MinecraftServer` / `LevelChunk.unpackTicks` / `LevelTicks` / `Entity.saveWithoutId`·`load` / `TagValueOutput`**：锂均无注入
- **`Level.blockEntityTickers` 列表结构**：锂的 sleeping/chunk_tickable/collections mixin 不改列表本身及索引语义
- **锂 sleeping 机制的 pos-null**：锂会把部分方块实体（箱子/熔炉/潜影盒等）ticker 的 `pos` 置 null——`PistonManager` 遍历整个列表时**必须判空**（`PistonManager.java` 已加防御，锂自己的 `dumpBlockEntityTickers` 也有同样防御）

## 残余风险（三方共存的固有问题，非 safesave 特有）

- **C2ME 异步序列化子线程读锂 AVL 树** vs 主线程 schedule/removeIf：数据竞争存在于 C2ME+锂之间（C2ME 用 `LithiumUtil` 反射适配），safesave 只能在主线程操作
- **C2ME 的 `notickvd` 模拟距离**：模拟距离外区块可能长时间不 unpack → `hasPendingTicks` 跳过 → 该区块刻不保存（功能减弱，非崩溃）

## 相关源码

### 锂（ref/lithium，develop 分支）

- `ref/lithium/common/src/main/java/net/caffeinemc/mods/lithium/mixin/world/tick_scheduler/LevelChunkTicksMixin.java` — 构造器置 null + 全部 @Overwrite
- `ref/lithium/common/src/main/java/net/caffeinemc/mods/lithium/common/world/scheduler/OrderedTickQueue.java` — 按 subTickOrder 排序的桶队列
- `ref/lithium/common/src/main/java/net/caffeinemc/mods/lithium/mixin/collections/entity_ticking/EntityTickListMixin.java` — ensureActiveIsNotIterated clone
- `ref/lithium/common/src/main/java/net/caffeinemc/mods/lithium/mixin/block/moving_block_shapes/PistonMovingBlockEntityMixin.java` — getCollisionShape 缓存
- `ref/lithium/common/src/main/java/net/caffeinemc/mods/lithium/mixin/world/block_entity_ticking/sleeping/*` — sleeping 机制（pos 置 null 来源）

### C2ME（ref/C2ME-fabric）

- `c2me-rewrites-chunk-system/.../common/statuses/ReadFromDisk.java` — 卸载路径（downgradeFromThis）
- `c2me-rewrites-chunk-serializer/.../common/utils/LithiumUtil.java` — 对锂的反射适配（标准答案）
- `c2me-rewrites-chunk-system/.../mixin/MixinWorldChunk.java` — 延迟方块实体加载

### SafeSave 项目（GitHub: Melationin/SafeSave）

- [LevelChunkTicksMixin（SafeTickContainer）](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/mixin/scheduled/LevelChunkTicksMixin.java)
- [ScheduledTickManager](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/safesave/scheduled/ScheduledTickManager.java)
- [PistonManager（pos-null 防御）](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/safesave/blockentity/PistonManager.java)
