package com.carpet.safesave.safesave.scheduled;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveStore;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.blockevent.SafeBlockEvent;
import com.carpet.safesave.rules.SafeSaveRules;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickContainerAccess;
import net.minecraft.world.ticks.TickPriority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划刻（scheduled tick）的保存与恢复管理。
 *
 * <p>原版将刻以 {@code SavedTick(type, pos, int delay, priority)} 存在区块 NBT 中，加载时按区块
 * 重新锚定 {@code delay} 并丢弃全局 {@code subTickOrder}，导致绝对触发时间漂移、跨区块顺序被摧毁。
 * 本类用<em>绝对</em> {@code triggerTick} 与原始全局 {@code subTickOrder} 快照/恢复每个区块的刻。
 */
public final class ScheduledTickManager {

    /** 注入的权威存储；服务端加载后由 {@link #init} 设置。 */
    private static SafeSaveStore store;

    /**
     * 每个维度上次在<em>非冻结</em>世界刻开始时所观察到的已注册刻容器集合。
     *
     * <p>每个正常 tick 都会把它替换为当刻的 {@code allContainers} 并集；下一次 tick 时，出现在
     * 当前集合却不在该集合中的区块就是“新加载/刚达到 FULL 的区块”，对它们统一重建计划刻。
     * 冻结期间刻意不更新：解冻后，冻结期间加载的所有区块都会被视为新加载并得到恢复。
     */
    private static final Map<String, Set<Long>> knownChunks = new HashMap<>();
    /** 已警告过的维度，确保消息每个会话只出现一次。 */
    private static final Set<String> staleWarned = new HashSet<>();

    /** 供 {@code /safesave status} 使用的诊断数据。 */
    private static int restoredCount;
    private static int droppedCount;

    private ScheduledTickManager() {
    }

    /** 服务端加载时注入存储并重置会话状态。 */
    public static void init(final SafeSaveStore store) {
        ScheduledTickManager.store = store;
        reset();
    }

    public static void reset() {
        knownChunks.clear();
        staleWarned.clear();
        restoredCount = 0;
        droppedCount = 0;
    }

    public static int restoredCount() {
        return restoredCount;
    }

    public static int droppedCount() {
        return droppedCount;
    }

    /**
     * 在 {@code MinecraftServer.prepareLevels} 的 HEAD 处调用（世界与存储同时可用的最早时机）：
     * 恢复 {@code Level.subTickCount}。在任何区块解包之前恢复计数器，可以保证新调度的刻不会与
     * 恢复的 {@code subTickOrder} 值冲突。
     */
    public static void restoreSubTickCount(final ServerLevel level, final SafeSaveStore.DimensionData data) {
        if (data.subTickCount >= 0L) {

            long current = level.subTickCount;
            // 绝不让计数器倒退：已经发出的值必须保持唯一
            if (data.subTickCount > current) {
                level.subTickCount = data.subTickCount;
                DebugLog.info("{}: restored Level.subTickCount {} -> {}",
                        dimensionId(level), current, data.subTickCount);
            }
        }
    }

    /**
     * 在 {@code ServerLevel.tick} 的 HEAD 处调用。每个<em>非冻结</em> tick 开始统一重建新加载区块的刻。
     *
     * <p>架构：保存时计划刻已按区块快照到 {@link SafeSaveStore}；恢复不再在 {@code LevelChunk.unpackTicks}
     * 里逐个改写，而是等区块真正开始参与世界刻后，在本 tick 的起点统一替换。判断“新加载”的方式是对比
     * {@code LevelTicks.allContainers}：每个正常 tick 记录当时已注册的刻容器集合，下一次正常 tick 时，
     * 当前集合比上次多出的键就是本 tick 新加载（达到 {@code FULL}）的区块。
     *
     * <p>冻结期间刻意<em>不</em>更新 {@link #knownChunks}：启动冻结或 {@code /tick freeze} 期间加载的区块，
     * 会在解冻后的第一个正常 tick 被统一视为新加载并恢复，保证恢复数据不会在冻结时被部分消费。
     *
     * <p>仍未解包（{@code pendingTicks} 未清空）的容器被跳过，留到后续正常 tick：
     * Lithium 的 removeIf 只清“已入桶”刻的 allTicks 索引，而它会在构造时把 pendingTicks 的
     * (type,pos) 索引预先放入 allTicks——此刻恢复会残留这些索引，拦截之后相同 (type,pos) 的刻。
     * unpack 完成后 pendingTicks 已清空、刻已进桶，removeIf 即可完整清空，恢复不再丢刻。
     */
    public static void onLevelTickStart(final ServerLevel level) {
        if (!SafeSaveRules.safeSave || store == null) {
            return;
        }
        String dimension = dimensionId(level);

        // 冻结时 ServerLevel.tick 仍会进入，但 runsNormally() 为 false，区块加载照常发生。
        // 跳过重建且不更新 knownChunks，使这些区块在解冻后的第一个正常 tick 被当作新加载处理。
        if (!level.tickRateManager().runsNormally()) {
            return;
        }

        Long2ObjectMap<?> blockContainers = ((TickContainerHolder) level.getBlockTicks()).SS$containers();
        Long2ObjectMap<?> fluidContainers = ((TickContainerHolder) level.getFluidTicks()).SS$containers();

        // “就绪”集合 = 已注册且已解包（无 pendingTicks）的刻容器。仍在 pendingTicks 的区块
        // 不进入 knownChunks，因此后续 tick 会再次把它当作新加载，直到真正可重建。
        Set<Long> ready = new HashSet<>();
        LongIterator blockKeys = blockContainers.keySet().iterator();
        while (blockKeys.hasNext()) {
            long key = blockKeys.nextLong();
            Object block = blockContainers.get(key);
            Object fluid = fluidContainers.get(key);
            if (block instanceof SafeTickContainer blockContainer
                    && fluid instanceof SafeTickContainer fluidContainer
                    && !blockContainer.SS$hasPendingTicks()
                    && !fluidContainer.SS$hasPendingTicks()) {
                ready.add(key);
            }
        }

        // 第一个正常 tick 没有“上一次”可比较：视作已知集合为空，这样 prepareLevels 期间已经
        // 加载好的区块也会在此时统一重建。
        Set<Long> previous = knownChunks.get(dimension);
        if (previous == null) {
            previous = Set.of();
        }
        // 实际候选 = ready ∩ pendingRestore。诊断用 newKeys 记录 ready 相对 previous 多出的键（新加载）。
        Set<Long> newKeys = new HashSet<>(ready);
        newKeys.removeAll(previous);

        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimension);
        Set<Long> candidates = new HashSet<>();
        if (data != null) {
            // 只处理“已就绪且处于待恢复队列”的区块。newKeys 负责识别新加载，
            // 而 ready ∩ pendingRestore 额外兜底“卸载→重载发生在两个正常 tick 之间、未从 previous 消失”的边界。
            // 不直接用 newKeys 去取：新加载但不在 pendingRestore 中的区块没有恢复数据，不应调用 store.take。
            for (Long boxed : ready) {
                if (data.pendingRestore.contains(boxed)) {
                    candidates.add(boxed);
                }
            }
        }

        int rebuilt = 0;
        List<SafeBlockEvent> blockEventsToRestore = new ArrayList<>();
        for (Long boxed : candidates) {
            long key = boxed;
            Object block = blockContainers.get(key);
            Object fluid = fluidContainers.get(key);
            SafeSaveStore.ChunkSnapshot snapshot = restoreInto(level, key, block, fluid,
                    ((SafeTickContainer) block).SS$snapshotQueue(),
                    ((SafeTickContainer) fluid).SS$snapshotQueue());
            if (snapshot != null) {
                rebuilt++;
                blockEventsToRestore.addAll(snapshot.blockEvents());
            }
        }
        // 同一个正常 tick 重建的所有区块，其方块事件一起按全局顺序合并回世界队列。
        if (!blockEventsToRestore.isEmpty()) {
            BlockEventManager.restoreChunkEvents(level, blockEventsToRestore);
        }

        // 只记录“就绪”的区块；尚未解包的区块会在下个正常 tick 重新进入 newKeys。
        knownChunks.put(dimension, ready);
        if (!candidates.isEmpty()) {
            DebugLog.info("{}: rebuild tick start - {} chunk(s) to rebuild ({} newly loaded); {} rebuilt, {} tick(s) restored so far, {} dropped",
                    dimension, candidates.size(), newKeys.size(), rebuilt, restoredCount, droppedCount);
        }
    }

    /** 当此世界仍有待处理（未应用）的恢复条目时为 {@code true}。 */
    public static int pendingChunkCount(final ServerLevel level) {
        if (store == null) {
            return 0;
        }
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimensionId(level));
        return data == null ? 0 : data.pendingRestore.size();
    }

    /**
     * @param blockContainer 该区块的 {@code LevelChunkTicks<Block>}
     * @param fluidContainer 该区块的 {@code LevelChunkTicks<Fluid>}
     * @return 被恢复的区块快照；没有可恢复内容时为 {@code null}。
     *         快照已被消费（从 {@code pendingRestore}/{@code chunks} 移除），
     *         调用方负责处理其中的方块事件。
     */
    @SuppressWarnings("unchecked")
    private static SafeSaveStore.ChunkSnapshot restoreInto(final ServerLevel level,
                                      final long packedChunkPos,
                                      final Object blockContainer,
                                      final Object fluidContainer,
                                      final List<?> keepBlockTicks,
                                      final List<?> keepFluidTicks) {
        String dimension = dimensionId(level);
        SafeSaveStore.ChunkSnapshot snapshot = store.take(dimension, packedChunkPos);
        if (snapshot == null) {
            return null;
        }
        warnIfStale(level);
        int keptBlock = applyTicks((TickContainerAccess<Block>) blockContainer, snapshot.blockTicks(),
                BuiltInRegistries.BLOCK, keepBlockTicks);
        int keptFluid = applyTicks((TickContainerAccess<Fluid>) fluidContainer, snapshot.fluidTicks(),
                BuiltInRegistries.FLUID, keepFluidTicks);
        DebugLog.info("{} {}: restored {} block + {} fluid tick(s) with absolute timing (kept {} pre-existing)",
                dimension, ChunkPos.unpack(packedChunkPos),
                snapshot.blockTicks().size(), snapshot.fluidTicks().size(), keptBlock + keptFluid);
        return snapshot;
    }

    /**
     * 纯诊断用途。记录的 {@code gameTime} <strong>从不</strong>用于重新锚定任何东西——
     * 触发时间按构造就是绝对的。但如果它与实时的 {@code gameTime} 不一致，说明旁置文件与
     * {@code level.dat} 脱节（典型情况：某个会话关闭了规则，世界继续推进而此文件没有），
     * 那么每个恢复的刻都会相应地过期。这一点值得明确指出。
     */
    private static void warnIfStale(final ServerLevel level) {
        String dimension = dimensionId(level);
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimension);
        if (data == null || data.gameTime == Long.MIN_VALUE) {
            return;
        }
        long live = level.getGameTime();
        if (data.gameTime != live && staleWarned.add(dimension)) {
            DebugLog.warn("{}: side file was written at gameTime={} but the world resumed at gameTime={} "
                            + "(difference {}). Restored ticks keep their absolute trigger times and will therefore "
                            + "fire immediately. This usually means 'safeSave' was off for a previous session.",
                    dimension, data.gameTime, live, live - data.gameTime);
        }
    }

    /**
     * @param keep 清空后需要重新加入的既有刻；可为 {@code null}
     * @return 重新加入的既有刻数量
     */
    @SuppressWarnings("unchecked")
    private static <T> int applyTicks(final TickContainerAccess<T> container,
                                      final List<SafeTick> saved,
                                      final Registry<T> registry,
                                      final List<?> keep) {
        List<ScheduledTick<T>> ticks = new ArrayList<>(saved.size());
        for (SafeTick entry : saved) {
            Identifier id = Identifier.tryParse(entry.typeId());
            // BLOCK/FLUID 是 DefaultedRegistry：getValue() 遇到未知 id 会悄悄返回 AIR/EMPTY，
            // 因此必须显式检查注册表成员资格。
            if (id == null || !registry.containsKey(id)) {
                droppedCount++;
                DebugLog.warn("dropping scheduled tick for unknown type '{}' at ({},{},{})",
                        entry.typeId(), entry.x(), entry.y(), entry.z());
                continue;
            }
            T type = registry.getValue(id);
            ticks.add(new ScheduledTick<>(
                    type,
                    new BlockPos(entry.x(), entry.y(), entry.z()),
                    entry.triggerTick(),
                    TickPriority.byValue(entry.priority()),
                    entry.subTickOrder()));
        }
        ((SafeTickContainer) container).SS$replaceAll(ticks);
        restoredCount += ticks.size();

        int kept = 0;
        if (keep != null) {
            for (Object raw : keep) {
                if (raw instanceof ScheduledTick<?> tick) {
                    // schedule() 会按 (type, pos) 去重，因此恢复已覆盖的既有刻会在这里被丢弃而非重复。
                    container.schedule((ScheduledTick<T>) tick);
                    kept++;
                }
            }
        }
        return kept;
    }

    /**
     * 将区块的刻捕获到存储中。从 {@code ServerLevel.unload} 的 HEAD 处调用，
     * 即刻容器刚从世界中注销之前。
     */
    public static void snapshotChunk(final ServerLevel level, final LevelChunk chunk) {
        if (!SafeSaveRules.safeSave || store == null) {
            return;
        }
        // 保护这个强转：ChunkAccess.getBlockTicks() 只在真正的 LevelChunk 上才是 LevelChunkTicks。
        // ImposterProtoChunk 在写入被禁用时会返回 BlackholeTickAccess.emptyContainer()，
        // 它没有实现 SafeTickContainer，盲目强转会抛出 ClassCastException。
        // snapshotLevel() 已经这样防护；这条路径之前没有。
        if (!(chunk.getBlockTicks() instanceof SafeTickContainer)
                || !(chunk.getFluidTicks() instanceof SafeTickContainer)) {
            return;
        }
        snapshot(level, chunk.getPos().pack(), chunk.getBlockTicks(), chunk.getFluidTicks(), true);
    }

    /**
     * @param addToPendingRestore 卸载路径传 {@code true}：快照写入后立即把该区块放入恢复队列，
     *                            使同一会话内重新加载的区块也会在后续正常 tick 开头被统一重建；
     *                            全量保存路径传 {@code false}，避免把本次保存快照误当成“待恢复”。
     */
    private static void snapshot(final ServerLevel level,
                                 final long packedChunkPos,
                                 final TickContainerAccess<Block> blockTicks,
                                 final TickContainerAccess<Fluid> fluidTicks,
                                 final boolean addToPendingRestore) {
        SafeTickContainer blockContainer = (SafeTickContainer) blockTicks;
        SafeTickContainer fluidContainer = (SafeTickContainer) fluidTicks;

        // 仍持有 pendingTicks 的容器从未被解包，因此没有可捕获的绝对时间。
        // 存储中已有的该区块条目原样保留：它来自该区块*确实*在刻的会话，而绝对时间永不漂移。
        if (blockContainer.SS$hasPendingTicks() || fluidContainer.SS$hasPendingTicks()) {
            return;
        }

        // 仍在恢复队列中 => 容器当前持有的是原版重新锚定的刻，正是我们打算丢弃的数据。
        // 用它们覆盖条目会悄悄让整个功能失效。这在实践中很重要：
        // MC 在启动后不久就会执行一次 flush 保存，可能赶在恢复之前。
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimensionId(level));
        if (data != null && data.pendingRestore.contains(packedChunkPos)) {
            return;
        }

        // 注意：如果这个区块不是 SafeTickContainer（ImposterProtoChunk 等），上面已经 return；
        // 方块事件快照依赖 LevelChunk 对应的世界级队列，与容器类型无关，因此放在容器检查之后。

        // 容器不可读（如与第三方刻调度重写冲突）时返回 null：跳过该区块，保留存储中的旧条目，
        // 而不是以空快照覆盖——那会悄悄删除已保存的刻。
        List<?> blockQueue = blockContainer.SS$snapshotQueue();
        List<?> fluidQueue = fluidContainer.SS$snapshotQueue();
        if (blockQueue == null || fluidQueue == null) {
            return;
        }
        List<SafeTick> block = toSafeTicks(blockQueue);
        List<SafeTick> fluid = toSafeTicks(fluidQueue);
        List<SafeBlockEvent> chunkEvents = BlockEventManager.snapshotChunkEvents(level, packedChunkPos);
        store.put(dimensionId(level), packedChunkPos, new SafeSaveStore.ChunkSnapshot(block, fluid, chunkEvents));
        if (addToPendingRestore) {
            // 同会话重载场景：区块卸载后马上又加载，pendingRestore 保证它的快照会被
            // 后续正常 tick 的“新加载区块统一重建”再次消费。全量保存路径不加。
            data = store.dimension(dimensionId(level));
            data.pendingRestore.add(packedChunkPos);
        }
    }

    private static List<SafeTick> toSafeTicks(final List<?> scheduledTicks) {
        List<SafeTick> out = new ArrayList<>(scheduledTicks.size());
        for (Object raw : scheduledTicks) {
            if (!(raw instanceof ScheduledTick<?> tick)) {
                continue;
            }
            out.add(new SafeTick(
                    DebugLog.typeId(tick.type()),
                    tick.pos().getX(),
                    tick.pos().getY(),
                    tick.pos().getZ(),
                    tick.triggerTick(),
                    tick.priority().getValue(),
                    tick.subTickOrder()));
        }
        // 按取出顺序排序，纯粹为了让文件便于检查；恢复使用存储的字段。
        out.sort((a, b) -> {
            int cmp = Long.compare(a.triggerTick(), b.triggerTick());
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(a.priority(), b.priority());
            return cmp != 0 ? cmp : Long.compare(a.subTickOrder(), b.subTickOrder());
        });
        return out;
    }

    /**
     * 快照该维度所有已加载区块的刻。供 {@code MinecraftServer.saveAllChunks} HEAD 的保存流程调用。
     *
     * @return 快照的区块数
     */
    public static int snapshotLevel(final ServerLevel level) {
        Long2ObjectMap<?> blockContainers = ((TickContainerHolder) level.getBlockTicks()).SS$containers();
        Long2ObjectMap<?> fluidContainers = ((TickContainerHolder) level.getFluidTicks()).SS$containers();

        Set<Long> keys = new HashSet<>();
        LongIterator blockKeys = blockContainers.keySet().iterator();
        while (blockKeys.hasNext()) {
            keys.add(blockKeys.nextLong());
        }
        LongIterator fluidKeys = fluidContainers.keySet().iterator();
        while (fluidKeys.hasNext()) {
            keys.add(fluidKeys.nextLong());
        }

        int count = 0;
        for (Long boxed : keys) {
            long key = boxed;
            Object block = blockContainers.get(key);
            Object fluid = fluidContainers.get(key);
            if (!(block instanceof SafeTickContainer) || !(fluid instanceof SafeTickContainer)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            TickContainerAccess<Block> blockAccess = (TickContainerAccess<Block>) block;
            @SuppressWarnings("unchecked")
            TickContainerAccess<Fluid> fluidAccess = (TickContainerAccess<Fluid>) fluid;
            snapshot(level, key, blockAccess, fluidAccess, false);
            count++;
        }
        return count;
    }

    private static String dimensionId(final ServerLevel level) {
        return level.dimension().identifier().toString();
    }
}
