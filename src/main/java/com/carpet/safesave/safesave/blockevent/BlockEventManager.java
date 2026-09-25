package com.carpet.safesave.safesave.blockevent;


import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.util.ChunkPosHelper;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.carpet.safesave.util.Util.dimensionId;


public final class BlockEventManager {

    public static final Comparator<SafeBlockEvent> COMPARE_BY_ORDER =
            Comparator.comparingLong(SafeBlockEvent::order);

    private BlockEventManager() {
    }

    /*
      在 ServerLevel.blockEvent 的入队点调用。同一刻内的重复入队会命中集合去重，
      被丢弃的那个新实例不会进入队列，序号因此只在首次入队时生效。
     */
    public static void assignOrder(final ServerLevel level, final BlockEventData event) {
        ((BlockEventOrderHolder) (Object) event)
                .SS$assignBlockEventOrder(SafeSaveLevelAccess.of(level).nextBlockEventOrder++);
    }

    public static List<SafeBlockEvent> snapshotChunkEvents(final ServerLevel level,
                                                           final long packedChunkPos) {
        List<SafeBlockEvent> events = new ArrayList<>();
        for (BlockEventData event : level.blockEvents) {
            if (ChunkPosHelper.pack(event.pos()) != packedChunkPos) {
                continue;
            }
            events.add(new SafeBlockEvent(
                    BuiltInRegistries.BLOCK.getKey(event.block()).toString(),
                    event.pos().getX(),
                    event.pos().getY(),
                    event.pos().getZ(),
                    event.paramA(),
                    event.paramB(),
                    ((BlockEventOrderHolder) (Object) event).SS$blockEventOrder()));
        }
        events.sort(COMPARE_BY_ORDER);
        return events;
    }

    public static void restoreChunkEvents(final ServerLevel level,
                                          final List<SafeBlockEvent> saved,
                                          final SafeSaveLevelState levelState) {
        if (saved == null || saved.isEmpty()) {
            return;
        }
        List<SafeBlockEvent> valid = new ArrayList<>(saved.size());
        for (SafeBlockEvent entry : saved) {
            Identifier id = Identifier.tryParse(entry.blockId());
            // BLOCK 是 DefaultedRegistry：getValue() 遇到未知 id 会悄悄返回 AIR。
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
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

            long next = levelState.nextBlockEventOrder;
            int restored = 0;
            for (SafeBlockEvent entry : valid) {
                // 1.21.2 renamed Registry#get(ResourceLocation) to Registry#getValue(ResourceLocation).
                //? if <1.21.2 {
                /*Block block = BuiltInRegistries.BLOCK.get(Identifier.tryParse(entry.blockId()));
                *///?} else {
                Block block = BuiltInRegistries.BLOCK.getValue(Identifier.tryParse(entry.blockId()));
                //?}
                BlockEventData event = new BlockEventData(
                        new BlockPos(entry.x(), entry.y(), entry.z()),
                        block, entry.paramA(), entry.paramB());
                // 实时队列里已有同一事件时沿用它的序号：恢复实例先入队，集合会丢掉后加的旧实例。
                long order = entry.order();
                for (BlockEventData live : existing) {
                    if (live.equals(event)) {
                        order = ((BlockEventOrderHolder) (Object) live).SS$blockEventOrder();
                        break;
                    }
                }
                ((BlockEventOrderHolder) (Object) event).SS$assignBlockEventOrder(order);
                queue.add(event);
                if (order >= next) {
                    next = order + 1;
                }
                restored++;
            }
            levelState.nextBlockEventOrder = next;
            if (DebugLog.DEBUG) {
                DebugLog.debug("{}: restored {} block event(s) in global order ({} pre-existing kept behind them)",
                        dimensionId(level), restored, existing.size());
            }
        } finally {
            // 回填既有实时事件到恢复事件之后（恢复事件更老，必须排前面）。
            queue.addAll(existing);
        }
    }
}
