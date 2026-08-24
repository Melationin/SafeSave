package com.carpet.safesave.safesave.blockevent;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveStore;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * 方块事件（block event）的保存与恢复管理。
 *
 * <p>原版将方块事件保存在 {@code ServerLevel.blockEvents} 中，并且<strong>根本不持久化</strong>——
 * 重启会悄然丢弃所有进行中的方块事件。本类把队列按取出顺序写入存储，加载时按原顺序重新排入。
 */
public final class BlockEventManager {

    /** 注入的权威存储；服务端加载后由 {@link #init} 设置。 */
    private static SafeSaveStore store;

    /** 供 {@code /safesave status} 使用的诊断数据。 */
    private static int restoredCount;
    private static int droppedCount;

    private BlockEventManager() {
    }

    /** 服务端加载时注入存储并重置会话状态。 */
    public static void init(final SafeSaveStore store) {
        BlockEventManager.store = store;
        reset();
    }

    public static void reset() {
        restoredCount = 0;
        droppedCount = 0;
    }

    /**
     * 将保存的方块事件重新排入 {@code ServerLevel.blockEvents}。
     *
     * <p>这里顺序就是一切：原版的容器是 {@code ObjectLinkedOpenHashSet}，通过
     * {@code removeFirst()} 取出，因此插入顺序<em>即</em>执行顺序。此时已排队的事件（世界刚刚构建，
     * 但为保险起见仍然处理）会被重新追加到<em>恢复的事件之后</em>，因为恢复的事件严格更早。
     *
     * <p>注意 {@code runBlockEvents} 会在返回前把暂时无法执行的事件放回 {@code blockEvents}，
     * 因此 {@code blockEventsToReschedule} 不会跨刻边界保留状态，无需保存。
     */
    public static void restore(final ServerLevel level, final SafeSaveStore.DimensionData data) {
        if (!data.blockEventsPendingRestore || data.blockEvents.isEmpty()) {
            return;
        }
        data.blockEventsPendingRestore = false;
        ObjectLinkedOpenHashSet<BlockEventData> queue = level.blockEvents;

        List<BlockEventData> existing = new ArrayList<>(queue);
        queue.clear();

        int restored = 0;
        for (SafeBlockEvent saved : data.blockEvents) {
            Identifier id = Identifier.tryParse(saved.blockId());
            // BLOCK 是 DefaultedRegistry：getValue() 遇到未知 id 会悄悄返回 AIR。
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                droppedCount++;
                DebugLog.warn("dropping block event for unknown block '{}' at ({},{},{})",
                        saved.blockId(), saved.x(), saved.y(), saved.z());
                continue;
            }
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            queue.add(new BlockEventData(new BlockPos(saved.x(), saved.y(), saved.z()),
                    block, saved.paramA(), saved.paramB()));
            restored++;
        }
        // 把原本已排队的事件重新追加到恢复的（更早的）事件之后
        queue.addAll(existing);

        restoredCount += restored;
        data.blockEvents.clear(); // 已消费
        DebugLog.info("{}: restored {} block event(s) in drain order ({} pre-existing kept behind them)",
                dimensionId(level), restored, existing.size());
    }

    /**
     * 原样捕获世界级的方块事件队列，保持取出顺序。
     *
     * <p>与计划刻不同，这里不需要按区块记账：队列位于 {@code ServerLevel} 上，完全在内存中，
     * 每次保存时直接覆盖即可。
     */
    public static void snapshot(final ServerLevel level, final SafeSaveStore.DimensionData data) {
        data.blockEvents.clear();
        for (BlockEventData event : level.blockEvents) {
            data.blockEvents.add(new SafeBlockEvent(
                    BuiltInRegistries.BLOCK.getKey(event.block()).toString(),
                    event.pos().getX(),
                    event.pos().getY(),
                    event.pos().getZ(),
                    event.paramA(),
                    event.paramB()));
        }
    }

    /** 世界刻日志行的调试辅助方法。 */
    public static int pendingCount(final Level level) {
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.blockEvents.size();
        }
        return -1;
    }

    public static int restoredCount() {
        return restoredCount;
    }

    public static int droppedCount() {
        return droppedCount;
    }

    private static String dimensionId(final ServerLevel level) {
        return level.dimension().identifier().toString();
    }
}
