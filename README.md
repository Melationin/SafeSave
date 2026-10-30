# SafeSave

[English](#english) | 中文

SafeSave 是一个用于重载世界时更好恢复计划刻、方块事件和实体的状态和顺序。已达到更安全的保存和备份的fabric mod

同时，为了解决载入世界时不同区块的加载不一致的问题，模组会在重载世界后给保存时加载的区块添加加载票，并冻结世界直到所有区块被加载

模组只能大幅度减少因为重启导致的计划刻错乱和方块事件丢失等，对于普通的卸载区块可能没有非常好的效果，

模组提供以下设置项，可以在游戏中或者`safesave.conf`中进行修改

| 设置项                          |     默认值 | 用途                                |
|------------------------------|--------:|-----------------------------------|
| `safeSave`                   |  `true` | 启用保存与启动恢复                         |
| `rebuildStartupOnly`         |  `true` | 仅接管首次自动或手动解冻前新加载的区块。关闭后持续接管所有新加载区块|
| `extraChunkDirtyMarking`     | `false` | 为含计划刻或方块事件的区块额外标脏                 |
| `expandDirtyChunksOnSave`    |  `true` | 世界保存时，将当前脏区块周围一圈正在实体刻的区块也标脏       |
| `autoUnfreeze`               |  `true` | 启动加载等待结束后自动解冻；关闭后使用 `/tick unfreeze` 手动恢复 |
| `autoUnfreezeWaitForPlayer`  |  `true` | 自动解冻是否等待真人玩家加入                    |
| `ticketDuration`             |  `1200` | 启动加载票时长                           |
| `unfreezeTimeout`            |  `2400` | 解冻超时时长                            |

> [!IMPORTANT]
> 注意！模组会添加新的区块nbt字段，可能会影响一些原版向的优化mod。
> 目前只测试了与锂和c2me(默认配置)的兼容性.很可能与多线程mod不兼容！
>
> 模组还未进行充分测试，在第一次安装前务必备份存档！

---

## English

[中文](#safesave) | English

SafeSave is a Fabric mod that restores the state and ordering of scheduled ticks, block events, and entities more faithfully when a world is reloaded, making saves and backups safer.

It also addresses inconsistent chunk loading when a world loads: after a world reload, the mod adds loading tickets for the chunks that were loaded at save time, and freezes the world until all of those chunks have loaded.

The mod can greatly reduce scheduled tick disorder, block event loss, and similar issues caused by restarts, but may be less effective for ordinary chunk unloads.

The mod provides the following settings, which can be changed in-game or in `safesave.conf`:

| Setting | Default | Purpose |
|---|---|---|
| `safeSave` | `true` | Enable saving and startup restoration |
| `rebuildStartupOnly` | `true` | Take over newly loaded chunks before the first automatic or manual unfreeze, plus newly loaded chunks in the dimension's chunksToRestore set; when disabled, take over all newly loaded chunks |
| `extraChunkDirtyMarking` | `false` | Additionally mark chunks containing scheduled ticks or block events as dirty |
| `expandDirtyChunksOnSave` | `true` | On world save, also mark the ring of surrounding chunks that are entity-ticking as dirty |
| `autoUnfreeze` | `true` | Unfreeze automatically once the startup loading wait ends; when disabled, use `/tick unfreeze` to resume manually |
| `autoUnfreezeWaitForPlayer` | `true` | Whether automatic unfreezing waits for a real player to join |
| `ticketDuration` | `1200` | Startup loading ticket duration |
| `unfreezeTimeout` | `2400` | Unfreeze timeout |

> [!IMPORTANT]
> Note! The mod adds new chunk NBT fields, which may affect vanilla-oriented optimization mods.
> So far, compatibility has only been tested with Lithium and C2ME (default configuration). It is very likely incompatible with multithreading mods!
>
> The mod has not been thoroughly tested yet. Be sure to back up your world before installing it for the first time!


Run `python tests/server/run_matrix.py --accept-eula` to execute the dedicated-server save/restart
regression matrix for 1.21.4, 1.21.5, and 26.3. It covers all three dimensions with no optimization
mods, Lithium, C2ME, and both installed. Tests are separate from the release JAR;
see the [test documentation](tests/server/README.md).
