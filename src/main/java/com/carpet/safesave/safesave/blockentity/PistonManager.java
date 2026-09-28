package com.carpet.safesave.safesave.blockentity;


import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayList;
import java.util.List;

import static com.carpet.safesave.util.Util.dimensionId;


public final class PistonManager {

    private PistonManager() {
    }

    public static long nextPistonOrder(final ServerLevel level) {
        return SafeSaveSession.of(level.getServer()).pistonOrder.next();
    }

    public static void observePistonOrder(final ServerLevel level, final long restored) {
        SafeSaveSession.of(level.getServer()).pistonOrder.observe(restored);
    }

    public static void markPistonTickOrderDirty(final ServerLevel level) {
        SafeSaveSession.of(level.getServer()).pistonOrderGeneration.incrementAndGet();
    }

    /*
     * 在 ServerLevel.tick HEAD 处调用，每刻都运行（含冻结期间，ServerLevel.tick
     * 本身不受门控）：若活塞从 NBT 加载过（代数已推进），重建该维度活塞刻顺序。
     */
    public static void onLevelTickStart(final ServerLevel level,
                                        final SafeSaveSession session,
                                        final SafeSaveLevelState levelState) {
        long generation = session.pistonOrderGeneration.get();
        if (levelState.pistonOrderRebuiltAt < generation) {
            levelState.pistonOrderRebuiltAt = generation;
            // 包含物理卸载前被复活的区块，它们的 NBT 加载钩子不会触发。
            for (var ticker : level.blockEntityTickers) {
                if (ticker.isRemoved()) continue;
                BlockPos pos = ticker.getPos();
                if (pos == null) continue;
                if (!level.getBlockState(pos).is(Blocks.MOVING_PISTON)) continue;
                if (level.getBlockEntity(pos) instanceof PistonOrderHolder piston) {
                    piston.SS$rebaseTime(level.getGameTime());
                }
            }
            rebuildPistonTickOrder(level);
        }
    }


    private static void rebuildPistonTickOrder(final ServerLevel level) {
        List<TickingBlockEntity> tickers = level.blockEntityTickers;
        List<Integer> slots = new ArrayList<>();
        List<TickingBlockEntity> pistons = new ArrayList<>();

        for (int i = 0; i < tickers.size(); i++) {
            TickingBlockEntity ticker = tickers.get(i);
            if (ticker.isRemoved()) {
                continue;
            }
            BlockPos pos = ticker.getPos();
            if (pos == null || !level.getBlockState(pos).is(Blocks.MOVING_PISTON)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof PistonOrderHolder holder && holder.SS$pistonOrder() != Long.MIN_VALUE) {
                slots.add(i);
                pistons.add(ticker);
            }
        }
        if (pistons.size() < 2) {
            return;
        }

        pistons.sort((a, b) -> {
            BlockEntity beA = level.getBlockEntity(a.getPos());
            BlockEntity beB = level.getBlockEntity(b.getPos());
            long orderA = beA instanceof PistonOrderHolder h ? h.SS$pistonOrder() : Long.MAX_VALUE;
            long orderB = beB instanceof PistonOrderHolder h ? h.SS$pistonOrder() : Long.MAX_VALUE;
            return Long.compare(orderA, orderB);
        });
        for (int k = 0; k < slots.size(); k++) {
            tickers.set(slots.get(k), pistons.get(k));
        }
        if (DebugLog.DEBUG) {
            DebugLog.debug("{}: rebuilt tick order of {} moving piston(s) by creation sequence",
                    dimensionId(level), pistons.size());
        }
    }
}
