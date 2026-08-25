package com.carpet.safesave.safesave.entity;

import com.carpet.safesave.debug.DebugLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体 tick 顺序的管理。
 *
 * <p>原版 {@code EntityTickList} 的 tick 序 = 实体进入列表的顺序，由区块晋级时机和异步反序列化
 * 完成顺序决定，重启后必然变化。本类给每个实体持久化单调序号（{@link EntityOrderHolder}，
 * 随实体 NBT 保存），并在恢复流程中按序号重建顺序。
 *
 * <p>时机：冻结期间只记录"从 NBT 加载的实体所在区块"；每个非冻结 tick 对 {@code pendingChunks}
 * 做区块内维护（提取该区块实体 → 按序号排序 → 重插列表尾部）。解冻后首个 tick 会一次性处理
 * 冻结期间积累的所有区块，等效于原全量重建。
 */
public final class EntityOrderManager {

    /** 分配给每个实体的单调递增 tick 序号。 */
    private static final java.util.concurrent.atomic.AtomicLong entityOrder = new java.util.concurrent.atomic.AtomicLong();

    /** 维度 → 从 NBT 加载了有序号实体的区块（等待区块内维护）。 */
    private static final Map<String, Set<Long>> pendingChunks = new HashMap<>();

    /** 无序号（本会话新生成）的实体排最后，保持相对顺序（List.sort 稳定）。 */
    private static final Comparator<Entity> ENTITY_ORDER = Comparator.comparingLong(
            e -> e instanceof EntityOrderHolder h && h.SS$entityOrder() != Long.MIN_VALUE
                    ? h.SS$entityOrder() : Long.MAX_VALUE);

    private EntityOrderManager() {
    }

    /** @return 下一个实体 tick 序号 */
    public static long nextOrder() {
        return entityOrder.getAndIncrement();
    }

    /** 确保新实体严格排在所有从磁盘恢复的序号之后。 */
    public static void observeOrder(final long restored) {
        entityOrder.accumulateAndGet(restored + 1L, Math::max);
    }

    /** 记录一个从 NBT 加载了有序号实体的区块，等待区块内维护。 */
    public static void markChunkDirty(final String dimension, final long packedChunkPos) {
        pendingChunks.computeIfAbsent(dimension, k -> new HashSet<>()).add(packedChunkPos);
    }

    /** 服务端加载时重置会话状态。 */
    public static void reset() {
        pendingChunks.clear();
    }

    /**
     * 在 {@code ServerLevel.tick} 的 HEAD 处调用。
     *
     * <p>冻结期间只记录；每个非冻结 tick 都对新加载/刚达到可刻状态的实体区块做区块内维护。
     * 冻结期间加载的区块在解冻后第一个正常 tick 被 {@code pendingChunks} 保留，因此无需单独的
     * “全量重建”分支——解冻后首个 tick 会一次性处理完这些区块，效果等同于全量重建。
     */
    public static void onLevelTickStart(final ServerLevel level) {
        if (!level.tickRateManager().runsNormally()) {
            return;
        }
        maintainChunks(level);
    }

    /**
     * 区块内维护：对每个待维护区块，提取该区块实体 → 按序号排序 → 重插列表尾部。
     *
     * <p>新加载区块的实体本来就是 append 到列表尾部的，重插仍在尾部——区块相对位置不变，
     * 只有区块内的顺序被修复。冻结期间加载的区块都会进入 {@code pendingChunks}，
     * 解冻后第一个非冻结 tick 会一并处理。
     */
    private static void maintainChunks(final ServerLevel level) {
        String dimension = dimensionId(level);
        Set<Long> chunks = pendingChunks.get(dimension);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        EntityTickList list = ((ServerLevelTickListAccess) level).SS$getEntityTickList();
        List<Entity> all = ((EntityTickListAccess) list).SS$snapshotActive();
        boolean changed = false;
        for (Long chunkKey : new ArrayList<>(chunks)) {
            List<Entity> inChunk = new ArrayList<>();
            for (Entity entity : all) {
                if (entity.chunkPosition().pack() == chunkKey) {
                    inChunk.add(entity);
                }
            }
            if (inChunk.size() >= 2) {
                inChunk.sort(ENTITY_ORDER);
                all.removeAll(inChunk);
                all.addAll(inChunk);
                changed = true;
            }
            chunks.remove(chunkKey);
        }
        if (changed) {
            ((EntityTickListAccess) list).SS$rebuildActive(all);
        }
        if (chunks.isEmpty()) {
            pendingChunks.remove(dimension);
        }
    }

    private static String dimensionId(final ServerLevel level) {
        return level.dimension().identifier().toString();
    }
}
