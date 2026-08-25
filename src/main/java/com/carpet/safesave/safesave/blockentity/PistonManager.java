package com.carpet.safesave.safesave.blockentity;

import com.carpet.safesave.debug.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 移动中的活塞（{@code PistonMovingBlockEntity}）的管理（#4/#5）。
 *
 * <p>原版按 {@code Level.blockEntityTickers} 的插入顺序刻方块实体。重启后该顺序会变成
 * {@code BlockPos} 哈希顺序，导致同一刻内完成推动的相邻活塞互相观察到错误的邻居状态。
 * 本类为每个活塞持久化创建序号（{@link PistonOrderHolder}），并在新加载区块统一重建时
 * 恢复该区块内活塞状态与原始相对顺序。
 *
 * <p>v5 起活塞状态随 {@code SafeSaveStore.ChunkSnapshot} 按区块快照；同时 PME NBT 中的
 * {@code safesave_*} 键仍保留作为兼容/冗余。
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

    // ------------------------------------------------------------ 按区块快照

    /**
     * 捕获一个区块内所有移动活塞的 safe-save 状态。
     *
     * <p>只读取目标区块内已实际注册的方块实体，不扫描世界级 ticker 列表、不触碰未加载区块。
     * 在卸载流程中调用是安全的：通过 {@code ServerChunkCache.getChunkNow} 获取当前已加载 chunk，
     * 不会触发强制加载。
     */
    public static List<SafePiston> snapshotChunkPistons(final ServerLevel level, final long packedChunkPos) {
        net.minecraft.world.level.chunk.LevelChunk chunk =
                level.getChunkSource().getChunkNow(
                        net.minecraft.world.level.ChunkPos.getX(packedChunkPos),
                        net.minecraft.world.level.ChunkPos.getZ(packedChunkPos));
        if (chunk == null) {
            return List.of();
        }
        List<SafePiston> pistons = new ArrayList<>();
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (!level.getBlockState(blockEntity.getBlockPos()).is(Blocks.MOVING_PISTON)) {
                continue;
            }
            if (blockEntity instanceof SafePistonHolder holder) {
                pistons.add(holder.SS$snapshotPiston());
            }
        }
        pistons.sort(Comparator.comparingLong(SafePiston::order));
        return pistons;
    }

    /**
     * 捕获整个世界所有移动活塞，按区块分组。
     *
     * <p>全量保存路径使用：只遍历当前已加载区块的方块实体，避免扫描 world ticker 列表时
     * 对已卸载/正在卸载区块调用 {@code getBlockState}。
     */
    public static Map<Long, List<SafePiston>> snapshotByChunk(final ServerLevel level) {
        Map<Long, List<SafePiston>> byChunk = new LinkedHashMap<>();
        // 通过 ServerChunkCache 当前可见 chunk 遍历，而不是 level.blockEntityTickers。
        // getChunkNow 只返回已加载 FULL chunk，不会强制加载。
        net.minecraft.server.level.ServerChunkCache cache = level.getChunkSource();
        // 没有直接的“已加载 chunk 集合”公共 API，这里遍历世界 ticket 半径不可靠。
        // 因此全量保存仍使用 blockEntityTickers，但通过 getChunkNow 确认区块仍已加载后再 getBlockState。
        List<TickingBlockEntity> tickers = level.blockEntityTickers;
        for (TickingBlockEntity ticker : tickers) {
            if (ticker.isRemoved()) {
                continue;
            }
            BlockPos pos = ticker.getPos();
            if (pos == null) {
                continue;
            }
            long packed = net.minecraft.world.level.ChunkPos.pack(pos);
            // 关键：只在区块仍已加载时读取，避免 getBlockState 强制加载正在卸载的区块。
            net.minecraft.world.level.chunk.LevelChunk chunk = cache.getChunkNow(
                    net.minecraft.world.level.ChunkPos.getX(packed),
                    net.minecraft.world.level.ChunkPos.getZ(packed));
            if (chunk == null) {
                continue;
            }
            if (!level.getBlockState(pos).is(Blocks.MOVING_PISTON)) {
                continue;
            }
            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            if (!(blockEntity instanceof SafePistonHolder holder)) {
                continue;
            }
            byChunk.computeIfAbsent(packed, k -> new ArrayList<>()).add(holder.SS$snapshotPiston());
        }
        for (List<SafePiston> pistons : byChunk.values()) {
            pistons.sort(Comparator.comparingLong(SafePiston::order));
        }
        return byChunk;
    }

    /**
     * 把从区块快照恢复的活塞状态写回 PME 实例，并提升全局计数器。
     * 调用方需确保该活塞已经加载为 {@code PistonMovingBlockEntity}。
     */
    public static void restoreChunkPistons(final ServerLevel level, final List<SafePiston> saved) {
        if (saved == null || saved.isEmpty()) {
            return;
        }
        int restored = 0;
        for (SafePiston entry : saved) {
            BlockPos pos = new BlockPos(entry.x(), entry.y(), entry.z());
            if (!level.getBlockState(pos).is(Blocks.MOVING_PISTON)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof SafePistonHolder holder)) {
                continue;
            }
            if (entry.order() != Long.MIN_VALUE) {
                observePistonOrder(entry.order());
            }
            holder.SS$restorePiston(entry);
            restored++;
        }
        DebugLog.info("{}: restored {} moving piston state(s) from chunk snapshot",
                dimensionId(level), restored);
    }

    // ------------------------------------------------------------ 旧全量重建

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

    private static String dimensionId(final ServerLevel level) {
        return level.dimension().identifier().toString();
    }
}
