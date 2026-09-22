# SafeSave 改动清单与设计意图

本文记录 **每一处改动以及做它的理由**，包括被否决的替代方案、开发过程中发现的 bug，以及哪些结论有实测支撑、哪些只是代码推论。

三份文档的分工：

| 文档 | 内容 |
|---|---|
| `README.md` | 功能说明 —— 做什么、怎么用、已验证行为 |
| **本文 `DESIGN.md`** | 改动清单 + 每个决定的**意图**与取舍 |
| `docs-tick-analysis/`（在 mcsource 工作区） | 上游代码分析，是所有改动的依据 |

起点是 `carpet-example` 模板；目标是让 vanilla 在重启时丢失的 tick 状态无损存续，并提供能**自证**这一点的调试手段。分析基于 MC **26.2** 源码（`minecraft-merged-5f54b0a1c5-26.2-sources`），实测在 26.2 dev server 上完成，26.1 一并编译通过。

---

## 1. 动因：上游到底丢了什么

每一条都在源码里核实过，行号为 26.2。

### 1.1 计划刻与方块事件

| # | 缺陷 | 证据 |
|---|---|---|
| L1 | **绝对触发时刻丢失**。`LevelChunk.unpackTicks(gameTime)` 把相对 `delay` 重锚到「该区块开始 block-ticking 的时刻」，漂移 `T_unpack − T_save` | `LevelChunk.java:637`、`SavedTick.java:49-51` |
| L2 | **`subTickOrder` 被按区块重编号为 `-N..-1`**，跨区块全局顺序被摧毁；并列后由 `Long2ObjectOpenHashMap` 迭代序决出 | `LevelChunkTicks.java:113-123`、`LevelTicks.java:32,101` |
| L3 | **`Level.subTickCount` 完全不持久化**，重启归零 | `Level.java:136,1093` |
| L5 | **`schedule` 不调用 `markUnsaved`**，只多了一条计划刻的区块不会被重写，该刻静默丢失 | `LevelChunkTicks.java:56-68` vs `ChunkMap.java:740` |
| — | **`ServerLevel.blockEvents` 完全不落盘**，在途方块事件全丢；且加载时无任何重新检查，活塞可永久卡在「有电但不动」 | 全仓仅 `ServerLevel.java` 6 处引用 |

### 1.2 移动中的活塞

| # | 缺陷 | 证据 |
|---|---|---|
| #2 | **存 `progressO` 而非 `progress`**。`tick()` 开头 `progressO = progress`，两者恒差 0.5 → 存档倒退半步，每个存读周期丢 1 tick；`moveStuckEntities` 无重叠判定地施加 `deltaProgress`，重复半步导致蜜块乘客**多拖 0.5** | `PistonMovingBlockEntity.java:356` / `:341-349` / `:201-214` |
| #3 | **跨区块推动在加载后不原子**。PME tick 逐区块门控，区块错峰上线 → 一次推动分批 finalize，而 finalize 会跑 `updateFromNeighbourShapes`/`neighborChanged` | `LevelChunk.java:425-433` |
| #4 | **BE tick 顺序改变**。存盘前是创建序，重载后是 `BlockPos` 哈希序 | 存：`ChunkAccess.java:161-164`（`Sets.newHashSet`）；载：`LevelChunk.java:604`（`HashMap`） |
| #5 | **`lastTicked` 不落盘**，`checkIfExtend` 少一个决定 `TRIGGER_DROP`/`TRIGGER_CONTRACT` 的分支 | `PistonMovingBlockEntity.java:47`、`PistonBaseBlock.java:115-121` |
| #6 | **MOVING_PISTON 丢 BE = 永久鬼影**，`newBlockEntity` 返回 `null` 无法自建 | `MovingPistonBlock.java:53-57` —— **未修**，见 §8 |

---

## 2. 改动总览

### 2.1 新增（2433 行：12 个业务类 1880 行 + 8 个 mixin 553 行）

| 文件 | 行 | 职责 |
|---|---|---|
| `debug/DebugSwitches.java` | 95 | `DEBUG` 编译期常量 + 三个运行期通道 |
| `debug/DebugLog.java` | 195 | 三个通道的格式化输出 + 一次性告警 |
| `debug/TickOwnerAware.java` | 21 | duck：给 `LevelTicks` 补上「属于哪个维度」 |
| `commands/DebugCommand.java` | 123 | `/safesave` 命令树 |
| `rules/SafeSaveRules.java` | 21 | carpet 规则 `safeSave` |
| `safesave/SafeTick.java` | 70 | 一条计划刻（绝对 `tt` + 全局 `so`） |
| `safesave/SafeBlockEvent.java` | 62 | 一条方块事件 |
| `safesave/SafeSaveStore.java` | 339 | 内存模型 + NBT 编解码（v3） |
| `safesave/SafeSaveManager.java` | 881 | 全部编排：读/存/恢复/门控 |
| `safesave/SafeTickContainer.java` | 41 | duck：改写单区块 tick 容器 |
| `safesave/TickContainerHolder.java` | 15 | duck：暴露 `allContainers` |
| `safesave/PistonOrderHolder.java` | 17 | duck：PME 创建序号 |
| 8 个 mixin | 553 | 见 §3 |

### 2.2 修改的模板文件

`ExampleCarpetServer.java`（接上 `onServerLoaded`/`onServerClosed`）、`safesave.mixins.json`、两份 lang、`build.gradle`、`gradle.properties`、`fabric.mod.json`。

### 2.3 改名 `carpet-example` → `SafeSave`

mod id `safesave`、产物 `SafeSave-<ver>-<modver>.jar`、mixin 配置 `safesave.mixins.json`、资源 `assets/safesave/`、logger `safesave`、命令 `/safesave`、数据文件 `safesave.dat`。

**意图**：`FILE_NAME` 改名会让已有世界的数据变孤儿，所以加了 `LEGACY_FILE_NAME` 回落 —— 读不到 `safesave.dat` 时读旧名，写永远写新名，下次保存自动迁移。已实测生效。

**未动 Java 包名与 `Example*` 类名**：`com.example.carpet` 被注解处理器硬编码（`CarpetProcessor` 生成 `com.example.carpet.generated`），改动会连带改处理器，风险与收益不成比例。

### 2.4 工具与工程

- `tools/setup-void-server.sh` —— 虚空世界测试台（§7）
- `tools/start-server.sh` —— 分离式启动 + FIFO 控制台
- `.gitattributes` —— 仓库继承了全局 `core.autocrlf=true`，在 Linux 上会把 `gradlew` 转成 CRLF 直接跑不起来；强制 `eol=lf`，`.bat` 保持 CRLF，jar/dat 标 binary

---

## 3. 每个注入点的选择理由

选点是这套改动里最需要解释的部分 —— 差一个位置，行为就完全不同。

| Mixin | 目标 | 注入点 | **为什么是这里** |
|---|---|---|---|
| `MinecraftServerSafeSaveMixin` | `prepareLevels` | HEAD | 全部 `ServerLevel` 已构造、但还没有区块被 unpack。是「levels 与 store 同时可用」的**最早**时刻 —— 恢复 `subTickCount` 必须早于任何新刻被分配，否则新旧 `subTickOrder` 会撞车 |
| 同上 | `tickServer` | HEAD | freeze 必须在任何东西前进之前 |
| 同上 | `saveAllChunks` | **HEAD 而非 RETURN** | `flush=true` 时 vanilla 会在存档过程中跑 `processUnloads`，那会 `unregisterTickContainerFromLevel`；到 RETURN 时一部分世界已从 `allContainers` 消失 |
| `ServerLevelSafeSaveMixin` | `tick` | HEAD | 「输出世界 tick」的落点；同时承载每刻维护（#4 重排、#3 就绪检查）与计划刻的新加载区块统一重建。**frozen 时 `ServerLevel.tick` 本身照跑**，但计划刻重建由 `runsNormally()` 门控，冻结期间不消费恢复队列 |
| 同上 | `blockEvent` | HEAD | 只有在插入**前**采样 `contains` 才能看出 `ObjectLinkedOpenHashSet` 的去重 |
| 同上 | `doBlockEvent` | RETURN | 需要 `CallbackInfoReturnable` 拿到 `handled` 结果 |
| 同上 | `unload` | HEAD | tick 容器仍注册在 level 上的**最后**时刻 |
| ~~`LevelChunkSafeSaveMixin`~~ | ~~`unpackTicks`~~ | **已删除（阶段 1）** | 原 HEAD+TAIL 方案被「每个非冻结 tick 开头统一重建新加载区块」取代。HEAD 原本抓本会话新排刻、TAIL 原本在 vanilla 解包后整体替换；现在重建发生在 tick 起点，`SS$snapshotQueue()` 作为 keep 列表直接拿到全部已解包刻，语义相同且与区块解包顺序解耦 |
| `LevelTicksMixin` | `schedule` | HEAD | 复现 vanilla 自己的接受/丢弃判定，才能区分真插入与被去重吞掉 |
| 同上 | `runCollectedTicks` | `INVOKE BiConsumer.accept` | vanilla 在这行**上一行**才 `alreadyRunThisTick.add(entry)`，所以读该 List 尾部就是即将执行的那条 —— 拿到完整 `ScheduledTick`（含 priority/subTickOrder）而**不需要脆弱的 local capture** |
| `PistonMovingBlockEntitySafeSaveMixin` | 6 参 `<init>` | TAIL | 用完整描述符精确锁定 `moveBlocks` 用的那个构造器；2 参构造器是反序列化路径，其序号从 NBT 来 |
| 同上 | `saveAdditional`/`loadAdditional` | TAIL | 在 vanilla 写完/读完之后追加、覆盖 |

### 3.1 用 `@Accessor` 还是 `@Shadow`

- `ServerLevel.blockEvents` 用 `@Accessor` —— 该字段在 26.1 是 `public final`、26.2 是 `private final`，`@Accessor` 对修饰符不敏感。
- `LevelChunkTicks` / `LevelTicks` 的字段用 `@Shadow` + **通配符泛型**（`Queue<ScheduledTick<?>>`）。JVM 字段描述符擦除泛型，所以通配符能匹配 `LevelChunkTicks<T>`，同时让 mixin 保持**非泛型**类 —— 避免泛型 mixin 的额外约束。

---

## 4. 数据格式 `<world>/safesave.dat`

gzip NBT。`FORMAT_VERSION = 3`，`MIN_READABLE_VERSION = 1`：v1 只有计划刻，v2 加方块事件，v3 加活塞区块。**版本超出可读区间时抛异常并回落到 vanilla 行为**，而不是猜着读 —— 时序数据读错比读不到更糟。

```
{
  version: 3,
  debug: { serverTickCount: int },          // 仅诊断
  levels: [{
    dimension: "minecraft:overworld",
    subTickCount: long,                     // 恢复 Level.subTickCount
    gameTime: long,                         // 仅诊断
    piston_chunks: long[],                  // v3，#3 的等待集
    block_events: [                         // v2，有序！
      { i:"minecraft:piston", x,y,z, a:int, b:int }
    ],
    chunks: [{
      x,z,
      block: [ { i, x,y,z, tt:long, p:int, so:long } ],
      fluid: [ ... ]
    }]
  }]
}
```

`tt` = **绝对** triggerTick（不是 delay），`so` = **原始全局** subTickOrder —— 这两个正是 vanilla 丢掉的。

**`debug.serverTickCount` 与每维度 `gameTime` 恒定只用于诊断**，恢复路径从不读取（这是你明确要求的约束）。唯一的用途是在它与实时 `gameTime` 不一致时发一条 stale 告警 —— 那说明侧存文件与 `level.dat` 脱节（通常是某次会话把规则关了），恢复的刻会集体过期立即触发。告警是诊断，不是重锚。

活塞的 #2/#4/#5 三个字段**不进侧存**，而是写在 PME 自己的 NBT 里：

```
progress: 0.0f              // vanilla 键，一字不改
safesave_progress: 0.5f
safesave_progress_o: 0.0f
safesave_last_ticked: 202L
safesave_order: 0L
```

---

## 5. 关键设计决策

### 5.1 计划刻走侧存，活塞字段走 BE 自己的 NBT

不是为了对称而分开，是两类数据的约束不同：

- **计划刻必须走侧存**。上游 L5 的根因是「排刻不 `markUnsaved`」，写进区块 NBT 就绕不开这个坑；侧存文件独立于区块 NBT，天然免疫。
- **活塞字段必须走 BE NBT**。它跟着 block entity 走，区块卸载/重载自动保持一致，不需要额外的快照/失效逻辑。放侧存反而要为它再造一套 unload 快照机制。

### 5.2 vanilla 的 `progress` 键一字不改

只**追加** `safesave_*` 键。意图：卸载本 mod 后世界退化成 vanilla 行为，而不是留下 vanilla 读不懂的数据。`safesave_progress` 缺失作为哨兵 = 「此 BE 早于本 mod 或规则关着」，此时**不覆盖** vanilla 已应用的值。

### 5.3 恢复队列与实时快照分离

`DimensionData` 里 `pendingRestore`（只从磁盘填充、不落盘）与 `chunks`（每次存档重写）是两个东西。

**意图**：MC 启动后紧跟一次 flush 存档，可能落在恢复**之前**。若两者共用一个结构，那次存档会把已恢复容器的内容写回 store，随后的兜底 sweep 又应用一遍。数据虽幂等，但计数和语义都脏。拆开后 `read=1 restored=1`。

**阶段 1 扩展**：卸载路径的 `snapshotChunk` 在成功 `put` 后也把该区块加入 `pendingRestore`（全量保存路径 `snapshotLevel` 不加），从而同会话重载也能被新加载统一重建消费。详细论证见 [同会话区块卸载-重载](docs/same-session-chunk-reload.md)。

### 5.4 重建是“替换 + 合并”，不是纯替换

阶段 1 前是 `unpackTicks` HEAD 抓、TAIL 补回；阶段 1 改为在非冻结 tick 起点对 `ready ∩ pendingRestore` 的区块调用 `SS$replaceAll(绝对刻)`，然后把 `SS$snapshotQueue()` 拿到的既有刻重新 `schedule` 回去。**意图**：区块停在 FULL 未 block-ticking 时仍可被排刻，纯替换会丢掉这些本会话新刻 —— 那是**比 vanilla 更差**。这套修复的底线是任何路径都不能不如 vanilla。

### 5.5 `onServerClosed` 不清 store

Carpet 的 `onServerClosed` 在 `stopServer` **HEAD** 触发，而停服存档在 `MinecraftServer.java:700`，**之后**。在这里清状态会静默跳过整个功能最重要的一次保存。改为只打日志；所有会话状态由 `onServerLoaded` 重新初始化，不会泄漏到下一个世界。

### 5.6 freeze 判定用「从磁盘实读到的条数」

不用 `store.isEmpty()`：启动那次存档会为每个维度建空 `DimensionData`，让空 store 看起来非空，导致**全新世界也被冻住**。改用 `loadedTickCount > 0 || loadedBlockEventCount > 0`。

### 5.7 #3 故意不加区块票据

强行加载玩家没请求的区块是**行为改变**；而模拟距离外的区块在 vanilla 里本来也不 tick，所以无限等是错的。改为 600 **服务器刻**超时并点名滞留区块。

用服务器刻而非 `gameTime`：freeze 期间 `gameTime` 不前进，拿它做超时会永远不触发。

### 5.8 #4 只重写活塞占用的槽位

不是整体排序 `blockEntityTickers`。收集移动活塞占用的下标，按创建序填回**同一批下标**，其余 ticker 索引一个不动。意图：修活塞之间的相对顺序，而不扰动任何别的 BE。

筛选用 `getBlockState(pos).is(Blocks.MOVING_PISTON)` 而非 `getBlockEntity`：后者走 `EntityCreationType.IMMEDIATE`，会把全 level 的 pending BE 提前实例化 —— 比 vanilla 更早创建 BE 是可观测的行为改变。这个选择顺带绕开了 §9 那个版本差异。

按维度用**生成计数器**而非布尔脏标记：`loadAdditional` 运行时 BE 还没有 level，此刻不知道维度，所以让每个 level 记住自己上次重排的 generation。

### 5.9 `DEBUG` 是编译期常量

`public static final boolean DEBUG = true`。翻成 `false` 后 javac 把所有 `if (DebugSwitches.DEBUG && ...)` 判定为静态假并**剥掉整段代码**，release 构建零开销。三个通道默认 `false`，仅装 debug 构建不会刷日志。

### 5.10 存档失败宁降级不崩

`snapshotQueue` 遇到异常状态（`tickQueue` 为 null）改为**一次性诊断 + 跳过该区块**。让一次自动存档崩掉，比少存一个区块的计划刻糟糕得多。

---

## 6. 开发过程中发现并修掉的 bug

**上游的**（已在 §1）。**我自己引入的**：

| 问题 | 后果 | 修法 |
|---|---|---|
| `onServerClosed` 清 store | 停服存档被静默跳过 —— 最重要的一次保存 | §5.5 |
| 启动 flush 存档覆盖未恢复条目 | 恢复被 vanilla 重锚数据顶掉 | §5.3 的守卫 |
| 全新世界误 freeze | 空 `DimensionData` 让 `isEmpty()` 失效 | §5.6 |
| 恢复丢弃本会话新排刻 | 比 vanilla 更差 | §5.4（阶段 1 改为 tick 起点合并） |
| 同区块恢复两次 | `read=1 restored=2`，语义脏 | §5.3 |
| `snapshotChunk` 强转无 `instanceof` 保护 | `ImposterProtoChunk` 返回 `BlackholeTickAccess.emptyContainer()` → CCE | 补 guard（`snapshotLevel` 本来有，这条路径漏了） |
| `pistonTickOrderDirty` 全局布尔但重排按 level | 下界的活塞被主世界的 tick 清了标记，永不重排 | 改生成计数器（§5.8） |
| 工程：`core.autocrlf=true` | `gradlew` 转 CRLF，Linux 上跑不起来 | `.gitattributes` |

---

## 7. 验证

### 7.1 虚空世界测试台

`tools/setup-void-server.sh`：`layers:[]` + `minecraft:the_void`。

**意图**：普通世界的区块生成会排入几百条环境水/岩浆流体刻（日志里满是 `flowing_lava (31,-9,-1)`），一次存档 845 条里绝大多数是噪音。虚空世界什么都不生成，捕获到的每一条都是测试自己造的；启动也快 3 倍（1.2s vs 3.6s）。

两个坑写进了脚本注释：虚空世界**不保持 chunk (0,0) 加载**（出生点选址不同，`/setblock` 会报 `That position is not loaded`，必须先 `forceload`）；只有主世界生成器被替换，下界末地照常生成。

### 7.2 造「未执行的方块事件」

方块事件正常在同一 tick 内被排空，存档时永远看不到。必须先 `tick freeze` —— `/setblock` 引发的邻居更新**不受 freeze 影响**（`neighborChanged` 是同步的），但 `runBlockEvents` 被 `runsNormally()` 挡住。

### 7.3 实测结果

**计划刻 + 方块事件**（虚空世界，两阶段）：恢复的刻逐条以原值执行 —— `observer (0,100,0) trigger=243 sub=71 late=0`，与存盘 dump 完全一致；跨区块全局顺序保住（lava `so=3702..3704` 先于 water `so=3788..`）；新刻从恢复的 `subTickCount` 接着走无碰撞；**活塞从一条跨越重启的方块事件里伸出来了**（`[BE][RUN] ... handled=true ... extended=true`）。

**#2 的 A/B**（黏性活塞 + 蜜块 + 骑在上面的盔甲架，推动进行到一半时存档重启）：

| `safeSave` | 蜜块位移 | 乘客位移 | 乘客最终 x |
|---|---|---|---|
| `false`（vanilla） | 1.0 | **1.5** ❌ | 3.0 |
| `true`（修复后） | 1.0 | **1.0** ✅ | 2.5 |

同一份日志确认 #4 与 #3：`rebuilt tick order of 2 moving piston(s) by creation sequence`、`all 1 chunk(s) holding a moving piston are now block-ticking`。

### 7.4 未验证的部分

诚实标注：

- **#5（`lastTicked`）没有专门实测**。它只在 `isHandlingTick() == false`（玩家触发的更新）时才暴露，构造这个场景需要模拟玩家交互，尚未做。
- **#3 的撕裂场景没有实测**。只验证了「等齐并报告」这条路径生效，没有真的造出一个跨区块推动被撕成两半的对照。
- **#4 的顺序差异没有量化**。只验证重排被触发，没有构造两个相邻活塞同刻 finalize 的对照。
- 26.1 **只编译通过，没有实机运行**。

---

## 8. 已知限制

| 限制 | 说明 |
|---|---|
| **崩溃 / `kill -9`** | 自动存档异步写盘，且侧存写在 `saveAllChunks` HEAD；非正常退出会丢掉上次存档之后的一切。正常 `/stop` 是安全的（§5.5 刻意不短路） |
| **规则必须持久化** | 规则在 `loadLevel` HEAD 读取，`/carpet safeSave true` 若不点 `[Change permanently?]` 则只对本会话生效，下次启动读到 `false` 什么都不恢复。需写进 `<world>/carpet.conf` |
| **#6 鬼影未修** | `MovingPistonBlock.newBlockEntity` 返回 `null`，方块无法自建 BE。正常路径下两者同份 chunk NBT 一致落盘，只在损坏或注册表变动时触发 |
| **从未 block-ticking 的区块** | 其 `pendingTicks` 没有绝对时序可存，不快照；保留 store 里已有条目（正确 —— 绝对时刻不漂移） |
| **注册表条目消失** | 对应刻/事件被丢弃并告警。`BLOCK`/`FLUID` 是 `DefaultedRegistry`，所以显式 `containsKey` 校验，而不是让 `getValue()` 静默返回 AIR/EMPTY |
| **一个未定位的 NPE** | 有一份来自 IntegratedServer 的报告，`LevelChunkTicks.tickQueue` 为 null。已确认 shadow 绑定本身正确（本地 `replaceAll` 成功 clear 过它，之后存档正常），而该字段是带初始化器的 `private final`，正常构造不可能为 null。已加一次性诊断打印实例具体类名；追根因需要那次崩溃的完整日志与 mod 列表（其 `IntegratedServer.java:120` 行号与 26.2 对不上，疑为 26.1 或混入其他 mod） |

---

## 9. 兼容性

**26.1 / 26.2 API 对等性**逐个核实过：`LevelTicks`、`LevelChunkTicks`、`ScheduledTick`、`SavedTick`、`TickPriority`、`ChunkPos`、`NbtIo`、`Identifier`、`ServerLevel` 成员、`LevelChunk.unpackTicks`/`getLevel`、`Registry.containsKey`、`ValueInput`/`ValueOutput` 全部一致，**无需 stonecutter 条件编译**。

实际撞到的**唯一**差异：`BlockEntityTypes`（26.2）在 26.1 叫 `BlockEntityType`。已通过改用 `Blocks.MOVING_PISTON` 判定绕开（§5.8），顺带避免了 BE 提前实例化。

**降级行为**：规则关闭 → 完全 vanilla 行为，连 PME 的 `safesave_*` 键都不写。卸载 mod → 世界退化成 vanilla 行为（§5.2）。旧格式文件 → v1/v2 可读。旧文件名 → 自动迁移（§2.3）。

---

## 10. 建成后的构建与运行

```
JAVA_HOME=<jdk25> ./gradlew build          # SafeSave-26.1 / SafeSave-26.2
tools/setup-void-server.sh [run-dir] [port]
tools/start-server.sh                      # 分离式，FIFO 控制台
```

需要 JDK 25 工具链（`sourceCompatibility = 25`）。

⚠️ **`runServer` 运行期间不要跑 `./gradlew build`** —— Gradle 不允许同一项目并发两个 build，会终止 runServer（关得干净，但确实会停）。这条已写进 `tools/start-server.sh` 注释。
