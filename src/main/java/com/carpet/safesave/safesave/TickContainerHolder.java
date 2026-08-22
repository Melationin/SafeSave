package com.carpet.safesave.safesave;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

/**
 * Duck interface injected into {@code LevelTicks}, exposing the registered per-chunk containers.
 *
 * <p>{@code LevelTicks.allContainers} is exactly "every chunk of this dimension that is loaded to at
 * least {@code FULL}", keyed by packed {@code ChunkPos} — precisely the set safe-save needs to sweep
 * at save time, and it hands over the chunk key for free.
 */
public interface TickContainerHolder {
    /** packed {@code ChunkPos} -> {@code LevelChunkTicks} (values also implement {@link SafeTickContainer}). */
    Long2ObjectMap<?> SS$containers();
}
