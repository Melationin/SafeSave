package com.carpet.safesave.safesave.blockentity;


import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
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


public final class PistonManager {

    private PistonManager() {
    }

    public static long nextPistonOrder(final ServerLevel level) {
        return SafeSaveLevelAccess.of(level).pistonOrder.next();
    }

    public static void observePistonOrder(final ServerLevel level, final long restored) {
        SafeSaveLevelAccess.of(level).pistonOrder.observe(restored);
    }

    public static void markPistonTickOrderDirty(final ServerLevel level) {
        SafeSaveLevelAccess.of(level).pistonOrderGeneration.incrementAndGet();
    }

    public static void queuePistonTimeRebase(final ServerLevel level,
                                             final PistonMovingBlockEntity piston) {
        SafeSaveLevelAccess.of(level).pendingPistonTimeRebases.add(new WeakReference<>(piston));
    }

    /*
     * 在 ServerLevel.tick HEAD 处调用（含冻结期间）。加载过的活塞始终校准时间；
     * 是否重建顺序由维度级启动恢复入口统一控制。
     */
    public static void onLevelTickStart(final ServerLevel level,
                                        final SafeSaveLevelState levelState,
                                        final boolean rebuildOrder) {
        rebasePendingPistons(level, levelState);
        if (!rebuildOrder) {
            return;
        }
        long generation = levelState.pistonOrderGeneration.get();
        // 屏障结束时即使加载代数未变，也要对完整的启动集合执行一次排序。
        if (levelState.pistonOrderRebuiltAt >= generation
                && !(rebuildOrder && !levelState.startupRebuildComplete)) {
            return;
        }
        List<TickingBlockEntity> tickers = level.blockEntityTickers;
        List<TickingBlockEntity> snapshot = new ArrayList<>(tickers);
        List<Integer> slots = new ArrayList<>();
        List<OrderedPiston> pistons = new ArrayList<>();
        
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
            if (chunk == null || !chunk.getBlockState(pos).is(Blocks.MOVING_PISTON)) {
                continue;
            }
            BlockEntity blockEntity = chunk.getBlockEntities().get(pos);
            if (blockEntity instanceof PistonOrderHolder holder) {
                long order = holder.SS$pistonOrder();
                if (order != Long.MIN_VALUE) {
                    slots.add(i);
                    pistons.add(new OrderedPiston(ticker, order));
                }
            }
        }
        rebuildPistonTickOrder(level, slots, pistons);
        // 仅记录本轮开始时的代数；处理期间新加载的活塞留到下一轮。
        levelState.pistonOrderRebuiltAt = generation;
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
                ((PistonOrderHolder) piston).SS$rebaseTime(level.getGameTime());
            } catch (RuntimeException | Error e) {
                // 校准失败时保留入口，不能提前消费待恢复状态。
                pending.add(reference);
                throw e;
            }
        }
    }

    private static void rebuildPistonTickOrder(final ServerLevel level,
                                               final List<Integer> slots,
                                               final List<OrderedPiston> pistons) {
        if (pistons.size() < 2) {
            return;
        }

        pistons.sort(Comparator.comparingLong(OrderedPiston::order));
        for (int k = 0; k < slots.size(); k++) {
            level.blockEntityTickers.set(slots.get(k), pistons.get(k).ticker());
        }
        if (DebugLog.DEBUG) {
            DebugLog.debug("{}: rebuilt tick order of {} moving piston(s) by creation sequence",
                    dimensionId(level), pistons.size());
        }
    }

    private record OrderedPiston(TickingBlockEntity ticker, long order) {}
}
