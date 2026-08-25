package com.carpet.safesave.safesave.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个 {@code ServerLevel} 内的 ProtectedRegion 维度级状态。
 *
 * <p>挂在 {@code SafeSaveLevelState} 上，随 ServerLevel 创建/丢弃天然隔离。两个索引：
 * {@link #byName} 是定义索引；{@link #byChunk} 是区块 -> region 的查询索引（重叠时后者覆盖，
 * 但 gating 只依赖 {@link #frozenChunks} 的冻结并集，所以重叠不影响正确性）。
 * {@link #frozenChunks} 每个服务端 tick 由 {@code ProtectedRegionManager.tick} 重建，
 * 是 tick 门控热路径唯一查询的集合。
 */
public final class ProtectedRegionState {

    public final Map<String, ProtectedRegion> byName = new LinkedHashMap<>();
    public final Long2ObjectMap<ProtectedRegion> byChunk = new Long2ObjectOpenHashMap<>();
    public final LongSet frozenChunks = new LongOpenHashSet();

    public void addRegion(final ProtectedRegion region) {
        this.byName.put(region.name, region);
        reindex();
    }

    public boolean removeRegion(final String name) {
        ProtectedRegion removed = this.byName.remove(name);
        if (removed == null) {
            return false;
        }
        reindex();
        return true;
    }

    public boolean addChunk(final String name, final long packedChunkPos) {
        ProtectedRegion region = this.byName.get(name);
        if (region == null || !region.chunks.add(packedChunkPos)) {
            return false;
        }
        this.byChunk.put(packedChunkPos, region);
        return true;
    }

    public boolean removeChunk(final String name, final long packedChunkPos) {
        ProtectedRegion region = this.byName.get(name);
        if (region == null || !region.chunks.remove(packedChunkPos)) {
            return false;
        }
        if (this.byChunk.get(packedChunkPos) == region) {
            this.byChunk.remove(packedChunkPos);
        }
        return true;
    }

    /** 定义变更后重建区块 -> region 索引与冻结并集。 */
    public void reindex() {
        this.byChunk.clear();
        for (ProtectedRegion region : this.byName.values()) {
            for (long key : region.chunks) {
                this.byChunk.put(key, region);
            }
        }
        rebuildFrozenChunks();
    }

    /** 重建冻结区块并集：所有 frozen region 的区块；冻结优先。 */
    public void rebuildFrozenChunks() {
        this.frozenChunks.clear();
        for (ProtectedRegion region : this.byName.values()) {
            if (region.frozen) {
                this.frozenChunks.addAll(region.chunks);
            }
        }
    }
}
