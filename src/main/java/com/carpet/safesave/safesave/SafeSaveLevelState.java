package com.carpet.safesave.safesave;

import com.carpet.safesave.util.OrderSequence;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.BlockEventData;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SafeSaveLevelState {

    public LongSet knownChunks = new LongOpenHashSet();

    // parse 线程写入，主线程在 tick 开头消费；保存路径只读
    public final Map<Long, SafeSaveStore.ChunkSnapshot> pendingChunks = new ConcurrentHashMap<>();

    public Map<BlockEventData, Long> blockEventOrders = new HashMap<>();
    public long nextBlockEventOrder;

    public long pistonOrderRebuiltAt = -1L;

    public boolean staleWarned;

    // Vanilla can save/unload chunks inside ServerLevel.tick. Defer those writes until
    // the server-tick-end snapshot has been placed on each LevelChunk.
    public boolean worldTickRunning;
    public int completedWorldTick = Integer.MIN_VALUE;
    public boolean deferredUnloads;
    public boolean deferredFullSave;
    public boolean deferredFullSaveFlush;

    public final OrderSequence entityOrder = new OrderSequence();

    // Simulation levels 31/32 as of the last save.
    public Long2ByteOpenHashMap tickingChunksAtTickEnd = new Long2ByteOpenHashMap();
    public boolean tickingSnapshotAvailable;

    // Loading-only tickets restored from the previous side-file save.
    public final Long2ByteOpenHashMap startupTickets = new Long2ByteOpenHashMap();
}
