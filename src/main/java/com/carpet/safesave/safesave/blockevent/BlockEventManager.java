package com.carpet.safesave.safesave.blockevent;

import static com.carpet.safesave.util.DimensionIds.dimensionId;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveStore;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方块事件（block event）的按区块保存与恢复管理。
 *
 * <p>方块事件随 {@link SafeSaveStore.ChunkSnapshot} 按区块保存。每条事件带全局
 * {@code order}，用于把分散在不同区块快照中的事件重新合并成世界级执行顺序。
 *
 * <p>原版的队列是 {@code ServerLevel.blockEvents}（{@code ObjectLinkedOpenHashSet}），
 * {@code runBlockEvents} 用 {@code removeFirst()} 按插入顺序取出，因此插入顺序<em>即</em>执行顺序。
 * 本类在 {@code ServerLevel.blockEvent} 插入时分配单调递增的全局序号，并在保存时按当前队列
 * 分组到各区块；恢复时把同一 tick 内所有被重建区块的事件按全局序号排序后统一重新入队。
 */
public final class BlockEventManager {

    /** 按 {@code order} 升序比较保存的方块事件。 */
    public static final Comparator<SafeBlockEvent> COMPARE_BY_ORDER =
            Comparator.comparingLong(SafeBlockEvent::order);

    /** 注入的权威存储；服务端加载后由 {@link #init} 设置。 */
    private static SafeSaveStore store;

    /** 供 {@code /safesave status} 使用的诊断数据。 */
    private static int restoredCount;
    private static int droppedCount;

    /** 每个世界中事件对象 -> 全局序号。仅在事件仍存活于队列时有效，保存时会清理已执行条目。 */
    private static final Map<ServerLevel, Map<BlockEventData, Long>> orders = new HashMap<>();
    /** 每个世界下一个待分配的全局序号。 */
    private static final Map<ServerLevel, Long> nextOrder = new HashMap<>();

    private BlockEventManager() {
    }

    // 仅用于隔离测试/调试时从外部校验序号计数
    static int liveOrderCount(final ServerLevel level) {
        Map<BlockEventData, Long> map = orders.get(level);
        return map == null ? 0 : map.size();
    }

    /** 服务端加载时注入存储并重置会话状态。 */
    public static void init(final SafeSaveStore store) {
        BlockEventManager.store = store;
        reset();
    }

    public static void reset() {
        restoredCount = 0;
        droppedCount = 0;
        orders.clear();
        nextOrder.clear();
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
        Map<BlockEventData, Long> levelOrders = orders.computeIfAbsent(level, k -> new HashMap<>());
        if (levelOrders.containsKey(event)) {
            return;
        }
        long order = nextOrder.computeIfAbsent(level, k -> 0L);
        levelOrders.put(event, order);
        nextOrder.put(level, order + 1);
    }

    /**
     * 丢弃已经不在 {@code ServerLevel.blockEvents} 中的序号记录，并为队列中缺少序号的事件
     * （例如来自其他 mod 直接操作队列）补发序号。每次保存前调用，避免长期运行内存无限增长。
     */
    private static void refreshOrders(final ServerLevel level) {
        Map<BlockEventData, Long> old = orders.get(level);
        Map<BlockEventData, Long> current = new HashMap<>();
        long next = nextOrder.getOrDefault(level, 0L);
        for (BlockEventData event : level.blockEvents) {
            Long order = old == null ? null : old.get(event);
            if (order == null) {
                order = next++;
            }
            current.put(event, order);
        }
        orders.put(level, current);
        nextOrder.put(level, Math.max(nextOrder.getOrDefault(level, 0L), next));
    }

    /** 当前事件队列中的事件总数（调试用）。 */
    public static int liveCount(final Level level) {
        return level instanceof ServerLevel serverLevel ? serverLevel.blockEvents.size() : -1;
    }

    // ------------------------------------------------------------ 快照

    /**
     * 将当前世界级队列按区块分组，并保留每条事件的全局 {@code order}。
     * 供区块 NBT 保存路径（{@code SafeSaveManager.onChunkSerializing}）按区块取用。
     */
    public static Map<Long, List<SafeBlockEvent>> snapshotByChunk(final ServerLevel level) {
        refreshOrders(level);
        Map<BlockEventData, Long> levelOrders = orders.get(level);
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

    /** 取单个区块的方块事件快照（用于卸载路径）。 */
    public static List<SafeBlockEvent> snapshotChunkEvents(final ServerLevel level, final long packedChunkPos) {
        return snapshotByChunk(level).getOrDefault(packedChunkPos, List.of());
    }

    // ------------------------------------------------------------ 恢复

    /**
     * 把一批（可能来自多个区块快照的）方块事件按全局序号排序后重新入队。
     *
     * <p>已有的实时事件会被保留在恢复事件之后：恢复的事件来自更早的保存，严格更老。
     * 队列是集合，因此与恢复事件重复的实时事件不会重复出现。
     */
    public static void restoreChunkEvents(final ServerLevel level, final List<SafeBlockEvent> saved) {
        if (saved == null || saved.isEmpty()) {
            return;
        }
        List<SafeBlockEvent> valid = new ArrayList<>(saved.size());
        for (SafeBlockEvent entry : saved) {
            Identifier id = Identifier.tryParse(entry.blockId());
            // BLOCK 是 DefaultedRegistry：getValue() 遇到未知 id 会悄悄返回 AIR。
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                droppedCount++;
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
        queue.clear();

        Map<BlockEventData, Long> levelOrders = orders.computeIfAbsent(level, k -> new HashMap<>());
        long next = nextOrder.getOrDefault(level, 0L);
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
        // 把原本已排队的事件重新追加到恢复的（更早的）事件之后
        queue.addAll(existing);

        nextOrder.put(level, next);
        restoredCount += restored;
        DebugLog.info("{}: restored {} block event(s) in global order ({} pre-existing kept behind them)",
                dimensionId(level), restored, existing.size());
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

    public static int restoredCount() {
        return restoredCount;
    }

    public static int droppedCount() {
        return droppedCount;
    }

}
