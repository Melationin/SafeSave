package com.carpet.safesave.safesave.chunk;

import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.scheduled.SafeTickContainer;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import com.carpet.safesave.util.ChunkPosHelper;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ChunkDirtyManager {
    private ChunkDirtyManager() {}

    public static void captureAtTickStart(final ServerLevel level, final SafeSaveLevelState state) {
        state.activeSnapshotChunksAtTickStart.clear();
        collectActiveChunks(level, state.activeSnapshotChunksAtTickStart);
    }

    public static void markAtTickEnd(final ServerLevel level, final SafeSaveLevelState state) {
        LongSet active = state.activeSnapshotChunksAtTickStart;
        collectActiveChunks(level, active);

        for (long key : active) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk != null) {
                markDirty(chunk);
            }
        }
        active.clear();
    }

    public static void expandDirtyChunks(final ServerLevel level) {
        // 先收集保存入口处的脏区块，新增的邻区块不再参与扩展。
        LongSet dirty = new LongOpenHashSet();
        for (var holder : level.getChunkSource().chunkMap.visibleChunkMap.values()) {
            var chunk = holder.getLatestChunk();
            if (chunk instanceof LevelChunk && chunk.isUnsaved()) {
                dirty.add(ChunkPosHelper.pack(chunk.getPos()));
            }
        }
        for (long key : dirty) {
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    LevelChunk neighbor = level.getChunkSource().getChunkNow(x + dx, z + dz);
                    if (neighbor != null) markDirty(neighbor);
                }
            }
        }
    }

    private static void markDirty(final LevelChunk chunk) {
        //? if <1.21.2 {
        /*chunk.setUnsaved(true);
        *///?} else {
        chunk.markUnsaved();
        //?}
    }

    private static void collectActiveChunks(final ServerLevel level, final LongSet active) {
        collectTicks(TickContainers.blockContainers(level), active);
        collectTicks(TickContainers.fluidContainers(level), active);
        for (BlockEventData event : level.blockEvents) {
            active.add(ChunkPosHelper.pack(event.pos()));
        }
    }

    private static void collectTicks(final Long2ObjectMap<?> containers, final LongSet active) {
        for (Long2ObjectMap.Entry<?> entry : containers.long2ObjectEntrySet()) {
            if (entry.getValue() instanceof SafeTickContainer ticks && !ticks.SS$isEmpty()) {
                active.add(entry.getLongKey());
            }
        }
    }
}
