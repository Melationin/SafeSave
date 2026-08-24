package com.carpet.safesave.safesave.blockentity;

import com.carpet.safesave.debug.DebugLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 移动中的活塞（{@code PistonMovingBlockEntity}）的管理（#4）。
 *
 * <p>原版按 {@code Level.blockEntityTickers} 的插入顺序刻方块实体。重启后该顺序会变成
 * {@code BlockPos} 哈希顺序，导致同一刻内完成推动的相邻活塞互相观察到错误的邻居状态。
 * 本类为每个活塞持久化创建序号（{@link PistonOrderHolder}），并在加载后重建原始相对顺序。
 */
public final class PistonManager {

    /** 分配给每个新创建的 PistonMovingBlockEntity 的单调递增创建计数器（#4）。 */
    private static final java.util.concurrent.atomic.AtomicLong pistonOrder = new java.util.concurrent.atomic.AtomicLong();
    /**
     * 每当一个移动中的活塞从 NBT 加载时递增，因为其刻循环器（ticker）槽位顺序需要重建（#4）。
     * 之所以用代数计数器而不是布尔值，是因为 {@code loadAdditional} 在方块实体获得所属世界之前运行，
     * 此时维度未知——因此改为由每个世界记住自己上次重建时的代数。
     */
    private static final java.util.concurrent.atomic.AtomicLong pistonOrderGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private static final Map<String, Long> pistonOrderRebuiltAt = new HashMap<>();

    private PistonManager() {
    }

    /** @return 新构建的移动活塞的下一个创建序号 */
    public static long nextPistonOrder() {
        return pistonOrder.getAndIncrement();
    }

    /** 确保新创建的活塞严格排在所有从磁盘恢复的顺序值之后。 */
    public static void observePistonOrder(final long restored) {
        pistonOrder.accumulateAndGet(restored + 1L, Math::max);
    }

    public static void markPistonTickOrderDirty() {
        pistonOrderGeneration.incrementAndGet();
    }

    /** 服务端加载时重置会话状态。 */
    public static void reset() {
        pistonOrderRebuiltAt.clear();
    }

    /**
     * 在 {@code ServerLevel.tick} 的 HEAD 处调用。每个刻都运行（包括冻结期间，
     * 因为 {@code ServerLevel.tick} 本身不受门控）：若有活塞从 NBT 加载过（代数已推进），
     * 重建该维度的活塞刻顺序。
     */
    public static void onLevelTickStart(final ServerLevel level) {
        String dimension = dimensionId(level);
        long generation = pistonOrderGeneration.get();
        if (pistonOrderRebuiltAt.getOrDefault(dimension, -1L) < generation) {
            pistonOrderRebuiltAt.put(dimension, generation);
            rebuildPistonTickOrder(level);
        }
    }

    /**
     * 恢复 {@code Level.blockEntityTickers} 中移动活塞之间的原始相对刻顺序。
     *
     * <p>只按创建顺序升序重写当前被移动活塞占据的槽位；其余刻循环器保持原索引不变。
     * 这样可以修复活塞之间的顺序而不扰动其他任何东西，这一点很重要，因为同一刻内完成推动的两个相邻活塞
     * 各自都会运行 {@code updateFromNeighbourShapes}，从而观察到对方的结果。
     *
     * <p>在 {@code ServerLevel.tick} 的 HEAD 处调用是安全的：此时 {@code tickingBlockEntities} 为
     * {@code false}，不会有正在进行的迭代。
     */
    private static void rebuildPistonTickOrder(final ServerLevel level) {
        List<TickingBlockEntity> tickers =level.blockEntityTickers;
        List<Integer> slots = new ArrayList<>();
        List<TickingBlockEntity> pistons = new ArrayList<>();

        // 先按方块状态过滤：调色板读取廉价且无副作用，而 Level.getBlockEntity 使用
        // EntityCreationType.IMMEDIATE，会把整个世界的待创建方块实体提前实例化，比原版更早。
        // （检查方块状态而不是刻循环器注册的类型，也能避开 26.1 与 26.2 之间的
        // BlockEntityType/BlockEntityTypes 类改名问题。）
        for (int i = 0; i < tickers.size(); i++) {
            TickingBlockEntity ticker = tickers.get(i);
            if (ticker.isRemoved() || !level.getBlockState(ticker.getPos()).is(Blocks.MOVING_PISTON)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(ticker.getPos());
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

    private static String dimensionId(final ServerLevel level) {
        return level.dimension().identifier().toString();
    }
}
