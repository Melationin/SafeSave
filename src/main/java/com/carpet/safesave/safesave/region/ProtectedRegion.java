package com.carpet.safesave.safesave.region;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Collection;

/**
 * 一个 ProtectedRegion：同一维度内的一组区块。
 *
 * <p>语义：region 内所有区块都满足
 * {@code ServerLevel.isPositionTickingWithEntitiesLoaded}（计划刻可执行）时，region 才整体 tick；
 * 只要有一个区块不满足，整个 region 冻结。{@link #frozen} / {@link #frozenAt} 是运行态，
 * 不持久化；持久化只存 {@link #name} 与 {@link #chunks}。
 */
public final class ProtectedRegion {

    public final String name;
    /** packed {@code ChunkPos}，持久化时按 LongArray 顺序写出。 */
    public final LongSet chunks = new LongOpenHashSet();

    /** 运行态：当前是否处于冻结。 */
    public boolean frozen;
    /** 运行态：本次冻结开始时的 {@code Level.gameTime}；{@code -1} = 未冻结。 */
    public long frozenAt = -1L;

    public ProtectedRegion(final String name) {
        this.name = name;
    }

    public ProtectedRegion(final String name, final Collection<Long> chunkKeys) {
        this.name = name;
        this.chunks.addAll(chunkKeys);
    }

    public boolean contains(final long packedChunkPos) {
        return this.chunks.contains(packedChunkPos);
    }
}
