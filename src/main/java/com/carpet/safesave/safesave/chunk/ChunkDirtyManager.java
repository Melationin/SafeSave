package com.carpet.safesave.safesave.chunk;

import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.scheduled.SafeTickContainer;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import com.carpet.safesave.util.ChunkPosHelper;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
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
                //? if <1.21.2 {
                /*chunk.setUnsaved(true);
                *///?} else {
                chunk.markUnsaved();
                //?}
            }
        }
        active.clear();
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
