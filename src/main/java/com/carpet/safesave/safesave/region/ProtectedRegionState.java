package com.carpet.safesave.safesave.region;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个 {@code ServerLevel} 内的 ProtectedRegion 维度级状态：挂在 {@code SafeSaveLevelState} 上，
 * 随 ServerLevel 创建/丢弃天然隔离。
 */
public final class ProtectedRegionState {

    public final java.util.Set<Long> ticketedChunks = new java.util.HashSet<>();
    /** Loading-only tickets held between pre-tick deactivation and the end-of-tick snapshot. */
    public final java.util.Set<Long> holdingChunks = new java.util.HashSet<>();
    public final LongSet suspendedAt = new LongOpenHashSet();
    public boolean waiting;

    public final Map<String, ProtectedRegion> byName = new LinkedHashMap<>();

    public void addRegion(final ProtectedRegion region) {
        this.byName.put(region.name, region);
    }

    public boolean removeRegion(final String name) {
        return this.byName.remove(name) != null;
    }

    public boolean addChunk(final String name, final long packedChunkPos) {
        ProtectedRegion region = this.byName.get(name);
        if (region == null || !region.chunks.add(packedChunkPos)) {
            return false;
        }
        return true;
    }

    public boolean removeChunk(final String name, final long packedChunkPos) {
        ProtectedRegion region = this.byName.get(name);
        if (region == null || !region.chunks.remove(packedChunkPos)) {
            return false;
        }
        return true;
    }
}
