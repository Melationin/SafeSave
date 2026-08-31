package com.carpet.safesave.safesave;

import com.carpet.safesave.safesave.region.ProtectedRegionState;
import com.carpet.safesave.util.OrderSequence;
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

    public final OrderSequence entityOrder = new OrderSequence();

    public final ProtectedRegionState protectedRegions = new ProtectedRegionState();
}
