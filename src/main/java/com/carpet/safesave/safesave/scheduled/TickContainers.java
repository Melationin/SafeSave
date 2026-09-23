package com.carpet.safesave.safesave.scheduled;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;

/**
 * 世界级计划刻容器的访问层。
 *
 * <p>{@code LevelTicks.allContainers} 恰好是「此维度中已加载到至少 {@code FULL} 的每个区块」，
 * 以打包的 {@code ChunkPos} 为键——正是 safe-save 保存时需要扫描的集合，而且键直接可用。
 *
 * <p>原版该字段为 private，这里通过 {@code safesave.classtweaker} 的
 * {@code accessible field net/minecraft/world/ticks/LevelTicks allContainers} 放开访问，
 * 不再需要鸭子接口与只为暴露该字段而存在的空壳 mixin。
 */
public final class TickContainers {
    private TickContainers() {
    }

    public static Long2ObjectMap<?> blockContainers(final ServerLevel level) {
        return level.getBlockTicks().allContainers;
    }

    public static Long2ObjectMap<?> fluidContainers(final ServerLevel level) {
        return level.getFluidTicks().allContainers;
    }


    public static boolean isReady(final Object blockContainer, final Object fluidContainer) {
        return blockContainer instanceof SafeTickContainer block
                && fluidContainer instanceof SafeTickContainer fluid
                && !block.SS$hasPendingTicks()
                && !fluid.SS$hasPendingTicks();
    }

    public static LongSet collectReadyChunks(final ServerLevel level) {
        Long2ObjectMap<?> blockContainers = blockContainers(level);
        Long2ObjectMap<?> fluidContainers = fluidContainers(level);

        LongOpenHashSet ready = new LongOpenHashSet(blockContainers.size());
        for (Long2ObjectMap.Entry<?> entry : blockContainers.long2ObjectEntrySet()) {
            long chunkKey = entry.getLongKey();
            if (isReady(entry.getValue(), fluidContainers.get(chunkKey))) {
                ready.add(chunkKey);
            }
        }
        return ready;
    }
}
