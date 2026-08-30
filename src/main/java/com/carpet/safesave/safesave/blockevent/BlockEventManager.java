package com.carpet.safesave.safesave.blockevent;

import static com.carpet.safesave.util.DimensionIds.dimensionId;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方块事件（block event）的按区块保存与恢复管理（纯服务，无静态可变状态）。
 *
 * <p>方块事件随 {@link com.carpet.safesave.safesave.SafeSaveStore.ChunkSnapshot} 按区块保存。
 * 每条事件带全局 {@code order}，用于把分散在不同区块快照中的事件重新合并成世界级执行顺序。
 *
 * <p>原版的队列是 {@code ServerLevel.blockEvents}（{@code ObjectLinkedOpenHashSet}），
 * {@code runBlockEvents} 用 {@code removeFirst()} 按插入顺序取出，因此插入顺序<em>即</em>执行顺序。
 * 本类在 {@code ServerLevel.blockEvent} 插入时分配单调递增的全局序号，并在保存时按当前队列
 * 分组到各区块；恢复时把同一 tick 内所有被重建区块的事件按全局序号排序后统一重新入队。
 *
 * <p>维度级序号表与计数器在 {@link SafeSaveLevelState}；会话级诊断计数在 {@link SafeSaveSession}。
 */
public final class BlockEventManager {

    /** 按 {@code order} 升序比较保存的方块事件。 */
    public static final Comparator<SafeBlockEvent> COMPARE_BY_ORDER =
            Comparator.comparingLong(SafeBlockEvent::order);

    private BlockEventManager() {
    }

    // ------------------------------------------------------------ 运行时序号

    /**
     * 在 {@code ServerLevel.blockEvent} TAIL 调用。若事件是首次加入队列则分配全局序号；
     * 重复事件（集合去重）保持原有序号。
     *
     * <p>注意 TAIL 注入在 vanilla {@code add} 之后：重复事件不会被再次插入，但方法仍会执行。
     * 这里用 {@code levelOrders.containsKey(event)} 识别重复，保持原有序号。
     */
    public static void onBlockEvent(final ServerLevel level, final BlockEventData event) {
        SafeSaveLevelState levelState = SafeSaveLevelAccess.of(level);
        Map<BlockEventData, Long> levelOrders = levelState.blockEventOrders;
        if (levelOrders.containsKey(event)) {
            return;
        }
        levelOrders.put(event, levelState.nextBlockEventOrder++);
    }

    /**
     * 丢弃已经不在 {@code ServerLevel.blockEvents} 中的序号记录，并为队列中缺少序号的事件
     * （例如来自其他 mod 直接操作队列）补发序号。每次保存前调用，避免长期运行内存无限增长。
     */
    private static void refreshOrders(final ServerLevel level, final SafeSaveLevelState levelState) {
        Map<BlockEventData, Long> old = levelState.blockEventOrders;
        Map<BlockEventData, Long> current = new LinkedHashMap<>();
        long next = levelState.nextBlockEventOrder;
        for (BlockEventData event : level.blockEvents) {
            Long order = old.get(event);
            if (order == null) {
                order = next++;
            }
            current.put(event, order);
        }
        levelState.blockEventOrders = current;
        levelState.nextBlockEventOrder = Math.max(levelState.nextBlockEventOrder, next);
    }

    /** 当前事件队列中的事件总数（调试用）。 */
    public static int liveCount(final Level level) {
        return level instanceof ServerLevel serverLevel ? serverLevel.blockEvents.size() : -1;
    }

    // ------------------------------------------------------------ 快照

    /**
     * 将当前世界级队列按区块分组，并保留每条事件的全局 {@code order}。
     * 供区块 NBT 保存路径（{@code ChunkNbtBridge}）按区块取用。
     */
    public static Map<Long, List<SafeBlockEvent>> snapshotByChunk(final ServerLevel level,
                                                                  final SafeSaveLevelState levelState) {
        refreshOrders(level, levelState);
        Map<BlockEventData, Long> levelOrders = levelState.blockEventOrders;
        Map<Long, List<SafeBlockEvent>> byChunk = new LinkedHashMap<>();
        long index = 0;
        for (BlockEventData event : level.blockEvents) {
            Long order = levelOrders.get(event);
            if (order == null) {
                // 理论上 refreshOrders 后不会发生；防御性回退到队列下标。
                order = index;
            }
            byChunk.computeIfAbsent(ChunkPos.pack(event.pos()), k -> new ArrayList<>())
                    .add(new SafeBlockEvent(
                            BuiltInRegistries.BLOCK.getKey(event.block()).toString(),
                            event.pos().getX(),
                            event.pos().getY(),
                            event.pos().getZ(),
                            event.paramA(),
                            event.paramB(),
                            order));
            index++;
        }
        for (List<SafeBlockEvent> events : byChunk.values()) {
            events.sort(COMPARE_BY_ORDER);
        }
        return byChunk;
    }

    /** 取单个区块的方块事件快照（用于保存路径）。 */
    public static List<SafeBlockEvent> snapshotChunkEvents(final ServerLevel level,
                                                           final long packedChunkPos,
                                                           final SafeSaveLevelState levelState) {
        return snapshotByChunk(level, levelState).getOrDefault(packedChunkPos, List.of());
    }

    // ------------------------------------------------------------ 恢复

    /**
     * 把一批（可能来自多个区块快照的）方块事件按全局序号排序后重新入队。
     *
     * <p>已有的实时事件会被保留在恢复事件之后：恢复的事件来自更早的保存，严格更老。
     * 队列是集合，因此与恢复事件重复的实时事件不会重复出现。
     */
    public static void restoreChunkEvents(final ServerLevel level,
                                          final List<SafeBlockEvent> saved,
                                          final SafeSaveSession session,
                                          final SafeSaveLevelState levelState) {
        if (saved == null || saved.isEmpty()) {
            return;
        }
        List<SafeBlockEvent> valid = new ArrayList<>(saved.size());
        for (SafeBlockEvent entry : saved) {
            Identifier id = Identifier.tryParse(entry.blockId());
            // BLOCK 是 DefaultedRegistry：getValue() 遇到未知 id 会悄悄返回 AIR。
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                session.droppedBlockEventCount.incrementAndGet();
                DebugLog.warn("dropping block event for unknown block '{}' at ({},{},{})",
                        entry.blockId(), entry.x(), entry.y(), entry.z());
                continue;
            }
            valid.add(entry);
        }
        if (valid.isEmpty()) {
            return;
        }
        valid.sort(COMPARE_BY_ORDER);

        ObjectLinkedOpenHashSet<BlockEventData> queue = level.blockEvents;
        List<BlockEventData> existing = new ArrayList<>(queue);
        try {
            queue.clear();

            Map<BlockEventData, Long> levelOrders = levelState.blockEventOrders;
            long next = levelState.nextBlockEventOrder;
            int restored = 0;
            for (SafeBlockEvent entry : valid) {
                Block block = BuiltInRegistries.BLOCK.getValue(Identifier.tryParse(entry.blockId()));
                BlockEventData event = new BlockEventData(
                        new BlockPos(entry.x(), entry.y(), entry.z()),
                        block, entry.paramA(), entry.paramB());
                queue.add(event);
                // 若事件已存在于实时队列（重复），保留其原有序号；否则写入保存的序号。
                levelOrders.putIfAbsent(event, entry.order());
                if (entry.order() >= next) {
                    next = entry.order() + 1;
                }
                restored++;
            }
            levelState.nextBlockEventOrder = next;
            session.restoredBlockEventCount.addAndGet(restored);
            DebugLog.info("{}: restored {} block event(s) in global order ({} pre-existing kept behind them)",
                    dimensionId(level), restored, existing.size());
        } finally {
            queue.addAll(existing);
        }
    }

    /** 世界刻日志行的调试辅助方法。 */
    public static int pendingCount(final Level level) {
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.blockEvents.size();
        }
        return -1;
    }

    /** 当前世界级队列中事件总数（与 {@link #pendingCount} 相同，语义更明确）。 */
    public static int liveEventCount(final Level level) {
        return pendingCount(level);
    }
}
