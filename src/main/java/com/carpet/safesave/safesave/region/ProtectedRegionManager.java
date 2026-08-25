package com.carpet.safesave.safesave.region;

import static com.carpet.safesave.util.DimensionIds.dimensionId;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.ServerLevel;

/**
 * ProtectedRegion 的运行时管理（纯服务，无静态可变状态）。
 *
 * <p>每个服务端世界刻 HEAD 调用 {@link #tick} 评估每个 region 的完整性；tick 门控热路径只查
 * {@code SafeSaveLevelState.protectedRegions.frozenChunks}（见 {@link #isChunkFrozen}）。
 *
 * <p>解冻时对 region 内已过期的计划刻做顺延重锚定（见 {@link ScheduledTickManager#rebaseOverdueTicks}），
 * 避免 region 冻结期间全局 gameTime 继续走导致解冻瞬间所有过期刻挤到同一 tick（时间压缩）。
 */
public final class ProtectedRegionManager {

    private ProtectedRegionManager() {
    }

    /** @return 该区块是否处于某个冻结 region 中；功能关闭或未定义 region 时恒为 {@code false} */
    public static boolean isChunkFrozen(final ServerLevel level, final long packedChunkPos) {
        if (!SafeSaveRules.safeSaveRegions) {
            return false;
        }
        return SafeSaveLevelAccess.of(level).protectedRegions.frozenChunks.contains(packedChunkPos);
    }

    /**
     * 每个 {@code ServerLevel.tick} HEAD 调用：更新 region 冻结状态。
     *
     * <p>完整性判据 = {@code ServerLevel.isPositionTickingWithEntitiesLoaded(packed)}，与计划刻的
     * vanilla 执行判据一致。只在功能开启时工作；功能关闭时清空冻结并集。
     */
    public static void tick(final ServerLevel level,
                            final SafeSaveSession session,
                            final SafeSaveLevelState levelState) {
        ProtectedRegionState regions = levelState.protectedRegions;
        if (!SafeSaveRules.safeSaveRegions) {
            for (ProtectedRegion region : regions.byName.values()) {
                region.frozen = false;
                region.frozenAt = -1L;
            }
            regions.frozenChunks.clear();
            return;
        }
        if (regions.byName.isEmpty()) {
            regions.frozenChunks.clear();
            return;
        }
        long gameTime = level.getGameTime();
        boolean anyTransition = false;
        for (ProtectedRegion region : regions.byName.values()) {
            boolean complete = isComplete(level, region);
            if (complete && region.frozen) {
                rebaseRegionTicks(level, region, gameTime);
                region.frozen = false;
                region.frozenAt = -1L;
                anyTransition = true;
                DebugLog.info("{}: ProtectedRegion '{}' complete again - unfrozen", dimensionId(level), region.name);
            } else if (!complete && !region.frozen) {
                region.frozen = true;
                region.frozenAt = gameTime;
                anyTransition = true;
                DebugLog.info("{}: ProtectedRegion '{}' incomplete - freezing {} chunk(s)",
                        dimensionId(level), region.name, region.chunks.size());
            }
        }
        if (anyTransition || !regions.frozenChunks.isEmpty()) {
            regions.rebuildFrozenChunks();
        }
    }

    /** 所有区块都可执行计划刻才视为完整。 */
    private static boolean isComplete(final ServerLevel level, final ProtectedRegion region) {
        for (long key : region.chunks) {
            if (!level.isPositionTickingWithEntitiesLoaded(key)) {
                return false;
            }
        }
        return true;
    }

    private static void rebaseRegionTicks(final ServerLevel level,
                                          final ProtectedRegion region,
                                          final long currentGameTime) {
        if (region.frozenAt < 0L) {
            return;
        }
        Long2ObjectMap<?> blockContainers = TickContainers.blockContainers(level);
        Long2ObjectMap<?> fluidContainers = TickContainers.fluidContainers(level);
        for (long key : region.chunks) {
            ScheduledTickManager.rebaseOverdueTicks(level, key,
                    blockContainers.get(key), fluidContainers.get(key),
                    region.frozenAt, currentGameTime);
        }
    }
}
