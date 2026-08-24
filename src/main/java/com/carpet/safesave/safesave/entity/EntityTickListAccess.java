package com.carpet.safesave.safesave.entity;

import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * 注入 {@code EntityTickList} 的鸭子接口：快照当前活跃实体，或按给定顺序整体重建活跃表。
 *
 * <p>{@code active} 是 {@code Int2ObjectLinkedOpenHashMap}（插入序即 tick 序），整体替换即可重排。
 */
public interface EntityTickListAccess {

    /** 当前活跃实体（顺序 = 当前 tick 序）。 */
    List<Entity> SS$snapshotActive();

    /** 按给定顺序重建活跃表（顺序 = 新 tick 序）。 */
    void SS$rebuildActive(List<Entity> ordered);
}
