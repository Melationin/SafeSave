package com.carpet.safesave.safesave.blockentity;

import static com.carpet.safesave.util.DimensionIds.dimensionId;

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

/**
 * 移动中的活塞（{@code PistonMovingBlockEntity}）的管理（纯服务，无静态可变状态）。
 *
 * <p>原版按 {@code Level.blockEntityTickers} 的插入顺序刻方块实体。重启后该顺序会变成
 * {@code BlockPos} 哈希顺序，导致同一刻内完成推动的相邻活塞互相观察到错误的邻居状态。
 * 本类为每个活塞持久化创建序号（{@link PistonOrderHolder}），并在新加载区块统一重建时
 * 恢复该区块内活塞状态与原始相对顺序。
 *
 * <p>活塞状态持久化在 PME 方块实体 NBT 的 {@code safeSave} 子节点中。
 *
 * <p>活塞创建序号与刻循环器重建代数是<em>会话级</em>的：{@code PistonMovingBlockEntity}
 * 的 {@code loadAdditional} 在方块实体获得所属世界之前运行，没有 level 可以寻址，因此
 * 序号与代数保存在 {@link SafeSaveSession}；每个维度记住自己最近一次重建的代数
 * （{@link SafeSaveLevelState#pistonOrderRebuiltAt}）。
 */
public final class PistonManager {

    private PistonManager() {
    }

    /** @return 新构建的移动活塞的下一个创建序号；会话未就绪时为 0 */
    public static long nextPistonOrder() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0L : session.pistonOrder.next();
    }

    /** 确保新创建的活塞严格排在所有从磁盘恢复的顺序值之后。会话未就绪时 no-op。 */
    public static void observePistonOrder(final long restored) {
        SafeSaveSession session = SafeSaveSession.current();
        if (session != null) {
            session.pistonOrder.observe(restored);
        }
    }

    /** 标记所有维度的活塞刻顺序需要重建。会话未就绪时 no-op。 */
    public static void markPistonTickOrderDirty() {
        SafeSaveSession session = SafeSaveSession.current();
        if (session != null) {
            session.pistonOrderGeneration.incrementAndGet();
        }
    }

    /**
     * 在 {@code ServerLevel.tick} 的 HEAD 处调用。每个刻都运行（包括冻结期间，
     * 因为 {@code ServerLevel.tick} 本身不受门控）：若有活塞从 NBT 加载过（代数已推进），
     * 重建该维度的活塞刻顺序。
     */
    public static void onLevelTickStart(final ServerLevel level,
                                        final SafeSaveSession session,
                                        final SafeSaveLevelState levelState) {
        String dimension = dimensionId(level);
        long generation = session.pistonOrderGeneration.get();
        if (levelState.pistonOrderRebuiltAt < generation) {
            levelState.pistonOrderRebuiltAt = generation;
            rebuildPistonTickOrder(level);
        }
    }

    /**
     * 恢复 {@code Level.blockEntityTickers} 中移动活塞之间的原始相对刻顺序。
     *
     * <p>只按创建顺序升序重写当前被移动活塞占据的槽位；其余刻循环器保持原索引不变。
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
        DebugLog.info("{}: rebuilt tick order of {} moving piston(s) by creation sequence",
                dimensionId(level), pistons.size());
    }
}
