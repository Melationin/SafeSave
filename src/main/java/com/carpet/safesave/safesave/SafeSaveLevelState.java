package com.carpet.safesave.safesave;

import com.carpet.safesave.util.OrderSequence;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.concurrent.atomic.AtomicInteger;

public final class SafeSaveLevelState {

    public SafeSaveStore.DimensionData savedDimension = new SafeSaveStore.DimensionData();

    // NBT 读取只暂存数据，注册区块时才决定是否接管。
    public final Long2ObjectMap<SafeSaveStore.ChunkSnapshot> loadedChunkSnapshots =
            Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());

    public final Long2ObjectMap<SafeSaveStore.ChunkSnapshot> pendingChunks =
            Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());

    // 主线程维护：首次解冻后仍需接管的新加载区块，按维度保存区块坐标。
    public final LongSet chunksToRestore = new LongOpenHashSet();

    public long nextBlockEventOrder;

    public boolean staleWarned;


    public boolean worldTickRunning;
    public int completedWorldTick = Integer.MIN_VALUE;
    public final LongSet activeSnapshotChunksAtTickStart = new LongOpenHashSet();
    public boolean deferredUnloads;
    public boolean deferredFullSave;
    public boolean deferredFullSaveFlush;

    public final OrderSequence entityOrder = new OrderSequence();

    public Long2ByteOpenHashMap tickingChunksAtTickEnd = new Long2ByteOpenHashMap();
    public boolean tickingSnapshotAvailable;
    // 上次采集时的 gameTime，用于区分真实变化与卸载造成的假象。
    public long lastTickingCaptureGameTime = Long.MIN_VALUE;

    public final Long2ByteOpenHashMap startupTickets = new Long2ByteOpenHashMap();

    public boolean startupBarrierPending;
    public boolean startupTicketsHeld;

    public final OrderSequence blockEntityOrder = new OrderSequence();

    public final AtomicInteger restoredTickCount = new AtomicInteger();
    public final AtomicInteger droppedTickCount = new AtomicInteger();
}
