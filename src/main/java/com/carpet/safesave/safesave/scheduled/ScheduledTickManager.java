package com.carpet.safesave.safesave.scheduled;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveStore;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
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
     * 扫描该维度“已就绪”的刻容器集合。
     *
     * <p>就绪 = 已注册到 {@code LevelTicks.allContainers} 且已解包（无 {@code pendingTicks}）。
     * 仍未解包的区块不返回，协调层会留到后续 tick 重试：Lithium 的 removeIf 只清“已入桶”刻的
     * allTicks 索引，而它会在构造时把 pendingTicks 的 (type,pos) 索引预先放入 allTicks——
     * 此刻恢复会残留这些索引，拦截之后相同 (type,pos) 的刻。
     *
     * <p>该方法只做只读扫描，不消费 {@code pendingRestore}，由 {@link SafeSaveManager} 统一协调。
     */
    public static Set<Long> collectReadyChunks(final ServerLevel level) {
        Long2ObjectMap<?> blockContainers = ((TickContainerHolder) level.getBlockTicks()).SS$containers();
        Long2ObjectMap<?> fluidContainers = ((TickContainerHolder) level.getFluidTicks()).SS$containers();

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
        return ready;
    }

    /**
     * 恢复单个区块的<em>计划刻</em>（不处理方块事件；方块事件由协调层 {@code SafeSaveManager} 统一恢复）。
     *
     * @return 被消费的区块快照；没有可恢复内容时为 {@code null}。
     */
    @SuppressWarnings("unchecked")
    public static SafeSaveStore.ChunkSnapshot restoreChunkTicks(final ServerLevel level,
                                                                final long packedChunkPos,
                                                                final Object blockContainer,
                                                                final Object fluidContainer) {
        String dimension = dimensionId(level);
        SafeSaveStore.ChunkSnapshot snapshot = store.take(dimension, packedChunkPos);
        if (snapshot == null) {
            return null;
        }
        warnIfStale(level);
        int keptBlock = applyTicks((TickContainerAccess<Block>) blockContainer, snapshot.blockTicks(),
                BuiltInRegistries.BLOCK, ((SafeTickContainer) blockContainer).SS$snapshotQueue());
        int keptFluid = applyTicks((TickContainerAccess<Fluid>) fluidContainer, snapshot.fluidTicks(),
                BuiltInRegistries.FLUID, ((SafeTickContainer) fluidContainer).SS$snapshotQueue());
        DebugLog.info("{} {}: restored {} block + {} fluid tick(s) with absolute timing (kept {} pre-existing)",
                dimension, ChunkPos.unpack(packedChunkPos),
                snapshot.blockTicks().size(), snapshot.fluidTicks().size(), keptBlock + keptFluid);
        return snapshot;
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

    /** 单个区块的计划刻快照：方块刻与流体刻分开保存。 */
    public record ChunkTickSnapshot(List<SafeTick> blockTicks, List<SafeTick> fluidTicks) {
        public ChunkTickSnapshot {
            blockTicks = List.copyOf(blockTicks);
            fluidTicks = List.copyOf(fluidTicks);
        }

        public boolean isEmpty() {
            return this.blockTicks.isEmpty() && this.fluidTicks.isEmpty();
        }
    }

    /**
     * 捕获单个区块的计划刻（方块刻与流体刻分开）。
     *
     * <p>协调层负责把返回结果与方块事件快照合并成 {@link SafeSaveStore.ChunkSnapshot} 并写入存储。
     *
     * @return 该区块当前已解包容器的绝对刻；容器未就绪或不可读时为 {@code null}
     */
    public static ChunkTickSnapshot snapshotChunkTicks(final ServerLevel level,
                                                       final long packedChunkPos,
                                                       final TickContainerAccess<Block> blockTicks,
                                                       final TickContainerAccess<Fluid> fluidTicks) {
        if (store == null) {
            return null;
        }
        SafeTickContainer blockContainer = (SafeTickContainer) blockTicks;
        SafeTickContainer fluidContainer = (SafeTickContainer) fluidTicks;

        // 仍持有 pendingTicks 的容器从未被解包，因此没有可捕获的绝对时间。
        if (blockContainer.SS$hasPendingTicks() || fluidContainer.SS$hasPendingTicks()) {
            return null;
        }

        // 容器不可读（如与第三方刻调度重写冲突）时返回 null：跳过该区块，保留存储中的旧条目，
        // 而不是以空快照覆盖——那会悄悄删除已保存的刻。
        List<?> blockQueue = blockContainer.SS$snapshotQueue();
        List<?> fluidQueue = fluidContainer.SS$snapshotQueue();
        if (blockQueue == null || fluidQueue == null) {
            return null;
        }
        return new ChunkTickSnapshot(toSafeTicks(blockQueue), toSafeTicks(fluidQueue));
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
     * 返回该维度所有已加载区块的“计划刻快照”映射。
     *
     * <p>只做只读快照，不写入 {@link SafeSaveStore}、不处理方块事件；协调层负责把方块事件合并后统一写入。
     *
     * @return 区块键 -> 该区块的计划刻快照；容器不可读/未就绪的区块不出现
     */
    public static Map<Long, ChunkTickSnapshot> snapshotLevelTicks(final ServerLevel level) {
        Long2ObjectMap<?> blockContainers = ((TickContainerHolder) level.getBlockTicks()).SS$containers();
        Long2ObjectMap<?> fluidContainers = ((TickContainerHolder) level.getFluidTicks()).SS$containers();

        Map<Long, ChunkTickSnapshot> ticksByChunk = new HashMap<>();
        LongIterator blockKeys = blockContainers.keySet().iterator();
        while (blockKeys.hasNext()) {
            long key = blockKeys.nextLong();
            Object block = blockContainers.get(key);
            Object fluid = fluidContainers.get(key);
            if (!(block instanceof SafeTickContainer) || !(fluid instanceof SafeTickContainer)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            TickContainerAccess<Block> blockAccess = (TickContainerAccess<Block>) block;
            @SuppressWarnings("unchecked")
            TickContainerAccess<Fluid> fluidAccess = (TickContainerAccess<Fluid>) fluid;
            ChunkTickSnapshot ticks = snapshotChunkTicks(level, key, blockAccess, fluidAccess);
            if (ticks != null) {
                ticksByChunk.put(key, ticks);
            }
        }
        return ticksByChunk;
    }

    private static String dimensionId(final ServerLevel level) {
        return level.dimension().identifier().toString();
    }
}
