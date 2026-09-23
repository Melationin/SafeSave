package com.carpet.safesave.mixin.chunk;

import com.carpet.safesave.safesave.SafeSaveStore;
import com.carpet.safesave.safesave.chunk.ChunkSnapshotHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin implements ChunkSnapshotHolder {
    @Unique private long SS$lastSnapshotGameTime = Long.MIN_VALUE;
    @Unique private int SS$lastSnapshotServerTick = Integer.MIN_VALUE;
    @Unique private boolean SS$lastSnapshotAtTickEnd;
    @Unique private SafeSaveStore.ChunkSnapshot SS$lastSnapshot;

    @Override
    public long SS$lastSnapshotGameTime() {
        return this.SS$lastSnapshotGameTime;
    }

    @Override
    public int SS$lastSnapshotServerTick() {
        return this.SS$lastSnapshotServerTick;
    }

    @Override
    public boolean SS$lastSnapshotAtTickEnd() {
        return this.SS$lastSnapshotAtTickEnd;
    }

    @Override
    public SafeSaveStore.ChunkSnapshot SS$lastSnapshot() {
        return this.SS$lastSnapshot;
    }

    @Override
    public void SS$setLastSnapshot(long gameTime, int serverTick, boolean atTickEnd,
                                   SafeSaveStore.ChunkSnapshot snapshot) {
        this.SS$lastSnapshotGameTime = gameTime;
        this.SS$lastSnapshotServerTick = serverTick;
        this.SS$lastSnapshotAtTickEnd = atTickEnd;
        this.SS$lastSnapshot = snapshot;
    }
}
