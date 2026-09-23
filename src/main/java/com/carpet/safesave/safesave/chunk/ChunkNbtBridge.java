package com.carpet.safesave.safesave.chunk;


import static com.carpet.safesave.util.SafeSaveNbt.KEY_SAFE_SAVE;
import static com.carpet.safesave.util.Util.dimensionId;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.SafeSaveStore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ChunkNbtBridge {

    private ChunkNbtBridge() {
    }

    public static void onChunkTagRead(final ServerLevel level, final CompoundTag chunkData,
                                      final SafeSaveSession session, final SafeSaveLevelState levelState) {
        if (session.store == null) {
            return;
        }
        String dimension = dimensionId(level);
        long key = ChunkPos.pack(chunkData.getIntOr("xPos", 0), chunkData.getIntOr("zPos", 0));
        CompoundTag safeSave = chunkData.getCompound(KEY_SAFE_SAVE).orElse(null);
        if (safeSave == null) {
            ChunkRebuildCoordinator.removePending(levelState, key, session);
            return;
        }
        SafeSaveStore.ChunkSnapshot snapshot = SafeSaveStore.loadChunkData(safeSave);
        // 同一区块在同一会话内卸载→重载时，先冲销旧快照的计数，避免 /safesave status 重复累计。
        SafeSaveStore.ChunkSnapshot old = levelState.pendingChunks.get(key);
        if (old != null) {
            session.loadedTickCount.addAndGet(-(old.blockTicks().size() + old.fluidTicks().size()));
            session.loadedBlockEventCount.addAndGet(-old.blockEvents().size());
        }
        if (snapshot == null || snapshot.isEmpty()) {
            levelState.pendingChunks.remove(key);
            return;
        }
        levelState.pendingChunks.put(key, snapshot);
        session.loadedTickCount.addAndGet(snapshot.blockTicks().size() + snapshot.fluidTicks().size());
        session.loadedBlockEventCount.addAndGet(snapshot.blockEvents().size());
        DebugLog.info("{} {}: read {} block + {} fluid tick(s), {} block event(s) from chunk NBT",
                dimension, ChunkPos.unpack(key),
                snapshot.blockTicks().size(), snapshot.fluidTicks().size(), snapshot.blockEvents().size());
    }

    public static CompoundTag onChunkSerializing(final ServerLevel level, final ChunkAccess chunk,
                                                 final SafeSaveSession session,
                                                 final SafeSaveLevelState levelState) {
        if (session.store == null) {
            return null;
        }
        if (!(chunk instanceof LevelChunk levelChunk)) {
            return null;
        }
        // Before rebuild, persist the original snapshot rather than the incomplete live containers.
        SafeSaveStore.ChunkSnapshot snapshot = ChunkSnapshotManager.forSave(level, levelChunk, levelState);
        if (snapshot == null) return null;

        CompoundTag tag = SafeSaveStore.saveChunkData(snapshot);
        return tag.isEmpty() ? null : tag;
    }
}
