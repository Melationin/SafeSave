package com.carpet.safesave.safesave.scheduled;


import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.util.ChunkPosHelper;
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


public final class ScheduledTickManager {

    private static final ChunkTickSnapshot EMPTY = new ChunkTickSnapshot(List.of(), List.of());

    private ScheduledTickManager() {
    }

    /*
     必须在任何区块解包之前恢复计数器，否则新调度的刻会与恢复的 subTickOrder 冲突。
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

    @SuppressWarnings("unchecked")
    public static void restoreChunkTicks(final ServerLevel level,
                                         final long packedChunkPos,
                                         final SafeSaveStore.ChunkSnapshot snapshot,
                                         final Object blockContainer,
                                         final Object fluidContainer,
                                         final SafeSaveSession session,
                                         final SafeSaveLevelState levelState) {
        warnIfStale(level, session, levelState);
        long currentGameTime = level.getGameTime();
        int keptBlock = applyTicks((TickContainerAccess<Block>) blockContainer, snapshot.blockTicks(),
                BuiltInRegistries.BLOCK, ((SafeTickContainer) blockContainer).SS$snapshotQueue(),
                snapshot.snapshotGameTime(), currentGameTime, session);
        int keptFluid = applyTicks((TickContainerAccess<Fluid>) fluidContainer, snapshot.fluidTicks(),
                BuiltInRegistries.FLUID, ((SafeTickContainer) fluidContainer).SS$snapshotQueue(),
                snapshot.snapshotGameTime(), currentGameTime, session);
        if (DebugLog.DEBUG) {
            DebugLog.debug("{} {}: restored {} block + {} fluid tick(s) (expired ticks rebased from gameTime {}; kept {} pre-existing)",
                    dimensionId(level), ChunkPosHelper.unpack(packedChunkPos),
                    snapshot.blockTicks().size(), snapshot.fluidTicks().size(),
                    snapshot.snapshotGameTime(), keptBlock + keptFluid);
        }
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
            long trigger = com.carpet.safesave.util.ResumeTime.rebase(
                    entry.triggerTick(), snapshotGameTime, currentGameTime);
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
    }

    public static ChunkTickSnapshot snapshotChunkTicks(final TickContainerAccess<Block> blockTicks,
                                                       final TickContainerAccess<Fluid> fluidTicks) {
        SafeTickContainer blockContainer = (SafeTickContainer) blockTicks;
        SafeTickContainer fluidContainer = (SafeTickContainer) fluidTicks;

        if (blockContainer.SS$hasPendingTicks() || fluidContainer.SS$hasPendingTicks()) {
            return null;
        }
        // 绝大多数区块一个计划刻都没有，此时无需取队列再排序。
        if (blockContainer.SS$isEmpty() && fluidContainer.SS$isEmpty()) {
            return EMPTY;
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
