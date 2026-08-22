package com.carpet.safesave.safesave;

/**
 * Duck interface injected into {@code PistonMovingBlockEntity}, exposing its creation order.
 *
 * <p>Vanilla ticks block entities in {@code Level.blockEntityTickers} insertion order. Before a save
 * that is the order {@code PistonBaseBlock.moveBlocks} created them in (reverse {@code toPush}, arm
 * last). After a reload it becomes {@code BlockPos} hash order, because the chunk writes its block
 * entities from a {@code HashSet} ({@code ChunkAccess.getBlockEntitiesPos}) and re-registers them
 * from a {@code HashMap} ({@code pendingBlockEntities}). Persisting a creation sequence number lets
 * the original relative order be restored.
 */
public interface PistonOrderHolder {

    /** Monotonic creation sequence number; {@link Long#MIN_VALUE} when unknown. */
    long SS$pistonOrder();
}
