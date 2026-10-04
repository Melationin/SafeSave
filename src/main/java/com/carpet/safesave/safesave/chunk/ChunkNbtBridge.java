package com.carpet.safesave.safesave.chunk;


import static com.carpet.safesave.util.SafeSaveNbt.KEY_SAFE_SAVE;
import static com.carpet.safesave.util.Util.dimensionId;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.config.SafeSaveConfig;
import com.carpet.safesave.util.ChunkPosHelper;
import com.carpet.safesave.util.TagCompat;
import com.carpet.safesave.safesave.SafeSaveLevelState;
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
                                      final SafeSaveLevelState levelState) {
        long key = ChunkPosHelper.pack(chunkData.getIntOr("xPos", 0), chunkData.getIntOr("zPos", 0));
        if (SafeSaveConfig.of(level.getServer()).rebuildStartupOnly && levelState.startupRebuildComplete) {
            levelState.pendingChunks.remove(key);
            return;
        }
        CompoundTag safeSave = TagCompat.compound(chunkData, KEY_SAFE_SAVE).orElse(null);
        if (safeSave == null) {
            levelState.pendingChunks.remove(key);
            return;
        }
        SafeSaveStore.ChunkSnapshot snapshot = SafeSaveStore.loadChunkData(safeSave);
        if (snapshot == null || snapshot.isEmpty()) {
            levelState.pendingChunks.remove(key);
            return;
        }
        levelState.pendingChunks.put(key, snapshot);
        // NBT 解析可以跨过主线程结束启动重建的时刻，入队后再检查一次以避免残留。
        if (levelState.startupRebuildComplete && SafeSaveConfig.of(level.getServer()).rebuildStartupOnly) {
            levelState.pendingChunks.remove(key, snapshot);
            return;
        }
        if (DebugLog.DEBUG) {
            DebugLog.debug("{} {}: read {} block + {} fluid tick(s), {} block event(s) from chunk NBT",
                    dimensionId(level), ChunkPosHelper.unpack(key),
                    snapshot.blockTicks().size(), snapshot.fluidTicks().size(), snapshot.blockEvents().size());
        }
    }

    public static CompoundTag onChunkSerializing(final ServerLevel level, final ChunkAccess chunk,
                                                 final SafeSaveLevelState levelState) {
        if (!(chunk instanceof LevelChunk levelChunk)) {
            return null;
        }
        // 重建前先落原始快照，而不是尚未完整的实时容器。
        SafeSaveStore.ChunkSnapshot snapshot = ChunkSnapshotManager.forSave(level, levelChunk, levelState);
        if (snapshot == null) return null;

        CompoundTag tag = SafeSaveStore.saveChunkData(snapshot);
        return tag.isEmpty() ? null : tag;
    }
}
