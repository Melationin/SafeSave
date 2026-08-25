package com.carpet.safesave.safesave.scheduled;

import static com.carpet.safesave.util.DimensionIds.dimensionId;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.SafeSaveStore;
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
import java.util.List;

/**
 * 计划刻（scheduled tick）的保存与恢复管理（纯服务，无静态可变状态）。
 *
 * <p>原版将刻以 {@code SavedTick(type, pos, int delay, priority)} 存在区块 NBT 中，加载时按区块
 * 重新锚定 {@code delay} 并丢弃全局 {@code subTickOrder}，导致绝对触发时间漂移、跨区块顺序被摧毁。
 * 本类用<em>绝对</em> {@code triggerTick} 与原始全局 {@code subTickOrder} 快照/恢复每个区块的刻。
 *
 * <p>会话级计数（restored/dropped）在 {@link SafeSaveSession}；维度级警告位在
 * {@link SafeSaveLevelState}。
 */
public final class ScheduledTickManager {

    private ScheduledTickManager() {
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
     * 恢复单个区块的<em>计划刻</em>（不处理方块事件；方块事件由协调层统一恢复）。
     *
     * <p>绝对触发时刻与卸载期间继续走的世界时间之间的漂移在这里修正：对已过期
     * （{@code triggerTick < currentGameTime}）的计划刻，按保存时的剩余间隔
     * （{@code triggerTick - snapshotGameTime}）从当前世界时间重新计时；未过期的保持绝对时刻，
     * 保证重启零漂移。{@code snapshotGameTime == Long.MIN_VALUE} 时跳过顺延（旧区块数据）。
     *
     * @param snapshot 该区块待恢复的 safe-save 快照（已由协调层从待恢复映射中取出）
     */
    @SuppressWarnings("unchecked")
    public static void restoreChunkTicks(final ServerLevel level,
                                         final long packedChunkPos,
                                         final SafeSaveStore.ChunkSnapshot snapshot,
                                         final Object blockContainer,
                                         final Object fluidContainer,
                                         final SafeSaveSession session,
                                         final SafeSaveLevelState levelState) {
        String dimension = dimensionId(level);
        warnIfStale(level, session, levelState);
        long currentGameTime = level.getGameTime();
        int keptBlock = applyTicks((TickContainerAccess<Block>) blockContainer, snapshot.blockTicks(),
                BuiltInRegistries.BLOCK, ((SafeTickContainer) blockContainer).SS$snapshotQueue(),
                snapshot.snapshotGameTime(), currentGameTime, session);
        int keptFluid = applyTicks((TickContainerAccess<Fluid>) fluidContainer, snapshot.fluidTicks(),
                BuiltInRegistries.FLUID, ((SafeTickContainer) fluidContainer).SS$snapshotQueue(),
                snapshot.snapshotGameTime(), currentGameTime, session);
        DebugLog.info("{} {}: restored {} block + {} fluid tick(s) (expired ticks rebased from gameTime {}; kept {} pre-existing)",
                dimension, ChunkPos.unpack(packedChunkPos),
                snapshot.blockTicks().size(), snapshot.fluidTicks().size(),
                snapshot.snapshotGameTime(), keptBlock + keptFluid);
    }

    /**
     * 纯诊断用途。旁置文件里的 {@code gameTime} <strong>从不</strong>用于重新锚定任何东西——
     * 重锚定由每个区块快照自带的 {@code snapshotGameTime} 完成。如果这里的值与实时
     * {@code gameTime} 不一致，说明旁置文件与 {@code level.dat} 脱节（典型情况：某个会话
     * 关闭了规则，世界继续推进而此文件没有），这有助于解释为什么恢复的刻被大量顺延。
     */
    private static void warnIfStale(final ServerLevel level, final SafeSaveSession session,
                                    final SafeSaveLevelState levelState) {
        String dimension = dimensionId(level);
        SafeSaveStore.DimensionData data = session.store.dimensionOrNull(dimension);
        if (data == null || data.gameTime == Long.MIN_VALUE || levelState.staleWarned) {
            return;
        }
        long live = level.getGameTime();
        if (data.gameTime != live) {
            levelState.staleWarned = true;
            DebugLog.warn("{}: side file was written at gameTime={} but the world resumed at gameTime={} "
                            + "(difference {}). Chunk snapshots are self-timestamped and expired ticks will be "
                            + "rebased on load; this usually means 'safeSave' was off for a previous session.",
                    dimension, data.gameTime, live, live - data.gameTime);
        }
    }

    /**
     * @param snapshotGameTime 保存快照时的世界时间；{@code Long.MIN_VALUE} = 缺失，保持绝对触发时刻
     * @param currentGameTime 当前世界时间，用于对已过期刻做原版式顺延重锚定
     * @param keep 清空后需要重新加入的既有刻；可为 {@code null}
     * @return 重新加入的既有刻数量
     */
    @SuppressWarnings("unchecked")
    private static <T> int applyTicks(final TickContainerAccess<T> container,
                                      final List<SafeTick> saved,
                                      final Registry<T> registry,
                                      final List<?> keep,
                                      final long snapshotGameTime,
                                      final long currentGameTime,
                                      final SafeSaveSession session) {
        List<ScheduledTick<T>> ticks = new ArrayList<>(saved.size());
        for (SafeTick entry : saved) {
            Identifier id = Identifier.tryParse(entry.typeId());
            // BLOCK/FLUID 是 DefaultedRegistry：getValue() 遇到未知 id 会悄悄返回 AIR/EMPTY，
            // 因此必须显式检查注册表成员资格。
            if (id == null || !registry.containsKey(id)) {
                session.droppedTickCount.incrementAndGet();
                DebugLog.warn("dropping scheduled tick for unknown type '{}' at ({},{},{})",
                        entry.typeId(), entry.x(), entry.y(), entry.z());
                continue;
            }
            T type = registry.getValue(id);
            long trigger = entry.triggerTick();
            if (snapshotGameTime != Long.MIN_VALUE) {
                long remaining = trigger - snapshotGameTime;
                trigger = Math.max(trigger, currentGameTime + Math.max(remaining, 0L));
            }
            ticks.add(new ScheduledTick<>(
                    type,
                    new BlockPos(entry.x(), entry.y(), entry.z()),
                    trigger,
                    TickPriority.byValue(entry.priority()),
                    entry.subTickOrder()));
        }
        ((SafeTickContainer) container).SS$replaceAll(ticks);
        session.restoredTickCount.addAndGet(ticks.size());

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
     * 把区块容器里<em>已经过期</em>（{@code triggerTick < currentGameTime}）的计划刻按冻结起点
     * 顺延重锚定，供 ProtectedRegion 解冻时调用。
     *
     * <p>与 {@link #applyTicks} 的 snapshotGameTime 公式同源：region 冻结期间全局 gameTime
     * 继续走，已排队的绝对触发时刻会过期；解冻时按 {@code triggerTick - frozenAt} 的剩余间隔
     * 从当前时间重新计时，未来刻不动。只重建出现过期刻的容器（{@code SS$replaceAll}），
     * 且只有在确有变更时才替换。
     */
    @SuppressWarnings("unchecked")
    public static void rebaseOverdueTicks(final ServerLevel level,
                                          final long packedChunkPos,
                                          final Object blockContainer,
                                          final Object fluidContainer,
                                          final long frozenAt,
                                          final long currentGameTime) {
        if (frozenAt < 0L) {
            return;
        }
        rebaseContainer((SafeTickContainer) blockContainer, frozenAt, currentGameTime);
        rebaseContainer((SafeTickContainer) fluidContainer, frozenAt, currentGameTime);
    }

    @SuppressWarnings("unchecked")
    private static void rebaseContainer(final SafeTickContainer container,
                                        final long frozenAt,
                                        final long currentGameTime) {
        if (container == null || container.SS$hasPendingTicks()) {
            return;
        }
        List<?> queue = container.SS$snapshotQueue();
        if (queue == null) {
            return;
        }
        boolean anyOverdue = false;
        for (Object raw : queue) {
            if (raw instanceof ScheduledTick<?> tick && tick.triggerTick() < currentGameTime) {
                anyOverdue = true;
                break;
            }
        }
        if (!anyOverdue) {
            return;
        }
        List<ScheduledTick<?>> rebased = new ArrayList<>(queue.size());
        for (Object raw : queue) {
            if (!(raw instanceof ScheduledTick<?> tick)) {
                continue;
            }
            long trigger = tick.triggerTick();
            if (trigger < currentGameTime) {
                long remaining = trigger - frozenAt;
                trigger = currentGameTime + Math.max(remaining, 0L);
            }
            rebased.add(new ScheduledTick<>(
                    tick.type(),
                    tick.pos(),
                    trigger,
                    tick.priority(),
                    tick.subTickOrder()));
        }
        container.SS$replaceAll(rebased);
    }

    /**
     * 捕获单个区块的计划刻（方块刻与流体刻分开）。
     *
     * <p>协调层负责把返回结果与方块事件快照合并成 {@link SafeSaveStore.ChunkSnapshot}，
     * 供区块 NBT 保存路径写入。
     *
     * @return 该区块当前已解包容器的绝对刻；容器未就绪或不可读时为 {@code null}
     */
    public static ChunkTickSnapshot snapshotChunkTicks(final ServerLevel level,
                                                       final long packedChunkPos,
                                                       final TickContainerAccess<Block> blockTicks,
                                                       final TickContainerAccess<Fluid> fluidTicks) {
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
}
