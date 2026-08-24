package com.carpet.safesave.safesave.blockentity;

/**
 * 注入 {@code PistonMovingBlockEntity} 的鸭子接口，暴露其创建顺序。
 *
 * <p>原版按 {@code Level.blockEntityTickers} 的插入顺序刻方块实体。保存前，该顺序就是
 * {@code PistonBaseBlock.moveBlocks} 创建它们的顺序（{@code toPush} 反向，活塞臂最后）。
 * 重载后它变成 {@code BlockPos} 哈希顺序，因为区块从一个 {@code HashSet}
 * （{@code ChunkAccess.getBlockEntitiesPos}）写出方块实体，再从 {@code HashMap}
 * （{@code pendingBlockEntities}）重新注册。持久化创建序号即可恢复原始相对顺序。
 */
public interface PistonOrderHolder {

    /** 单调递增的创建序号；未知时为 {@link Long#MIN_VALUE}。 */
    long SS$pistonOrder();
}
