package com.carpet.safesave.safesave;

import com.carpet.safesave.util.OrderSequence;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SafeSaveLevelState {

    public LongSet knownChunks = new LongOpenHashSet();

    // parse 线程写入，主线程在 tick 开头消费；保存路径只读
    public final Map<Long, SafeSaveStore.ChunkSnapshot> pendingChunks = new ConcurrentHashMap<>();

    public long nextBlockEventOrder;

    public long pistonOrderRebuiltAt = -1L;

    public boolean staleWarned;

    // 原版会在 ServerLevel.tick 内部保存/卸载区块。把这些写入推迟到服务端 tick 末的快照
    // 落到每个 LevelChunk 之后。
    public boolean worldTickRunning;
    public int completedWorldTick = Integer.MIN_VALUE;
    public boolean deferredUnloads;
    public boolean deferredFullSave;
    public boolean deferredFullSaveFlush;

    public final OrderSequence entityOrder = new OrderSequence();

    // 上次保存时的模拟层级 31/32。
    public Long2ByteOpenHashMap tickingChunksAtTickEnd = new Long2ByteOpenHashMap();
    public boolean tickingSnapshotAvailable;
    // 上次采集时的 gameTime，用于区分真实变化与卸载造成的假象。
    public long lastTickingCaptureGameTime = Long.MIN_VALUE;

    // 从上一份 side file 恢复出来的、仅用于加载的票据。
    public final Long2ByteOpenHashMap startupTickets = new Long2ByteOpenHashMap();
}
