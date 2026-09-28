package com.carpet.safesave.safesave;

import com.carpet.safesave.util.OrderSequence;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class SafeSaveLevelState {

    public SafeSaveStore.DimensionData savedDimension = new SafeSaveStore.DimensionData();

    public LongSet knownChunks = new LongOpenHashSet();

    public final Map<Long, SafeSaveStore.ChunkSnapshot> pendingChunks = new ConcurrentHashMap<>();

    public long nextBlockEventOrder;

    public long pistonOrderRebuiltAt = -1L;

    public boolean staleWarned;


    public boolean worldTickRunning;
    public int completedWorldTick = Integer.MIN_VALUE;
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

    public final OrderSequence pistonOrder = new OrderSequence();
    public final AtomicLong pistonOrderGeneration = new AtomicLong();

    public final AtomicInteger restoredTickCount = new AtomicInteger();
    public final AtomicInteger droppedTickCount = new AtomicInteger();
}
