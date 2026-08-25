# SafeSave 项目文档

## 文档列表

| 文档 | 内容 | 状态 |
|---|---|---|
| [同会话区块卸载-重载的计划刻恢复](same-session-chunk-reload.md) | 不重启的卸载-重载场景下计划刻无法恢复的问题、根因、阶段 1 修复 | 已实施，待实机验证 |
| [锂/C2ME 与 SafeSave 的兼容性分析](lithium-c2me-conflict-analysis.md) | LevelChunkTicks 冲突机制、三方分析、已实施的公共 API 修复、锂残留索引问题、已排除项、残余风险 | 已实施 |
| [实体 tick 序号功能接入记录](entity-order-integration.md) | 实体序号持久化 + unfreeze 全量重建 + 区块内维护的设计与接入点 | 已实施 |
| [原版区块加载机制分析](vanilla-chunk-loading.md) | tick 中区块加载的控制阶段、卸载异步保存模型、冻结期间行为、C2ME 差异、对 safesave 的意义 | 分析文档 |

## 项目速览

SafeSave 是 Carpet 模组（MC 26.1/26.2，stonecutter 多版本），目标：**跨重启无损保存计划刻、方块事件，并恢复实体/活塞 tick 顺序**。

三个机制的重建时机：

| 机制 | 重建时机 | 粒度 | 与 unfreeze 关系 |
|---|---|---|---|
| 计划刻（阶段 1） | 每个非冻结 tick 开头，统一重建新加载区块 | 区块级，消费式 | 依赖 unfreeze：冻结期间加载的区块在解冻后第一个正常 tick 重建 |
| 方块事件 | prepareLevels（世界刚构建） | 世界级一次性 | 无关，启动即恢复 |
| 实体 | unfreeze 后第一 tick 全量 + 之后区块内维护 | 先全量后区块级 | 依赖 unfreeze |
| 活塞 | 每 tick 检查代数，加载过活塞就全量重建 | 世界级（可能多次） | 无关，冻结期间也重建 |
