package com.carpet.safesave.safesave.region;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Collection;

/**
 * 一个 ProtectedRegion：同一维度内的一组区块。
 *
 * <p>Region 不参与运行时局部 tick 门控。保存时若其全部区块都已完整加载，会把
 * {@link #requiredAtStartup} 写入旁置元数据；下次启动的 region 解冻模式只等待这些 Region。
 */
public final class ProtectedRegion {

    public final String name;
    /** packed {@code ChunkPos}，持久化时按 LongArray 顺序写出。 */
    public final LongSet chunks = new LongOpenHashSet();

    /** 上次保存时是否完整加载；决定下次启动时是否需要等待该 Region。 */
    public boolean requiredAtStartup;

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
