package com.carpet.safesave.safesave.chunk;

import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.SafeSaveStore;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.scheduled.SafeTickContainer;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ChunkSnapshotManager {
    private ChunkSnapshotManager() {}

    public static SafeSaveStore.ChunkSnapshot forSave(ServerLevel level, LevelChunk chunk,
                                                      SafeSaveLevelState state) {
        long key = chunk.getPos().pack();
        SafeSaveStore.ChunkSnapshot pending = state.pendingChunks.get(key);
        if (pending != null) return pending;
        // 世界 tick 进行中、或本服务器刻尚未结算时，现场队列不代表一个完整刻的状态；
        // 返回 null 交原版序列化现场队列
        if (!SafeSaveManager.canCaptureSnapshot(level)) return null;
        if (!(chunk.getBlockTicks() instanceof SafeTickContainer)
                || !(chunk.getFluidTicks() instanceof SafeTickContainer)) return null;
        ScheduledTickManager.ChunkTickSnapshot ticks = ScheduledTickManager.snapshotChunkTicks(
                chunk.getBlockTicks(), chunk.getFluidTicks());
        if (ticks == null) return null;
        return new SafeSaveStore.ChunkSnapshot(ticks.blockTicks(), ticks.fluidTicks(),
                BlockEventManager.snapshotChunkEvents(level, key), level.getGameTime());
    }
}
