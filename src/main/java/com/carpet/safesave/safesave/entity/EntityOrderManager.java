package com.carpet.safesave.safesave.entity;

import com.carpet.safesave.debug.DebugLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 实体 tick 顺序的管理。
 *
 * <p>原版 {@code EntityTickList} 的 tick 序 = 实体进入列表的顺序，由区块晋级时机和异步反序列化
 * 完成顺序决定，重启后必然变化。本类给每个实体持久化单调序号（{@link EntityOrderHolder}，
 * 随实体 NBT 保存），并在恢复流程中按序号重建顺序。
 *
 * <p>时机：由 {@code SafeSaveManager.rebuildNewChunks} 统一驱动，每个非冻结 tick 只处理
 * “与上一非冻结 tick 相比新加载的区块”集合。把这些区块内的<em>所有</em>实体收集起来，
 * 按全局序号统一排序后重插列表尾部——既修复区块内顺序，也修复新加载区块之间的跨区块顺序。
 * 第一次 unfreeze 时已知集合为空，因此全部已加载区块都会作为“新加载”进入一次统一排序。
 */
public final class EntityOrderManager {

    /** 分配给每个实体的单调递增 tick 序号。 */
    private static final java.util.concurrent.atomic.AtomicLong entityOrder = new java.util.concurrent.atomic.AtomicLong();

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

    /** 服务端加载时重置会话状态。 */
    public static void reset() {
    }

    /**
     * 由 {@code SafeSaveManager.rebuildNewChunks} 在非冻结 tick 调用。
     *
     * <p>对 {@code newChunks}（本 tick 相对上一非冻结 tick 新加载的区块集合）做跨区块统一重排：
     * 收集这些区块内的所有实体 → 按全局序号排序 → 从原列表移除 → 整体重插列表尾部。
     *
     * @param newChunks 新加载区块集合；可为空。
     */
    public static void rebuildChunks(final ServerLevel level, final Collection<Long> newChunks) {
        if (newChunks == null || newChunks.isEmpty()) {
            return;
        }
        EntityTickList list = ((ServerLevelTickListAccess) level).SS$getEntityTickList();
        List<Entity> all = ((EntityTickListAccess) list).SS$snapshotActive();

        List<Entity> affected = new ArrayList<>();
        for (Entity entity : all) {
            if (newChunks.contains(entity.chunkPosition().pack())) {
                affected.add(entity);
            }
        }
        if (affected.size() < 2) {
            return;
        }
        affected.sort(ENTITY_ORDER);
        all.removeAll(affected);
        all.addAll(affected);
        ((EntityTickListAccess) list).SS$rebuildActive(all);
        DebugLog.info("{}: rebuilt cross-chunk tick order of {} entity(ies) in {} newly loaded chunk(s)",
                dimensionId(level), affected.size(), newChunks.size());
    }

    private static String dimensionId(final ServerLevel level) {
        return level.dimension().identifier().toString();
    }
}
