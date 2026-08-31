package com.carpet.safesave.safesave.chunk;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.SafeSaveStore;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.blockevent.SafeBlockEvent;
import com.carpet.safesave.safesave.scheduled.SafeTickContainer;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.carpet.safesave.util.Util.dimensionId;

public final class ChunkRebuildCoordinator {

    private ChunkRebuildCoordinator() {
    }

    /**
     * 冻结期间刻意<em>不</em>更新 {@code knownChunks}：启动冻结或 {@code /tick freeze} 期间
     * 加载的区块，会在解冻后的第一个正常 tick 被统一视为新加载并恢复。
     */
    public static Set<Long> rebuildNewChunks(final ServerLevel level,
                                             final SafeSaveSession session,
                                             final SafeSaveLevelState levelState) {
        if (!level.tickRateManager().runsNormally()) {
            return Set.of();
        }
        String dimension = dimensionId(level);
        LongSet ready = TickContainers.collectReadyChunks(level);

        LongSet previous = levelState.knownChunks;
        LongOpenHashSet newKeys = new LongOpenHashSet(ready.size());
        newKeys.addAll(ready);
        newKeys.removeAll(previous);

        LongOpenHashSet candidates = new LongOpenHashSet();
        for (long boxed : ready) {
            if (levelState.pendingChunks.containsKey(boxed)) {
                candidates.add(boxed);
            }
        }

        Long2ObjectMap<?> blockContainers = TickContainers.blockContainers(level);
        Long2ObjectMap<?> fluidContainers = TickContainers.fluidContainers(level);
        List<SafeBlockEvent> blockEventsToRestore = new ArrayList<>();
        int rebuilt = 0;
        for (long key : candidates) {
            Object block = blockContainers.get(key);
            Object fluid = fluidContainers.get(key);
            if (!(block instanceof SafeTickContainer) || !(fluid instanceof SafeTickContainer)) {
                continue;
            }
            SafeSaveStore.ChunkSnapshot snapshot = levelState.pendingChunks.get(key);
            if (snapshot == null) {
                continue;
            }
            try {
                ScheduledTickManager.restoreChunkTicks(level, key, snapshot, block, fluid, session, levelState);
            } catch (Exception e) {
                levelState.pendingChunks.remove(key);
                DebugLog.warn("{}: failed to restore scheduled ticks for chunk {}, dropping its snapshot: {}",
                        dimension, ChunkPos.unpack(key), e.toString());
                continue;
            }
            // 恢复成功后才移除快照。
            levelState.pendingChunks.remove(key);
            rebuilt++;
            blockEventsToRestore.addAll(snapshot.blockEvents());
        }
        if (!blockEventsToRestore.isEmpty()) {
            BlockEventManager.restoreChunkEvents(level, blockEventsToRestore, session, levelState);
        }

        levelState.knownChunks = ready;
        if (!candidates.isEmpty()) {
            DebugLog.info("{}: rebuild tick start - {} chunk(s) to rebuild ({} newly loaded); {} rebuilt, {} tick(s) restored so far, {} dropped",
                    dimension, candidates.size(), newKeys.size(), rebuilt,
                    session.restoredTickCount.get(), session.droppedTickCount.get());
        }
        return newKeys;
    }

    public static void removePending(final SafeSaveLevelState levelState, final long key,
                                     final SafeSaveSession session) {
        SafeSaveStore.ChunkSnapshot old = levelState.pendingChunks.remove(key);
        if (old != null) {
            session.loadedTickCount.addAndGet(-(old.blockTicks().size() + old.fluidTicks().size()));
            session.loadedBlockEventCount.addAndGet(-old.blockEvents().size());
        }
    }
}
