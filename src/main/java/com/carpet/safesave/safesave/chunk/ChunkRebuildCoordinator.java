package com.carpet.safesave.safesave.chunk;

import static com.carpet.safesave.util.DimensionIds.dimensionId;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 新加载区块的统一重建协调。
 *
 * <p>每个<em>非冻结</em> tick 开头，对比 {@code LevelTicks.allContainers} 的当前就绪集合与
 * 上一个非冻结 tick 记录的就绪集合，把“已就绪且仍有待恢复快照”的区块统一重建：计划刻按区块
 * 恢复，方块事件汇总后按全局 order 一次入队。
 */
public final class ChunkRebuildCoordinator {

    private ChunkRebuildCoordinator() {
    }

    /**
     * 每个<em>非冻结</em> tick 开头统一重建新加载区块的计划刻与方块事件。
     *
     * <p>判断“新加载”的方式是对比 {@code LevelTicks.allContainers}：每个正常 tick 记录当时
     * 已就绪（已注册且已解包）的刻容器集合，下一次正常 tick 时，当前集合比上次多出的键就是
     * 本 tick 新加载的区块。实际消费集合是 {@code ready ∩ pendingChunks}，新加载但没有恢复数据的
     * 区块不会重建。
     *
     * <p>冻结期间刻意<em>不</em>更新 {@code knownChunks}：启动冻结或 {@code /tick freeze} 期间
     * 加载的区块，会在解冻后的第一个正常 tick 被统一视为新加载并恢复。
     *
     * @return 本 tick 实际重建的候选区块集合（可能为空），供实体顺序协调使用
     */
    public static Set<Long> rebuildNewChunks(final ServerLevel level,
                                             final SafeSaveSession session,
                                             final SafeSaveLevelState levelState) {
        if (!level.tickRateManager().runsNormally()) {
            return Set.of();
        }
        String dimension = dimensionId(level);
        LongSet ready = TickContainers.collectReadyChunks(level);

        // 第一个正常 tick 没有“上一次”可比较：视作已知集合为空，这样 prepareLevels 期间已经
        // 加载好的区块也会在此时统一重建。
        LongSet previous = levelState.knownChunks;
        // 诊断用：ready 相对 previous 多出的键（新加载）。
        LongOpenHashSet newKeys = new LongOpenHashSet(ready.size());
        newKeys.addAll(ready);
        newKeys.removeAll(previous);

        LongOpenHashSet candidates = new LongOpenHashSet();
        // 只处理“已就绪且处于待恢复映射”的区块。
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
            SafeSaveStore.ChunkSnapshot snapshot = levelState.pendingChunks.remove(key);
            if (snapshot == null) {
                continue;
            }
            ScheduledTickManager.restoreChunkTicks(level, key, snapshot, block, fluid, session, levelState);
            rebuilt++;
            blockEventsToRestore.addAll(snapshot.blockEvents());
        }
        // 同一个正常 tick 重建的所有区块，其方块事件一起按全局顺序合并回世界队列。
        if (!blockEventsToRestore.isEmpty()) {
            BlockEventManager.restoreChunkEvents(level, blockEventsToRestore, session, levelState);
        }

        levelState.knownChunks = ready;
        if (!candidates.isEmpty()) {
            DebugLog.info("{}: rebuild tick start - {} chunk(s) to rebuild ({} newly loaded); {} rebuilt, {} tick(s) restored so far, {} dropped",
                    dimension, candidates.size(), newKeys.size(), rebuilt,
                    session.restoredTickCount.get(), session.droppedTickCount.get());
        }
        // 返回“与上一非冻结 tick 相比新加载的区块”，供实体顺序按区块统一重排。
        return newKeys;
    }

    /**
     * 从待恢复映射中移除一个区块快照并冲销计数。
     */
    public static void removePending(final SafeSaveLevelState levelState, final long key,
                                     final SafeSaveSession session) {
        SafeSaveStore.ChunkSnapshot old = levelState.pendingChunks.remove(key);
        if (old != null) {
            session.loadedTickCount.addAndGet(-(old.blockTicks().size() + old.fluidTicks().size()));
            session.loadedBlockEventCount.addAndGet(-old.blockEvents().size());
        }
    }
}
