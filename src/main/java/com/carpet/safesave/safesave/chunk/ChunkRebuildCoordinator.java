package com.carpet.safesave.safesave.chunk;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.util.ChunkPosHelper;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveStore;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.blockevent.SafeBlockEvent;
import com.carpet.safesave.safesave.blockentity.BlockEntityManager;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

import static com.carpet.safesave.util.Util.dimensionId;

public final class ChunkRebuildCoordinator {

    private ChunkRebuildCoordinator() {
    }

    // 调用方统一检查冻结状态；此处仅处理已接管且容器就绪的区块。
    public static LongSet rebuildNewChunks(final ServerLevel level,
                                             final SafeSaveLevelState levelState) {
        String dimension = dimensionId(level);
        Long2ObjectMap<?> blockContainers = TickContainers.blockContainers(level);
        Long2ObjectMap<?> fluidContainers = TickContainers.fluidContainers(level);
        LongOpenHashSet candidates = new LongOpenHashSet();
        synchronized (levelState.pendingChunks) {
            for (LongIterator iterator = levelState.pendingChunks.keySet().iterator(); iterator.hasNext();) {
                long key = iterator.nextLong();
                if (level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null
                        && TickContainers.isReady(blockContainers.get(key), fluidContainers.get(key))) {
                    candidates.add(key);
                }
            }
        }
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<SafeBlockEvent> blockEventsToRestore = new ArrayList<>();
        int rebuilt = 0;
        for (LongIterator iterator = candidates.iterator(); iterator.hasNext();) {
            long key = iterator.nextLong();
            Object block = blockContainers.get(key);
            Object fluid = fluidContainers.get(key);
            SafeSaveStore.ChunkSnapshot snapshot = levelState.pendingChunks.get(key);
            if (snapshot == null) {
                continue;
            }
            try {
                // 没有自定义快照的接管区块保留原版计划刻。
                if (!snapshot.isEmpty()) {
                    ScheduledTickManager.restoreChunkTicks(level, key, snapshot, block, fluid, levelState);
                }
            } catch (Exception e) {
                levelState.pendingChunks.remove(key);
                DebugLog.warn("{}: failed to restore scheduled ticks for chunk {}, dropping its snapshot: {}",
                        dimension, ChunkPosHelper.unpack(key), e.toString());
                continue;
            }
            levelState.pendingChunks.remove(key);
            rebuilt++;
            blockEventsToRestore.addAll(snapshot.blockEvents());
        }
        BlockEntityManager.restoreTickers(level, candidates);
        if (!blockEventsToRestore.isEmpty()) {
            BlockEventManager.restoreChunkEvents(level, blockEventsToRestore, levelState);
        }

        if (DebugLog.DEBUG) {
            DebugLog.debug("{}: rebuild tick start - {} chunk(s) to rebuild; {} rebuilt, {} tick(s) restored so far, {} dropped",
                    dimension, candidates.size(), rebuilt,
                    levelState.restoredTickCount.get(), levelState.droppedTickCount.get());
        }
        // 实体排序也仅处理本批接管的区块，不波及其他已加载区块。
        return candidates;
    }
}
