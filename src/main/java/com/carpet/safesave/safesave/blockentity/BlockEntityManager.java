package com.carpet.safesave.safesave.blockentity;


import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.carpet.safesave.util.Util.dimensionId;


public final class BlockEntityManager {

    private BlockEntityManager() {
    }

    public static long nextBlockEntityOrder(final ServerLevel level) {
        return SafeSaveLevelAccess.of(level).blockEntityOrder.next();
    }

    public static void observeBlockEntityOrder(final ServerLevel level, final long restored) {
        SafeSaveLevelAccess.of(level).blockEntityOrder.observe(restored);
    }

    public static void markTickOrderDirty(final ServerLevel level) {
        SafeSaveLevelAccess.of(level).blockEntityOrderGeneration.incrementAndGet();
    }

    public static void queuePistonTimeRebase(final ServerLevel level,
                                             final PistonMovingBlockEntity piston) {
        SafeSaveLevelAccess.of(level).pendingPistonTimeRebases.add(new WeakReference<>(piston));
    }

    /*
     * 在 ServerLevel.tick HEAD 处调用（含冻结期间）。加载过的活塞始终校准时间；
     * 活塞和漏斗共用 tick 序号，是否重建顺序由维度级启动恢复入口统一控制。
     */
    public static void onLevelTickStart(final ServerLevel level,
                                        final SafeSaveLevelState levelState,
                                        final boolean rebuildOrder) {
        rebasePendingPistons(level, levelState);
        if (!rebuildOrder) {
            return;
        }
        long generation = levelState.blockEntityOrderGeneration.get();
        // 屏障结束时即使加载代数未变，也要对完整的启动集合执行一次排序。
        if (levelState.blockEntityOrderRebuiltAt >= generation
                && !(rebuildOrder && !levelState.startupRebuildComplete)) {
            return;
        }
        List<TickingBlockEntity> tickers = level.blockEntityTickers;
        List<TickingBlockEntity> snapshot = new ArrayList<>(tickers);
        List<Integer> slots = new ArrayList<>();
        List<OrderedBlockEntity> ordered = new ArrayList<>();
        
        for (int i = 0; i < snapshot.size(); i++) {
            TickingBlockEntity ticker = snapshot.get(i);
            if (ticker.isRemoved()) {
                continue;
            }
            BlockPos pos = ticker.getPos();
            if (pos == null) {
                continue;
            }
            var chunk = level.getChunkSource().getChunkNow(
                    SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
            if (chunk == null) {
                continue;
            }
            BlockEntity blockEntity = chunk.getBlockEntities().get(pos);
            if (blockEntity instanceof BlockEntityOrderHolder holder) {
                long order = holder.SS$blockEntityOrder();
                if (order != Long.MIN_VALUE) {
                    slots.add(i);
                    ordered.add(new OrderedBlockEntity(ticker, order));
                }
            }
        }
        rebuildTickOrder(level, slots, ordered);
        // 仅记录本轮开始时的代数；处理期间新加载的方块实体留到下一轮。
        levelState.blockEntityOrderRebuiltAt = generation;
    }

    private static void rebasePendingPistons(final ServerLevel level,
                                              final SafeSaveLevelState levelState) {
        var pending = levelState.pendingPistonTimeRebases;
        int count = pending.size();
        for (int i = 0; i < count; i++) {
            WeakReference<PistonMovingBlockEntity> reference = pending.poll();
            if (reference == null) {
                break;
            }
            PistonMovingBlockEntity piston = reference.get();
            if (piston == null || piston.isRemoved() || piston.getLevel() != level) {
                continue;
            }
            BlockPos pos = piston.getBlockPos();
            var chunk = level.getChunkSource().getChunkNow(
                    SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
            if (chunk == null || chunk.getBlockEntities().get(pos) != piston) {
                pending.add(reference);
                continue;
            }
            if (!chunk.getBlockState(pos).is(Blocks.MOVING_PISTON)) {
                continue;
            }
            try {
                ((PistonOrderHolder) piston).SS$rebaseTime(SafeSaveSession.of(level.getServer()).serverGameTime);
            } catch (RuntimeException | Error e) {
                // 校准失败时保留入口，不能提前消费待恢复状态。
                pending.add(reference);
                throw e;
            }
        }
    }

    private static void rebuildTickOrder(final ServerLevel level,
                                         final List<Integer> slots,
                                         final List<OrderedBlockEntity> ordered) {
        if (ordered.size() < 2) {
            return;
        }

        ordered.sort(Comparator.comparingLong(OrderedBlockEntity::order));
        for (int k = 0; k < slots.size(); k++) {
            level.blockEntityTickers.set(slots.get(k), ordered.get(k).ticker());
        }
        if (DebugLog.DEBUG) {
            DebugLog.debug("{}: rebuilt tick order of {} piston/hopper block entities by shared sequence",
                    dimensionId(level), ordered.size());
        }
    }

    private record OrderedBlockEntity(TickingBlockEntity ticker, long order) {}
}
