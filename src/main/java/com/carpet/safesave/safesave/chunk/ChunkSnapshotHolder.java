package com.carpet.safesave.safesave.chunk;

import com.carpet.safesave.safesave.SafeSaveStore;

/** Transient snapshot for one loaded chunk; never serialized as a Java field. */
public interface ChunkSnapshotHolder {
    long SS$lastSnapshotGameTime();
    int SS$lastSnapshotServerTick();
    boolean SS$lastSnapshotAtTickEnd();
    SafeSaveStore.ChunkSnapshot SS$lastSnapshot();
    void SS$setLastSnapshot(long gameTime, int serverTick, boolean atTickEnd,
                            SafeSaveStore.ChunkSnapshot snapshot);
}
