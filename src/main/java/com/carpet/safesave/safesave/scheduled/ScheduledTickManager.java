package com.carpet.safesave.safesave.scheduled;


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

import static com.carpet.safesave.util.Util.dimensionId;

/**
 * 计划刻的保存与恢复管理。
 *
 * <p>原版把刻以 {@code SavedTick(type, pos, delay, priority)} 存进区块 NBT，加载时按区块
 * 重新锚定 {@code delay} 并丢弃全局 {@code subTickOrder}，导致绝对触发时间漂移、跨区块顺序
 * 被摧毁。本类改用<em>绝对</em> {@code triggerTick} 与原始全局 {@code subTickOrder} 快照/恢复。
 */
public final class ScheduledTickManager {

    private ScheduledTickManager() {
    }

    /*
     * 在 MinecraftServer.prepareLevels的 HEAD 调用（世界与存储同时可用的最早时机）：
     * 必须在任何区块解包之前恢复计数器，否则新调度的刻会与恢复的 subTickOrder 冲突。
     */
    public static void restoreSubTickCount(final ServerLevel level, final SafeSaveStore.DimensionData data) {
        if (data.subTickCount >= 0L) {

            long current = level.subTickCount;
            // 绝不让计数器倒退：已发出的值必须保持唯一
            if (data.subTickCount > current) {
                level.subTickCount = data.subTickCount;
                DebugLog.info("{}: restored Level.subTickCount {} -> {}",
                        dimensionId(level), current, data.subTickCount);
            }
        }
    }

    /*
     * 恢复单个区块的计划刻（方块事件由协调层统一恢复）。
     *
     * 顺延规则：对已过期（code triggerTick < currentGameTime）的刻，按保存时剩余间隔
     * （triggerTick - snapshotGameTime）从当前世界时间重新计时；未过期的保持绝对时刻。
     *  snapshotGameTime == Long.MIN_VALUE 时跳过顺延（旧区块数据）。
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
                trigger = currentGameTime + remaining;
               // trigger = Math.max(trigger, currentGameTime + Math.max(remaining, 0L));
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

    public record ChunkTickSnapshot(List<SafeTick> blockTicks, List<SafeTick> fluidTicks) {
        public ChunkTickSnapshot {
            blockTicks = List.copyOf(blockTicks);
            fluidTicks = List.copyOf(fluidTicks);
        }

        public boolean isEmpty() {
            return this.blockTicks.isEmpty() && this.fluidTicks.isEmpty();
        }
    }

    public static ChunkTickSnapshot snapshotChunkTicks(final ServerLevel level,
                                                       final long packedChunkPos,
                                                       final TickContainerAccess<Block> blockTicks,
                                                       final TickContainerAccess<Fluid> fluidTicks) {
        SafeTickContainer blockContainer = (SafeTickContainer) blockTicks;
        SafeTickContainer fluidContainer = (SafeTickContainer) fluidTicks;

        if (blockContainer.SS$hasPendingTicks() || fluidContainer.SS$hasPendingTicks()) {
            return null;
        }

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
        // 排序仅为便于检查文件；恢复使用存储的字段。
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
