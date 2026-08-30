package com.carpet.safesave.safesave.region;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个 {@code ServerLevel} 内的 ProtectedRegion 维度级状态。
 *
 * <p>挂在 {@code SafeSaveLevelState} 上，随 ServerLevel 创建/丢弃天然隔离。
 * Region 只用于保存时完整性记录与下次启动的全局冻结屏障，不再维护区块级冻结索引。
 */
public final class ProtectedRegionState {

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
