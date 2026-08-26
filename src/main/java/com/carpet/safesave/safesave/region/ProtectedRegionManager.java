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
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;

/**
 * ProtectedRegion 的运行时管理（纯服务，无静态可变状态）。
 *
 * <p>每个服务端世界刻 HEAD 调用 {@link #tick} 评估每个 region 的完整性；tick 门控热路径只查
 * {@code SafeSaveLevelState.protectedRegions.frozenChunks}（见 {@link #isChunkFrozen}）。
 *
 * <p>解冻时对 region 内的全部计划刻按冻结时长做顺延重锚定（见
 * {@link ScheduledTickManager#rebaseFrozenTicks}），使 region 内的计划刻倒计时随 region 一起暂停。
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
                long frozenAt = region.frozenAt;
                rebaseRegionTicks(level, region, gameTime);
                region.frozen = false;
                region.frozenAt = -1L;
                anyTransition = true;
                DebugLog.info("{}: ProtectedRegion '{}' complete again - unfrozen at gameTime={} "
                                + "(frozenAt={}, duration={})",
                        dimensionId(level), region.name, gameTime, frozenAt,
                        frozenAt < 0L ? 0L : Math.max(gameTime - frozenAt, 0L));
            } else if (!complete && !region.frozen) {
                region.frozen = true;
                region.frozenAt = gameTime;
                anyTransition = true;
                DebugLog.info("{}: ProtectedRegion '{}' incomplete at gameTime={} - freezing {} chunk(s)",
                        dimensionId(level), region.name, gameTime, region.chunks.size());
            }
        }
        if (anyTransition || !regions.frozenChunks.isEmpty()) {
            regions.rebuildFrozenChunks();
        }
    }

    /**
     * 所有区块的刻容器都已就绪（已注册到 {@code LevelTicks.allContainers} 且已解包）、实体已加载，
     * 并且原版 BLOCK_TICKING future 已完成时才视为完整。
     *
     * <p>不能使用 {@code level.isPositionTickingWithEntitiesLoaded(key)}：它内部会调用
     * {@code ServerLevel.shouldTickBlocksAt}，而后者已被本功能的冻结门控改写——冻结中的区块永远
     * 返回 {@code false}，会形成“冻结 → 完整性检查失败 → 继续冻结”的死锁。
     *
     * <p>这里直接展开 {@code ServerChunkCache.isPositionTicking} 的原版判据，绕开被 region 门控的
     * {@code shouldTickBlocksAt}：原始 block-ticking range + 可见 holder 的 ticking future；再补上
     * {@code areEntitiesLoaded}。这样 region 解冻的同一个 tick 中，计划刻、方块事件、方块实体与
     * 实体都已经具备运行条件。
     */
    private static boolean isComplete(final ServerLevel level, final ProtectedRegion region) {
        Long2ObjectMap<?> blockContainers = TickContainers.blockContainers(level);
        Long2ObjectMap<?> fluidContainers = TickContainers.fluidContainers(level);
        ServerChunkCache chunkSource = level.getChunkSource();
        for (long key : region.chunks) {
            if (!TickContainers.isReady(blockContainers.get(key), fluidContainers.get(key))) {
                return false;
            }
            if (!level.areEntitiesLoaded(key)
                    || !chunkSource.chunkMap.getDistanceManager().inBlockTickingRange(key)) {
                return false;
            }
            ChunkHolder holder = chunkSource.getVisibleChunkIfPresent(key);
            if (holder == null
                    || !holder.getTickingChunkFuture()
                    .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)
                    .isSuccess()) {
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
            ScheduledTickManager.rebaseFrozenTicks(level, key,
                    blockContainers.get(key), fluidContainers.get(key),
                    region.frozenAt, currentGameTime);
        }
    }
}
