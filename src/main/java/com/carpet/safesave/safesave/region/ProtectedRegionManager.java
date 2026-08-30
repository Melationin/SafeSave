package com.carpet.safesave.safesave.region;

import static com.carpet.safesave.util.DimensionIds.dimensionId;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.scheduled.SafeTickContainer;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

/**
 * ProtectedRegion 的运行时管理（纯服务，无静态可变状态）。
 *
 * <p>每个服务端世界刻 HEAD 调用 {@link #tick} 评估每个 region 的完整性；tick 门控热路径只查
 * {@code SafeSaveLevelState.protectedRegions.frozenChunks}（见 {@link #isChunkFrozen}）。
 *
 * <p>冻结期间每经过一个正常世界刻，就把当时仍加载的区块计划刻后移一刻（见
 * {@link ScheduledTickManager#shiftFrozenTicksOneTick}）。卸载区块由原版 SavedTick 相对 delay
 * 自己暂停，从而避免解冻时统一重锚造成常驻区块与重载区块的相位分裂。
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
                region.completeFrozenTicks = 0;
            }
            regions.frozenChunks.clear();
            return;
        }
        if (regions.byName.isEmpty()) {
            regions.frozenChunks.clear();
            return;
        }
        long gameTime = level.getGameTime();
        for (ProtectedRegion region : regions.byName.values()) {
            Readiness readiness = readiness(level, levelState, region);
            if (readiness.complete() && region.frozen) {
                if (!level.tickRateManager().runsNormally()) {
                    continue;
                }
                if (region.completeFrozenTicks == 0) {
                    region.completeFrozenTicks = 1;
                    DebugLog.info("{}: ProtectedRegion '{}' physically complete at gameTime={}, "
                                    + "holding frozen for one settle tick ({})",
                            dimensionId(level), region.name, gameTime, readiness.describe());
                    continue;
                }
                long frozenAt = region.frozenAt;
                region.frozen = false;
                region.frozenAt = -1L;
                region.completeFrozenTicks = 0;
                QueueStats queues = queueStats(level, region);
                DebugLog.info("{}: ProtectedRegion '{}' complete again - unfrozen at gameTime={} "
                                + "(frozenAt={}, duration={}, queues={})",
                        dimensionId(level), region.name, gameTime, frozenAt,
                        frozenAt < 0L ? 0L : Math.max(gameTime - frozenAt, 0L), queues.describe());
            } else if (!readiness.complete()) {
                region.completeFrozenTicks = 0;
                if (region.frozen) {
                    continue;
                }
                region.frozen = true;
                region.frozenAt = gameTime;
                QueueStats queues = queueStats(level, region);
                DebugLog.info("{}: ProtectedRegion '{}' incomplete at gameTime={} - freezing {} chunk(s) "
                                + "({}; queues={})",
                        dimensionId(level), region.name, gameTime, region.chunks.size(),
                        readiness.describe(), queues.describe());
            }
        }
        regions.rebuildFrozenChunks();
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
    private static Readiness readiness(final ServerLevel level,
                                       final SafeSaveLevelState levelState,
                                       final ProtectedRegion region) {
        Long2ObjectMap<?> blockContainers = TickContainers.blockContainers(level);
        Long2ObjectMap<?> fluidContainers = TickContainers.fluidContainers(level);
        ServerChunkCache chunkSource = level.getChunkSource();
        int containersMissing = 0;
        int snapshotsPending = 0;
        int entitiesMissing = 0;
        int rangeMissing = 0;
        int tickingFutureMissing = 0;
        for (long key : region.chunks) {
            if (!TickContainers.isReady(blockContainers.get(key), fluidContainers.get(key))) {
                containersMissing++;
            }
            if (levelState.pendingChunks.containsKey(key)) {
                snapshotsPending++;
            }
            if (!level.areEntitiesLoaded(key)) {
                entitiesMissing++;
            }
            if (!chunkSource.chunkMap.getDistanceManager().inBlockTickingRange(key)) {
                rangeMissing++;
            }
            ChunkHolder holder = chunkSource.getVisibleChunkIfPresent(key);
            if (holder == null
                    || !holder.getTickingChunkFuture()
                    .getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)
                    .isSuccess()) {
                tickingFutureMissing++;
            }
        }
        return new Readiness(containersMissing, snapshotsPending, entitiesMissing,
                rangeMissing, tickingFutureMissing);
    }

    /**
     * 在 SafeSave 的新加载区块恢复之后、ServerLevel.tick 真正开始之前调用。
     * 每个正常世界刻把已加载冻结区块的本地计划刻时钟向后移动一刻。
     */
    public static void pauseFrozenScheduledTicks(final ServerLevel level,
                                                 final SafeSaveLevelState levelState) {
        if (!SafeSaveRules.safeSaveRegions || !level.tickRateManager().runsNormally()) {
            return;
        }
        Long2ObjectMap<?> blockContainers = TickContainers.blockContainers(level);
        Long2ObjectMap<?> fluidContainers = TickContainers.fluidContainers(level);
        int shiftedChunks = 0;
        int shiftedBlocks = 0;
        int shiftedFluids = 0;
        for (long key : levelState.protectedRegions.frozenChunks) {
            if (!TickContainers.isReady(blockContainers.get(key), fluidContainers.get(key))) {
                continue;
            }
            ScheduledTickManager.ShiftResult shifted = ScheduledTickManager.shiftFrozenTicksOneTick(
                    blockContainers.get(key), fluidContainers.get(key));
            if (shifted.blockTicks() != 0 || shifted.fluidTicks() != 0) {
                shiftedChunks++;
                shiftedBlocks += shifted.blockTicks();
                shiftedFluids += shifted.fluidTicks();
            }
        }
        long gameTime = level.getGameTime();
        if (shiftedChunks > 0 && gameTime % 20L == 0L) {
            DebugLog.info("{}: ProtectedRegion local clock paused at gameTime={} - shifted {} block + {} fluid "
                            + "scheduled tick(s) in {} loaded frozen chunk(s)",
                    dimensionId(level), gameTime, shiftedBlocks, shiftedFluids, shiftedChunks);
        }
    }

    /**
     * {@code ServerChunkCache.tick} 会在计划刻/区块刻之后更新 ticket、holder 与卸载状态。
     * 因此在方块事件、实体和方块实体阶段之前再检查一次；这里只允许 active -> frozen，绝不在
     * 世界刻中途解冻。这样后半刻不会继续使用 HEAD 时已经过期的完整性结论。
     */
    public static void freezeIfBecameIncompleteAfterChunkSource(final ServerLevel level) {
        if (!SafeSaveRules.safeSaveRegions || !level.tickRateManager().runsNormally()) {
            return;
        }
        SafeSaveLevelState levelState = SafeSaveLevelAccess.of(level);
        ProtectedRegionState regions = levelState.protectedRegions;
        boolean changed = false;
        for (ProtectedRegion region : regions.byName.values()) {
            Readiness readiness = readiness(level, levelState, region);
            if (readiness.complete()) {
                continue;
            }
            if (region.frozen) {
                if (region.completeFrozenTicks != 0) {
                    region.completeFrozenTicks = 0;
                    DebugLog.info("{}: ProtectedRegion '{}' settle aborted after chunkSource at gameTime={} ({})",
                            dimensionId(level), region.name, level.getGameTime(), readiness.describe());
                }
                continue;
            }
            region.frozen = true;
            region.frozenAt = level.getGameTime();
            region.completeFrozenTicks = 0;
            changed = true;
            DebugLog.warn("{}: ProtectedRegion '{}' became incomplete after chunkSource in gameTime={}; "
                            + "freezing before block events/entities/block entities ({})",
                    dimensionId(level), region.name, level.getGameTime(), readiness.describe());
        }
        if (changed) {
            regions.rebuildFrozenChunks();
        }
    }

    private static QueueStats queueStats(final ServerLevel level, final ProtectedRegion region) {
        Long2ObjectMap<?> blockContainers = TickContainers.blockContainers(level);
        Long2ObjectMap<?> fluidContainers = TickContainers.fluidContainers(level);
        int blockTicks = 0;
        int fluidTicks = 0;
        for (long key : region.chunks) {
            blockTicks += queueSize(blockContainers.get(key));
            fluidTicks += queueSize(fluidContainers.get(key));
        }
        int blockEvents = 0;
        for (BlockEventData event : level.blockEvents) {
            if (region.contains(ChunkPos.pack(event.pos()))) {
                blockEvents++;
            }
        }
        int blockEntities = 0;
        for (TickingBlockEntity ticker : level.blockEntityTickers) {
            if (!ticker.isRemoved() && ticker.getPos() != null
                    && region.contains(ChunkPos.pack(ticker.getPos()))) {
                blockEntities++;
            }
        }
        return new QueueStats(blockTicks, fluidTicks, blockEvents, blockEntities);
    }

    private static int queueSize(final Object container) {
        if (!(container instanceof SafeTickContainer safe) || safe.SS$hasPendingTicks()) {
            return 0;
        }
        java.util.List<?> queue = safe.SS$snapshotQueue();
        return queue == null ? 0 : queue.size();
    }

    private record Readiness(int containersMissing,
                             int snapshotsPending,
                             int entitiesMissing,
                             int rangeMissing,
                             int tickingFutureMissing) {
        private boolean complete() {
            return this.containersMissing == 0 && this.snapshotsPending == 0
                    && this.entitiesMissing == 0 && this.rangeMissing == 0
                    && this.tickingFutureMissing == 0;
        }

        private String describe() {
            return "missing: containers=" + this.containersMissing
                    + ", snapshots=" + this.snapshotsPending
                    + ", entities=" + this.entitiesMissing
                    + ", blockRange=" + this.rangeMissing
                    + ", tickingFuture=" + this.tickingFutureMissing;
        }
    }

    private record QueueStats(int blockTicks, int fluidTicks, int blockEvents, int blockEntities) {
        private String describe() {
            return "blockTicks=" + this.blockTicks + ", fluidTicks=" + this.fluidTicks
                    + ", blockEvents=" + this.blockEvents + ", blockEntities=" + this.blockEntities;
        }
    }
}
