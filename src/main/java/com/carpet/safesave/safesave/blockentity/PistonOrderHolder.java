package com.carpet.safesave.safesave.blockentity;

/**
 * 注入 {@code PistonMovingBlockEntity} 的鸭子接口，暴露其创建顺序。
 *
 * <p>保存前 {@code Level.blockEntityTickers} 的顺序 = {@code PistonBaseBlock.moveBlocks}
 * 的创建顺序；重载后因方块实体经 {@code HashSet}（{@code ChunkAccess.getBlockEntitiesPos}）
 * 写出、{@code HashMap}（{@code pendingBlockEntities}）重注册而变成 {@code BlockPos} 哈希顺序。
 * 持久化创建序号即可恢复原始相对顺序。
 */
public interface PistonOrderHolder {

    /** 单调递增的创建序号；未知时为 {@link Long#MIN_VALUE}。 */
    long SS$pistonOrder();
}
