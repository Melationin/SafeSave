package com.carpet.safesave.safesave.chunk;

import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.SafeSaveStore;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.blockevent.SafeBlockEvent;
import com.carpet.safesave.safesave.region.RegionLifecycle;
import com.carpet.safesave.safesave.scheduled.SafeTickContainer;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.Map;

/** Captures live tick/event state once per server tick, or on the chunk save path. */
public final class ChunkSnapshotManager {
    private ChunkSnapshotManager() {}

    public static void captureAtServerTickEnd(ServerLevel level, SafeSaveLevelState state,
                                              boolean allChunks) {
        Map<Long, List<SafeBlockEvent>> events = BlockEventManager.snapshotByChunk(level, state);
        for (long key : TickContainers.collectReadyChunks(level)) {
            if (!allChunks && !RegionLifecycle.isProtected(level, key)) continue;
            if (state.pendingChunks.containsKey(key) || state.protectedRegions.suspendedAt.contains(key)) continue;
            ChunkPos pos = ChunkPos.unpack(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk == null) {
                var holder = level.getChunkSource().chunkMap.visibleChunkMap.get(key);
                ChunkAccess latest = holder == null ? null : holder.getLatestChunk();
                if (latest instanceof LevelChunk loaded) chunk = loaded;
            }
            if (chunk != null) capture(level, chunk, state, events.getOrDefault(key, List.of()), true);
        }
    }

    /** Frozen state lives on the chunk while loaded, and in pendingChunks after NBT reload. */
    public static SafeSaveStore.ChunkSnapshot storedSnapshot(long key, LevelChunk chunk,
                                                              SafeSaveLevelState state) {
        if (chunk != null) {
            SafeSaveStore.ChunkSnapshot snapshot = ((ChunkSnapshotHolder) chunk).SS$lastSnapshot();
            if (snapshot != null) return snapshot;
        }
        return state.pendingChunks.get(key);
    }

    public static SafeSaveStore.ChunkSnapshot forSave(ServerLevel level, LevelChunk chunk,
                                                      SafeSaveLevelState state) {
        long key = chunk.getPos().pack();
        if (state.protectedRegions.suspendedAt.contains(key)) return storedSnapshot(key, chunk, state);
        SafeSaveStore.ChunkSnapshot pending = state.pendingChunks.get(key);
        if (pending != null) return pending;
        ChunkSnapshotHolder holder = (ChunkSnapshotHolder) chunk;
        if (sameTick(level, holder)) return holder.SS$lastSnapshot();
        if (!SafeSaveManager.canCaptureSnapshot(level)) return holder.SS$lastSnapshot();
        return capture(level, chunk, state, BlockEventManager.snapshotChunkEvents(level, key, state), false);
    }

    /** Returns null until both vanilla tick containers have been unpacked. */
    public static SafeSaveStore.ChunkSnapshot capture(ServerLevel level, LevelChunk chunk,
                                                       SafeSaveLevelState state,
                                                       List<SafeBlockEvent> events, boolean atTickEnd) {
        long key = chunk.getPos().pack();
        if (state.pendingChunks.containsKey(key) || state.protectedRegions.suspendedAt.contains(key)) return null;
        ChunkSnapshotHolder holder = (ChunkSnapshotHolder) chunk;
        long gameTime = level.getGameTime();
        int serverTick = level.getServer().getTickCount();
        // Game time can stand still under /tick freeze; server ticks still distinguish fresh snapshots.
        if (sameTick(level, holder) && (!atTickEnd || holder.SS$lastSnapshotAtTickEnd()))
            return holder.SS$lastSnapshot();
        if (!(chunk.getBlockTicks() instanceof SafeTickContainer)
                || !(chunk.getFluidTicks() instanceof SafeTickContainer)) return null;
        ScheduledTickManager.ChunkTickSnapshot ticks = ScheduledTickManager.snapshotChunkTicks(level, key,
                chunk.getBlockTicks(), chunk.getFluidTicks());
        if (ticks == null) return null;
        SafeSaveStore.ChunkSnapshot snapshot = new SafeSaveStore.ChunkSnapshot(
                ticks.blockTicks(), ticks.fluidTicks(), events, gameTime);
        holder.SS$setLastSnapshot(gameTime, serverTick, atTickEnd, snapshot);
        return snapshot;
    }

    private static boolean sameTick(ServerLevel level, ChunkSnapshotHolder holder) {
        return holder.SS$lastSnapshot() != null
                && holder.SS$lastSnapshotGameTime() == level.getGameTime()
                && holder.SS$lastSnapshotServerTick() == level.getServer().getTickCount();
    }
}
