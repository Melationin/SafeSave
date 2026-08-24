package com.carpet.safesave.safesave;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

/**
 * 注入 {@code LevelTicks} 的鸭子接口，暴露已注册的每区块容器。
 *
 * <p>{@code LevelTicks.allContainers} 恰好是“此维度中已加载到至少 {@code FULL} 的每个区块”，
 * 以打包的 {@code ChunkPos} 为键——正是 safe-save 在保存时需要扫描的集合，而且键直接可用。
 */
public interface TickContainerHolder {
    /** 打包的 {@code ChunkPos} -> {@code LevelChunkTicks}（值也实现 {@link SafeTickContainer}）。 */
    Long2ObjectMap<?> SS$containers();
}
