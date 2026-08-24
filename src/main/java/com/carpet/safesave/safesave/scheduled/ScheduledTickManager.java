package com.carpet.safesave.safesave.scheduled;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveStore;
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
import java.util.HashSet;
import java.util.List;
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

    /** 已执行过一次性首个世界刻恢复的维度。 */
    private static final Set<String> firstTickDone = new HashSet<>();
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
        firstTickDone.clear();
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
     * 在 {@code ServerLevel.tick} 的 HEAD 处调用。执行每个维度的一次性恢复扫描。
     *
     * <p>在 {@code prepareLevels} 期间准备好的区块已由 {@code unpackTicks} 钩子处理；此扫描捕获的是
     * 已加载到 {@code FULL} 但尚未开始方块刻的区块，它们的刻仍停留在 {@code pendingTicks} 中。
     * 在这里应用绝对数据严格优于原版：刻以其真实的触发时间进入队列，只需等待区块变为可刻即可。
     */
    public static void onLevelTickStart(final ServerLevel level) {
        if (store == null) {
            return;
        }
        String dimension = dimensionId(level);
        if (!firstTickDone.add(dimension)) {
            return;
        }
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimension);
        if (data == null || data.pendingRestore.isEmpty()) {
            return;
        }

        Long2ObjectMap<?> blockContainers = ((TickContainerHolder) level.getBlockTicks()).SS$containers();
        Long2ObjectMap<?> fluidContainers = ((TickContainerHolder) level.getFluidTicks()).SS$containers();
        int swept = 0;
        for (Long boxed : new ArrayList<>(data.pendingRestore)) {
            long key = boxed;
            Object block = blockContainers.get(key);
            Object fluid = fluidContainers.get(key);
            // 不在 allContainers 中 => 区块未加载到 FULL；稍后由 unpackTicks 钩子处理。
            if (!(block instanceof SafeTickContainer) || !(fluid instanceof SafeTickContainer)) {
                continue;
            }
            if (restoreInto(level, key, block, fluid,
                    ((SafeTickContainer) block).SS$snapshotQueue(),
                    ((SafeTickContainer) fluid).SS$snapshotQueue())) {
                swept++;
            }
        }
        DebugLog.info("{}: first world tick - swept {} already-loaded chunk(s); {} tick(s) restored so far, {} dropped",
                dimension, swept, restoredCount, droppedCount);
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
     * @return 当此区块仍有未应用的恢复条目时为 {@code true}，即其刻容器中的当前内容即将被丢弃。
     */
    public static boolean hasPendingRestore(final LevelChunk chunk) {
        if (!SafeSaveRules.safeSave || store == null) {
            return false;
        }
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimensionId(level));
        return data != null && data.pendingRestore.contains(chunk.getPos().pack());
    }

    /**
     * 用保存的绝对刻替换区块的计划刻。从 {@code LevelChunk.unpackTicks} 的 TAIL 和首个世界刻扫描中调用。
     *
     * <p>存储条目会被<em>消费</em>，因此绝不会被应用两次；如果区块之后卸载，
     * {@link #snapshotChunk} 会放回一个新条目。
     *
     * @param keepBlockTicks 在 {@code unpackTicks} 运行<em>之前</em>就已排队的 {@code ScheduledTick}，
     *                       即本会话期间区块处于 {@code FULL} 时真正新调度的刻。它们会在恢复之后被重新加入，
     *                       使本功能绝不会丢失原版本会保留的刻。可为 {@code null}。
     * @return 当有内容被恢复时为 {@code true}
     */
    public static boolean restoreChunk(final LevelChunk chunk,
                                       final List<?> keepBlockTicks,
                                       final List<?> keepFluidTicks) {
        if (!SafeSaveRules.safeSave || store == null) {
            return false;
        }
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        return restoreInto(level, chunk.getPos().pack(), chunk.getBlockTicks(), chunk.getFluidTicks(),
                keepBlockTicks, keepFluidTicks);
    }

    /**
     * @param blockContainer 该区块的 {@code LevelChunkTicks<Block>}
     * @param fluidContainer 该区块的 {@code LevelChunkTicks<Fluid>}
     */
    @SuppressWarnings("unchecked")
    private static boolean restoreInto(final ServerLevel level,
                                      final long packedChunkPos,
                                      final Object blockContainer,
                                      final Object fluidContainer,
                                      final List<?> keepBlockTicks,
                                      final List<?> keepFluidTicks) {
        String dimension = dimensionId(level);
        SafeSaveStore.ChunkSnapshot snapshot = store.take(dimension, packedChunkPos);
        if (snapshot == null) {
            return false;
        }
        warnIfStale(level);
        int keptBlock = applyTicks((TickContainerAccess<Block>) blockContainer, snapshot.blockTicks(),
                BuiltInRegistries.BLOCK, keepBlockTicks);
        int keptFluid = applyTicks((TickContainerAccess<Fluid>) fluidContainer, snapshot.fluidTicks(),
                BuiltInRegistries.FLUID, keepFluidTicks);
        DebugLog.info("{} {}: restored {} block + {} fluid tick(s) with absolute timing (kept {} pre-existing)",
                dimension, ChunkPos.unpack(packedChunkPos),
                snapshot.blockTicks().size(), snapshot.fluidTicks().size(), keptBlock + keptFluid);
        return true;
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
        snapshot(level, chunk.getPos().pack(), chunk.getBlockTicks(), chunk.getFluidTicks());
    }

    private static void snapshot(final ServerLevel level,
                                 final long packedChunkPos,
                                 final TickContainerAccess<Block> blockTicks,
                                 final TickContainerAccess<Fluid> fluidTicks) {
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

        List<SafeTick> block = toSafeTicks(blockContainer.SS$snapshotQueue());
        List<SafeTick> fluid = toSafeTicks(fluidContainer.SS$snapshotQueue());
        store.put(dimensionId(level), packedChunkPos, new SafeSaveStore.ChunkSnapshot(block, fluid));
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
            snapshot(level, key, blockAccess, fluidAccess);
            count++;
        }
        return count;
    }

    private static String dimensionId(final ServerLevel level) {
        return level.dimension().identifier().toString();
    }
}
