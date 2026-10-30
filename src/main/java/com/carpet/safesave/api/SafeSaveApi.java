package com.carpet.safesave.api;

import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.SafeSaveStore;
import com.carpet.safesave.safesave.blockentity.BlockEntityManager;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.blockevent.SafeBlockEvent;
import com.carpet.safesave.safesave.blockevent.BlockEventOrderHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import com.carpet.safesave.safesave.entity.EntityOrderManager;
import com.carpet.safesave.safesave.scheduled.SafeTickContainer;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import com.carpet.safesave.util.ChunkPosHelper;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Server-thread capture and frozen batch restoration for dependent mods. */
public final class SafeSaveApi {
    private SafeSaveApi() {}

    public static int version() { return 2; }

    /** Captures a selected batch, visiting the world block-event queue only once. */
    public static Long2ObjectMap<SafeSaveStore.ChunkSnapshot> captureChunks(ServerLevel level, LongCollection chunks) {
        checkThread(level);
        if (!SafeSaveManager.shouldRun(level.getServer())) throw new IllegalStateException("SafeSave must be enabled");
        LongSet selected = chunks instanceof LongSet set ? set : new LongOpenHashSet(chunks);
        if (selected.isEmpty()) return Long2ObjectMaps.emptyMap();
        Long2ObjectMap<List<SafeBlockEvent>> events = new Long2ObjectOpenHashMap<>();
        for (var event : level.blockEvents) {
            long key = ChunkPosHelper.pack(event.pos());
            if (!selected.contains(key)) continue;
            events.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new SafeBlockEvent(
                    BuiltInRegistries.BLOCK.getKey(event.block()).toString(),
                    event.pos().getX(), event.pos().getY(), event.pos().getZ(), event.paramA(), event.paramB(),
                    ((BlockEventOrderHolder) (Object) event).SS$blockEventOrder()));
        }
        Long2ObjectMap<SafeSaveStore.ChunkSnapshot> result = new Long2ObjectOpenHashMap<>(selected.size());
        for (long key : selected) {
            var chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) throw new IllegalStateException("Missing chunk: " + ChunkPosHelper.unpack(key));
            var ticks = ScheduledTickManager.snapshotChunkTicks(chunk.getBlockTicks(), chunk.getFluidTicks());
            if (ticks == null) throw new IllegalStateException("Chunk tick containers are not ready: " + chunk.getPos());
            List<SafeBlockEvent> queue = events.get(key);
            if (queue == null) queue = List.of();
            else queue.sort(BlockEventManager.COMPARE_BY_ORDER);
            result.put(key, new SafeSaveStore.ChunkSnapshot(ticks.blockTicks(), ticks.fluidTicks(), queue, level.getGameTime()));
        }
        return result;
    }

    public static SafeSaveStore.ChunkSnapshot captureChunk(ServerLevel level, LevelChunk chunk) {
        checkThread(level);
        if (!SafeSaveManager.shouldRun(level.getServer())) {
            throw new IllegalStateException("SafeSave must be enabled");
        }
        var ticks = ScheduledTickManager.snapshotChunkTicks(chunk.getBlockTicks(), chunk.getFluidTicks());
        if (ticks == null) throw new IllegalStateException("Chunk tick containers are not ready: " + chunk.getPos());
        return new SafeSaveStore.ChunkSnapshot(ticks.blockTicks(), ticks.fluidTicks(),
                BlockEventManager.snapshotChunkEvents(level, ChunkPosHelper.pack(chunk.getPos())), level.getGameTime());
    }

    /** Marks only this batch for the existing loaded-block-entity registration hook. */
    public static RestoreScope beginRestore(ServerLevel level, LongCollection chunks) {
        checkFrozen(level);
        SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
        LongSet added = new LongOpenHashSet();
        LongSet targets = new LongOpenHashSet(chunks);
        for (long key : targets) {
            if (state.chunksToRestore.add(key)) added.add(key);
            state.pendingChunks.remove(key);
            state.loadedChunkSnapshots.remove(key);
        }
        return () -> {
            checkThread(level);
            state.chunksToRestore.removeAll(added);
            for (long key : targets) {
                state.pendingChunks.remove(key);
                state.loadedChunkSnapshots.remove(key);
            }
        };
    }

    /**
     * All replacement chunks and entities must already be installed. Replaces this batch's
     * scheduled ticks/events, then delegates ticker and entity ordering to the normal managers.
     * No simulation tick is run, and unrelated pending chunks are not consumed.
     */
    public static void restoreChunks(ServerLevel level, Map<Long, SafeSaveStore.ChunkSnapshot> snapshots) {
        checkFrozen(level);
        LongSet targets = new LongOpenHashSet(snapshots.keySet());
        var blocks = TickContainers.blockContainers(level);
        var fluids = TickContainers.fluidContainers(level);
        for (long key : targets) {
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) == null
                    || !TickContainers.isReady(blocks.get(key), fluids.get(key))
                    || snapshots.get(key) == null) {
                throw new IllegalStateException("Replacement chunk is not ready: " + ChunkPosHelper.unpack(key));
            }
        }
        SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
        SafeSaveSession session = SafeSaveSession.of(level.getServer());
        long previousTime = session.serverGameTime;
        session.serverGameTime = level.getServer().overworld().getGameTime();
        try {
            level.blockEvents.removeIf(event -> targets.contains(ChunkPosHelper.pack(event.pos())));
            List<SafeBlockEvent> events = new ArrayList<>();
            for (long key : targets) {
                ((SafeTickContainer) blocks.get(key)).SS$replaceAll(List.of());
                ((SafeTickContainer) fluids.get(key)).SS$replaceAll(List.of());
                var snapshot = snapshots.get(key);
                ScheduledTickManager.restoreChunkTicks(level, key, snapshot, blocks.get(key), fluids.get(key), state);
                events.addAll(snapshot.blockEvents());
            }
            BlockEntityManager.restoreTickers(level, targets);
            BlockEventManager.restoreChunkEvents(level, events, state);
            EntityOrderManager.rebuildChunks(level, targets);
            for (long key : targets) state.pendingChunks.remove(key);
        } finally {
            session.serverGameTime = previousTime;
        }
    }

    private static void checkThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SafeSave API requires the server thread");
    }

    private static void checkFrozen(ServerLevel level) {
        checkThread(level);
        var rate = level.getServer().tickRateManager();
        if (!SafeSaveManager.shouldRun(level.getServer()) || !rate.isFrozen() || rate.isSteppingForward() || rate.isSprinting()) {
            throw new IllegalStateException("SafeSave must be enabled and the server must be frozen without stepping/sprinting");
        }
    }

    @FunctionalInterface
    public interface RestoreScope extends AutoCloseable {
        @Override void close();
    }
}
