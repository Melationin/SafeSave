# 锂（Lithium）/ C2ME 与 SafeSave 的兼容性分析

> 状态：**已实施**（锂部分 2026-08-25 完成；2026-09 针对 **v5 架构**重新评估，补充了「锂 sleeping 的 pos-null」崩溃修复与「C2ME 兼容性」章节）
>
> 结论：
> - **锂**：真冲突有**两处** —— ① `world.tick_scheduler`（默认开启）把 `LevelChunkTicks` 的 `tickQueue`/`ticksPerPosition` 置 null；② `world.block_entity_ticking.sleeping`（默认开启）的哨兵 ticker `getPos()` 恒 null **且 `isRemoved()` 恒 false**，曾导致 `PistonManager` 崩溃（已修复）。
> - **C2ME**：默认配置下与 v5 兼容。唯一已知风险是实验性开关 `ioSystem.gcFreeChunkSerializer`（**默认关闭**）—— 一旦打开，区块级 safeSave 数据会**静默**不落盘。详见下方「C2ME 兼容性」章节。

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

### 锂 sleeping 的 pos-null（曾导致崩溃，已修复）

`world.block_entity_ticking.sleeping` 的 `package-info.java` **未显式写 `enabled`**，取 `MixinConfigOption.enabled() default true` → **默认开启**。方块实体入睡时，锂把它的 tick 包装器 delegate 换成哨兵：

```java
// ref/lithium/common/.../common/block/entity/SleepingBlockEntity.java
TickingBlockEntity SLEEPING_BLOCK_ENTITY_TICKER = new TickingBlockEntity() {
    public void tick() { }
    public boolean isRemoved() { return false; }        // 恒 false
    public BlockPos getPos() { return null; }           // 恒 null
    public String getType() { return "<lithium_sleeping>"; }
};
```

三个事实叠加，第三方遍历必炸：

1. ticker **仍留在** `Level.blockEntityTickers` 里（列表本身不动）
2. `getPos()` 恒 `null` → `ChunkPos.pack(null)` 抛 NPE
3. **`isRemoved()` 恒 `false`** → 靠 `isRemoved()` 过滤的守卫**拦不住它**

**症状**：设置受保护区域后必现

```
java.lang.NullPointerException: Cannot invoke "net.minecraft.core.BlockPos.getX()" because "pos" is null
	at net.minecraft.world.level.ChunkPos.pack(ChunkPos.java:92)
	at com.carpet.safesave.safesave.blockentity.PistonManager.onLevelTickStart(PistonManager.java:69)
```

**为什么是区域功能引爆**：`PistonManager.onLevelTickStart` 的全量扫描只在 `session.pistonOrderGeneration` 推进时执行，而推进它的调用者基本只有 `RegionLifecycle.beforeTick` 的复活路径与清理路径（`PistonMovingBlockEntityMixin.loadAdditional` 太罕见）。无区域时这段代码几乎不跑，NPE 长期潜伏；一有区域启停就变成每轮扫描 → 撞上第一个睡着的方块实体（箱子/熔炉/漏斗/潜影盒）即崩。

**修复**：`onLevelTickStart` 的循环把 `getPos()` 提成局部变量并判空 —— `rebuildPistonTickOrder` 里本来就有这道判空，前者漏了：

```java
if (ticker.isRemoved()) continue;
BlockPos pos = ticker.getPos();
if (pos == null) continue;                                     // 锂 sleeping 哨兵
if (RegionLifecycle.isSuspended(level, ChunkPos.pack(pos))) continue;
if (!level.getBlockState(pos).is(Blocks.MOVING_PISTON)) continue;
```

**教训**：锂为**自己**的访问点都加了判空（`sleeping/LevelMixin` 给 `tickBlockEntities` 里的 `shouldTickBlocksAt(BlockPos)` 套 `@WrapOperation`、`chunk_tickable/LevelMixin.optimizedShouldTick`、`sleeping/ServerLevelMixin` 对 `dumpBlockEntityTickers` 的 `@Redirect`），但对第三方独立遍历不负责。**任何遍历 `blockEntityTickers` 的新代码都必须自己判 `pos == null`，且不能依赖 `isRemoved()` 判有效性。**

## 锂的 allTicks 残留索引问题

**症状**：锂环境下恢复的计划刻被去重拦截（数量偏少）。

**机制**：锂的 `removeIf` 只清"已入桶"刻的 `allTicks` 索引；构造器 `reinit` 会把**未解包 `pendingTicks`** 的 (type,pos) 索引提前放入 `allTicks`——这些刻还没进桶，removeIf 清不到 → 残留索引拦截之后相同 (type,pos) 的恢复刻。

**修复**：恢复前检查 `SS$hasPendingTicks()`，未解包的容器不计入 `ready`，不在本 tick 重建；等它解包（pendingTicks 已清空、刻已进桶）后的某个正常 tick 进入 `ready`，removeIf 清得完整。曾用反射 `allTicks.clear()` 验证过假设（试验 mixin，已删除）。

## 已排除无冲突的部分

- **实体 `EntityTickList`**：锂 @Overwrite `ensureActiveIsNotIterated`（clone 保序）vs SafeSave @Inject `add` HEAD + `active` 重建（`Int2ObjectLinkedOpenHashMap`）——方法不重叠；clone 保留插入顺序；类型假设兼容
- **活塞 `PistonMovingBlockEntity`**：锂只注入 `getCollisionShape`（形状缓存）；SafeSave 注入 `<init>`/`saveAdditional`/`loadAdditional`。`alloc.enum_values.piston_*` 只是 `Direction.values()` → 常量数组
- **方块事件 `ServerLevel.blockEvents`**：锂完全不碰（`blockEvent` 命中仅 3 个 sleeping 相关 mixin，是方块实体自身的事件同步）
- **`ServerLevel.tick` / `MinecraftServer` / `LevelChunk.unpackTicks` / `LevelTicks` / `Entity.saveWithoutId`·`load` / `TagValueOutput`**：锂均无注入
- **`Level.blockEntityTickers` 列表结构**：锂的 sleeping/chunk_tickable/collections mixin 不改列表本身及索引语义（但**列表元素的 `pos` 可能为 null**，见上方「锂 sleeping 的 pos-null」）

## C2ME 兼容性（v5 架构下的重新评估）

> 本文此前的 C2ME 结论是在 **v3 架构**下得出的 —— 当时计划刻存于旁置文件、由 SafeSave 自己在 `saveAllChunks` HEAD 写出，对"任何 mod 替换区块序列化"天然免疫。**v5 把计划刻/方块事件搬进区块 NBT**，落盘通路变成原版 `SerializableChunkData`，因此必须重新评估。

**参考源码**：`ref/C2ME-fabric`，`minecraft_version=26.2`、`mod_version=0.4.2-alpha.0`，源码为 **Yarn 映射** —— `SerializedChunk` = 官方 `SerializableChunkData`、`ServerChunkLoadingManager` = `ChunkMap`、`ServerChunkManager` = `ServerChunkCache`、`ChunkTickScheduler` = `LevelChunkTicks`、`WorldChunk` = `LevelChunk`。
**实测环境**：`run/mods/c2me-fabric-mc26.1.2-0.4.0-alpha.0.54.jar`（与 ref 源码存在 26.1/26.2 版本与映射双重差异，行级对应需重新对齐）。

### 已排除（默认配置下兼容）

| C2ME mixin | 目标 | 核实结果 |
|---|---|---|
| `MixinThreadedAnvilChunkStorage`（chunk-system） | `ChunkMap` | `@Overwrite` 仅 `setLevel` / `getCurrentChunkHolder` / `getChunkHolder` —— **没有动 `save`**。SafeSave 对 `ChunkMap.save(ChunkAccess)Z` 的 `@WrapOperation(require = 2, allow = 2)` 所依赖的两个调用点（`ChunkMap.java:528` 卸载路径、`:727` saveChunkIfNeeded）仍然存在，不会因 `require` 失败而崩 |
| 同上 `@ModifyArg(method = "save(...)Z", target = "CompletableFuture;supplyAsync(...)")` | 替换 `data::write` 的 **Executor** | 标了 `require = 0`。SafeSave 的跨线程交接靠 `CompletableFuture.supplyAsync` 的 happens-before，**与用哪个 Executor 无关**；且 `write` 钩子只读预计算好的 `CompoundTag`、不访问世界 → 换线程安全 |
| `MixinServerChunkManager` | `ServerChunkCache` | `@Overwrite` 仅 `getDebugString`（无害）；`getChunk(...)` HEAD 注入的 `shortcutGetChunk` **只在 `Thread.currentThread() != serverThread` 时**早返回 → 主线程仍走原版 `getChunkFutureMainThread`，不受影响 |
| `MixinWorldChunk`（`c2me-notickvd`） | `runPostProcessing` 清除 `NO_REDRAW` | 只影响方块更新标志，与本 mod 无交集 |
| `ChunkDataSerializer` 旁的 `utils/LithiumUtil.java` | 反射探测锂注入的 `tickQueuesByTimeAndPriority` | 证实"锂 + C2ME 的数据竞争由 C2ME 自己适配"，与 SafeSave 无关 |

### 唯一已知风险：`ioSystem.gcFreeChunkSerializer`

**默认关闭。** 证据：

```java
// c2me-rewrites-chunk-serializer/.../ModuleEntryPoint.java
public static final boolean enabled = new ConfigSystem.ConfigAccessor()
        .key("ioSystem.gcFreeChunkSerializer")
        .comment("EXPERIMENTAL FEATURE ... (may cause incompatibility with other mods)")
        .incompatibleMod("architectury", "*")
        .getBoolean(false, false);          // 第 1 参 = 默认值，第 2 参 = 检测到不兼容 mod 时强制取的值
```

```java
// c2me-base/.../ConfigSystem.java:188 与 :213
public boolean getBoolean(boolean def, boolean incompatibleDef) {
    ...
    final boolean b = this.incompatibilityDetected ? incompatibleDef : (isDefaultValue ? def : CONFIG.get(this.key));
```

两条路径都指向 `false`，所以**只有用户显式开启才生效**（`findModDefinedIncompatibility()` 的机制只能关、不能开）。

**默认关闭时为什么安全**：

- `SerializerAccess.registerSerializer(...)` 全仓**只有一个调用点**（`c2me-rewrites-chunk-serializer/.../TheMod.java:38`），且被 `if (ModuleEntryPoint.enabled)` 包裹 → 开关关闭时不会注册任何自定义序列化器
- `SerializerAccess.getSerializer()` 在未注册时回退到 `VANILLA = serializable -> Either.left(serializable.serialize())`，即原版 `SerializableChunkData.write()` → **SafeSave 的 `@ModifyReturnValue` 正常触发** ✓
- 卸载路径也走这个回退：`ReadFromDisk.java:267` `return SerializerAccess.getSerializer().serialize(serializer);` ✓

**一旦打开会怎样**：C2ME 用 `ChunkDataSerializer.write(SerializedChunk, NbtWriter)` **整体替换区块写盘**。它是手写 NBT 字节的从零重实现 —— 键名硬编码为 ASCII 字节数组（`NbtWriter.getAsciiStringBytes("block_ticks")` 等），直接读 record 分量，**全程不调用 `serializable.write()`**：

```java
// c2me-rewrites-chunk-serializer/.../common/ChunkDataSerializer.java:159
public static void write(SerializedChunk serializable, NbtWriter writer) {
    writer.putInt(STRING_Y_POS, serializable.minSectionY());
    ...
    serializeTicks(writer, serializable.packedTicks());
    ...
}
```

它的键表里有 `block_ticks` / `fluid_ticks` / `structures` / `block_entities` …，**没有 `safeSave`**。

> 后果：SafeSave v5 的 `@ModifyReturnValue(method = "write")` **永不执行** → 区块级计划刻 / 方块事件 **完全不落盘**。

**为什么这个故障极难发现**：

1. **无异常、无日志、无 mixin 报错** —— `SerializableChunkData.write` 方法本身仍然存在（C2ME 只是不再调用它），所以注入成功、应用成功、**静默失效**；
2. **旁置文件照常写入** —— `SafeSaveFiles.saveAll` 挂在 `MinecraftServer.saveAllChunks` HEAD，与 C2ME 无关，`subTickCount` 与 `regions` 正常落盘，`/safesave region list` 正常返回；
3. 于是 mod 表现完全正常、日志完全正常，只有**计划刻在悄悄丢失**，回退到原版重锚定行为（而这正是本 mod 要消灭的现象）。

**绕过默认值的两条途径**：`config/c2me.json` 手动改 `true`；或系统属性 `-Dc2me.base.config.override.ioSystem.gcFreeChunkSerializer=true`（前缀见 `ConfigSystem.java` 的 `propertyPrefix = "c2me.base.config.override."`，比改配置文件更隐蔽）。

**与 v3 的对比**：v3 把计划刻放侧存、由 SafeSave 自己写，对"任何 mod 替换区块序列化"免疫；v5 之后才引入这条依赖 —— 这是 v5 用区块 NBT 承载计划刻的第二个代价（第一个是 L5 重新暴露，见 `DESIGN.md` §5.2）。

**建议（尚未实施）**：在 `SafeSaveManager.onChunkSerializing` / `SafeSaveFiles.saveAll` 加一条自检 —— 首次存档后抽样确认至少一个区块真的写出了 `safeSave` 标签，否则打 `warn`（"chunk-level safe-save data is not being written; another mod may have replaced the chunk serializer"）。这能把"静默失效"变成"可诊断"。

### 未验证项

- C2ME 的**读取**通路是否仍走原版 `SerializableChunkData.parse`（决定 `onChunkTagRead` 是否也被绕过）
- `ref/entity_collision_optimizer-26.2.0-alpha.jar` 未展开分析

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
