# SafeSave

通过额外保存更多的数据，并且在加载时恢复，以达到避免部分因为关服/回档而导致机器损坏的情况

会对一下数据做额外保存

- **计划刻无损重启**：额外保存随机刻时间 `triggerTick` 与 随机刻执行顺序`subTickOrder` ，防止原版用 `delay` 重新锚定、按区块重编号。
- **方块事件持久化**：持久化保存方块事件
- **移动活塞修复**：修复活塞半程重载（progress）、`lastTicked`、方块实体 tick 顺序、跨区块推拉恢复等问题
- **实体tick顺序重建**: 额外保存实体的tick顺序，用于重建。
- **ProtectedRegion 启动屏障**：启动时全局冻结，等待上次保存时完整加载的区域再次加载后自动解冻


## 规则

| 规则 | 默认 | 说明                                                           |
|---|---|--------------------------------------------------------------|
| `safeSave` | `false` | 计划刻 / 方块事件持久化总开关                                             |
| `safeSaveRegions` | `false` | ProtectedRegion 启动屏障总开关                                      |
| `safeSaveUnfreeze` | `manual` | 启动冻结策略：`no_freeze` 不冻结 / `manual` 冻结后手动解冻 / `region` 等区域自动解冻 |
| `safeSaveRegionTimeout` | `600` | `region` 模式最大等待（服务器刻，**自首个真人玩家进服起算**）                        |

## 命令

`/safesave region ...` 管理ProtectedRegion

| 命令 | 说明 |
|---|---|
| `region add <name> <from> <to>` | 定义矩形区域：两个角点区块坐标，名称唯一；空区域会被拒绝 |
| `region remove <name>` | 删除区域 |
| `region addChunk <name> <pos>` | 向区域追加一个区块 |
| `region removeChunk <name> <pos>` | 从区域移除一个区块 |
| `region list` | 列出全部区域及区块数（上次保存时完整加载的区域带 `[startup target]` 标记） |
| `region info <name>` | 显示区域详情：区块数、当前是否完整加载（`fullyLoadedNow`）、启动目标标记（`startupTarget`） |


## 数据存储

- **区块 NBT 的 `safeSave` 子节点**：每区块的计划刻（绝对触发时刻 + 全局序号）与方块事件
- **每维度旁置文件 `<维度>/data/safesave.dat`**：`Level.subTickCount`、ProtectedRegion 定义与"上次保存时完整加载"标记



