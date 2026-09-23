# SafeSave 26.1 设计

## 保存时点

`MinecraftServer.tickChildren` 返回后，所有维度与玩家处理已完成，而自动保存尚未开始。此时为已就绪区块生成本服务器刻的计划刻和方块事件快照，并从 `SimulationChunkTracker` 复制等级不大于 32 的区块清单；不大于 31 的等级归为实体刻等级 31。普通区块保存若发现当前刻没有快照，只能在世界 tick 完成后采集。`ChunkMap` 在 `ServerLevel.tick` 内执行的卸载和全量保存，以及刻内调用的 `MinecraftServer.saveEverything` / `saveAllChunks`，都延后至上述采集点。

快照只有一个建立入口，保存在 `LevelChunk` 的内存字段中。区块 NBT 可由普通保存、自动保存、flush 或卸载保存写出同一份快照。尚待重建的区块则保留从 NBT 读出的快照，直到恢复完成。世界级旁置文件只保存 `subTickCount`、等级 31/32 清单及诊断时间，不存计划刻或方块事件。

## 启动恢复

在首个服务器刻之前读取旁置文件。若有目标区块，就在首刻开头冻结游戏刻，并给每个目标区块添加对应等级的仅加载票。等待目标 `LevelChunk`、方块与流体计划刻容器就绪；等级 31 还需实体就绪。全数就绪后自动解冻，正常的区块重建发生在第一个非冻结世界刻开头。没有对单条计划刻或方块事件设置门控。

即使加载未完成，在首位真人玩家加入 `safeSaveForceUnfreezeTimeout` 个服务器刻后也强制解冻。恢复票在解冻之后才可撤销；保留时长由 `safeSaveTicketDuration` 设置，起点按 `safeSaveTicketTimerFromFirstPlayer` 选择。假玩家不会启动真人玩家计时。

## 关闭和异常

原版 `MinecraftServer.stopServer` 在最终 `saveAllChunks` 之前卸载全部区块。Carpet 的 `onServerClosed` 回调位于该过程开头，因此在此写出缓存的最后一个完整刻清单。卸载后最终保存复用这份清单，不能用已经清空的区块表覆盖。若本刻因异常未完成，使用上一完整刻的清单；对缺乏本刻有效快照的活动区块，区块 NBT 写入回退到原版现场序列化。

旁置文件格式为 v6；读取 v5 时忽略旧版 region 定义，保留可用的 `subTickCount`。项目不再注册 region 命令、region 票或 region 规则。
