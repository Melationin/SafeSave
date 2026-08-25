# 实体 tick 序号功能（接入记录）

> 状态：**已实施**（编译通过 26.1 + 26.2；单机行为验证见文末）
>
> 背景：用户手动回退了"统一恢复架构"的全部改动，只保留实体序号相关新文件，要求**只接入实体序号功能**（不碰计划刻/方块事件/活塞机制），重建时机为 **unfreeze 后的第一个 tick 前**。

## 设计

原版 `EntityTickList` 的 tick 序 = 实体进入列表的顺序，由区块晋级时机和异步反序列化完成顺序决定，**重启后必然变化**。方案：给每个实体持久化单调序号（随实体 NBT 保存），并在恢复流程中按序号重建顺序。

- **全量重建**：unfreeze 后第一个非冻结 tick 前（`EntityOrderManager` 自检 `tickRateManager().runsNormally()` 跃迁，`sawFrozen` per-dimension）
- **区块内维护**：之后每刻对新加载的区块做区块内重排（提取该区块实体 → 按序号排序 → 重插列表尾部），每区块至多一次
- **序号分配**：`EntityTickList.add` HEAD——新生成实体分配序号；从 NBT 加载的实体记录区块等待维护
- **序号持久化**：实体 NBT 的 `safeSave` 子标签 `entity_order`（`Long.MIN_VALUE` 表示未知）

## 接入点（3 个文件）

### 1. `mixin/entity/EntityMixin.java`
- `implements EntityOrderHolder` + `@Unique SS$entityOrder` 字段 + getter/assign
- `saveWithoutId` TAIL：写 `safeSave.entity_order`
- `load` TAIL：读回；非 `MIN_VALUE` → 赋值 + `EntityOrderManager.observeOrder(order)`

### 2. `mixin/ServerLevelMixin.java`
- `implements ServerLevelTickListAccess` + `@Accessor("entityTickList")` 暴露 private 字段

### 3. `safesave/SafeSaveManager.java`
- `onLevelTickStart`：追加 `EntityOrderManager.onLevelTickStart(level)`
- `onServerLoaded`：追加 `EntityOrderManager.reset()`

### 保留未动（回退时留下的新文件）
- `safesave/entity/`：`EntityOrderHolder`、`EntityOrderManager`、`EntityTickListAccess`、`ServerLevelTickListAccess`
- `mixin/entity/EntityTickListMixin.java`

## 行为流程

```
重启 → 冻结（有恢复数据时）
冻结期间：实体进列表只分配序号/记录区块，不重建
/tick unfreeze 后第一 tick HEAD → 全量重建（日志：rebuilt tick order of N entity(ies)）
之后：新加载区块 → 区块内维护（每区块至多一次）
```

## 验证

1. **编译**：`./gradlew compileJava --offline --rerun-tasks`（26.1/26.2）
2. **单机**：重启有实体的存档 → 冻结 → unfreeze 后日志 `rebuilt tick order of N entity(ies)`；实体 NBT 出现 `safeSave.entity_order`；同刻多实体场景（如 TNT 连锁）重启前后行为一致

## 相关源码（GitHub: Melationin/SafeSave）

- [EntityMixin](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/mixin/entity/EntityMixin.java)
- [EntityTickListMixin](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/mixin/entity/EntityTickListMixin.java)
- [EntityOrderManager](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/safesave/entity/EntityOrderManager.java)
- [ServerLevelMixin](https://github.com/Melationin/SafeSave/blob/master/src/main/java/com/carpet/safesave/mixin/ServerLevelMixin.java)
