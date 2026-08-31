package com.carpet.safesave.safesave.blockevent;


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

import static com.carpet.safesave.util.Util.dimensionId;


public final class BlockEventManager {

    public static final Comparator<SafeBlockEvent> COMPARE_BY_ORDER =
            Comparator.comparingLong(SafeBlockEvent::order);

    private BlockEventManager() {
    }

    // ------------------------------------------------------------ 运行时序号

    /*
      在  ServerLevel.blockEvent TAIL 调用
      用 containsKey 识别重复并保持原有序号。
     */
    public static void onBlockEvent(final ServerLevel level, final BlockEventData event) {
        SafeSaveLevelState levelState = SafeSaveLevelAccess.of(level);
        Map<BlockEventData, Long> levelOrders = levelState.blockEventOrders;
        if (levelOrders.containsKey(event)) {
            return;
        }
        levelOrders.put(event, levelState.nextBlockEventOrder++);
    }

    /*
     丢弃已不在队列中的序号记录
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

    public static int liveCount(final Level level) {
        return level instanceof ServerLevel serverLevel ? serverLevel.blockEvents.size() : -1;
    }



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

    public static List<SafeBlockEvent> snapshotChunkEvents(final ServerLevel level,
                                                           final long packedChunkPos,
                                                           final SafeSaveLevelState levelState) {
        return snapshotByChunk(level, levelState).getOrDefault(packedChunkPos, List.of());
    }

    // ------------------------------------------------------------ 恢复

    /*
     把一批方块事件按全局序号排序后重新入队。
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
            // 回填既有实时事件到恢复事件之后（恢复事件更老，必须排前面）。
            queue.addAll(existing);
        }
    }

    public static int pendingCount(final Level level) {
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.blockEvents.size();
        }
        return -1;
    }

    public static int liveEventCount(final Level level) {
        return pendingCount(level);
    }
}
