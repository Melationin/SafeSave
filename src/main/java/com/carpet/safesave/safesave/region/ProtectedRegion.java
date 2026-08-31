package com.carpet.safesave.safesave.region;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Collection;


public final class ProtectedRegion {

    public final String name;
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
