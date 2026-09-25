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

    public static long nextPistonOrder() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0L : session.pistonOrder.next();
    }

    // 确保新创建的活塞严格排在所有从磁盘恢复的顺序值之后；会话未就绪时 no-op。
    public static void observePistonOrder(final long restored) {
        SafeSaveSession session = SafeSaveSession.current();
        if (session != null) {
            session.pistonOrder.observe(restored);
        }
    }

    public static void markPistonTickOrderDirty() {
        SafeSaveSession session = SafeSaveSession.current();
        if (session != null) {
            session.pistonOrderGeneration.incrementAndGet();
        }
    }

    /*
     * 在 ServerLevel.tick HEAD 处调用，每刻都运行（含冻结期间，ServerLevel.tick
     * 本身不受门控）：若活塞从 NBT 加载过（代数已推进），重建该维度活塞刻顺序。
     */
    public static void onLevelTickStart(final ServerLevel level,
                                        final SafeSaveSession session,
                                        final SafeSaveLevelState levelState) {
        String dimension = dimensionId(level);
        long generation = session.pistonOrderGeneration.get();
        if (levelState.pistonOrderRebuiltAt < generation) {
            levelState.pistonOrderRebuiltAt = generation;
            // Includes chunks revived before physical unload, whose NBT load hook never fires.
            for (var ticker : level.blockEntityTickers) {
                if (ticker.isRemoved()) continue;
                // 锂 sleeping 的哨兵 ticker 恒返回 pos == null 且 isRemoved() == false，必须先取出判空。
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

    /*
     * 恢复 Level.blockEntityTickers 中移动活塞之间的原始相对刻顺序；
     * 只按创建顺序升序重写被移动活塞占据的槽位，其余刻循环器保持原索引不变。
     */
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
