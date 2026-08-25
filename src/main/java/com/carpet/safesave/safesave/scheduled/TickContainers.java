package com.carpet.safesave.safesave.scheduled;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code LevelTicks} / {@code LevelChunkTicks} 容器访问的公共适配层。
 *
 * <p>safe-save 只通过鸭子接口 {@link TickContainerHolder} 与 {@link SafeTickContainer} 访问
 * 原版刻容器；本类统一这些强转、通配符容器获取与“就绪”判断，避免各服务重复实现。
 */
public final class TickContainers {
    private TickContainers() {
    }

    public static Long2ObjectMap<?> blockContainers(final ServerLevel level) {
        return ((TickContainerHolder) level.getBlockTicks()).SS$containers();
    }

    public static Long2ObjectMap<?> fluidContainers(final ServerLevel level) {
        return ((TickContainerHolder) level.getFluidTicks()).SS$containers();
    }

    /**
     * 一个区块的方块刻容器与流体刻容器同时可读、且都已解包时，才视为“就绪”。
     */
    public static boolean isReady(final Object blockContainer, final Object fluidContainer) {
        return blockContainer instanceof SafeTickContainer block
                && fluidContainer instanceof SafeTickContainer fluid
                && !block.SS$hasPendingTicks()
                && !fluid.SS$hasPendingTicks();
    }

    /**
     * 扫描该维度“已就绪”的刻容器集合。
     *
     * <p>就绪 = 已注册到 {@code LevelTicks.allContainers} 且已解包（无 {@code pendingTicks}）。
     * 仍未解包的区块不返回，协调层会留到后续 tick 重试。
     *
     * <p>该方法只做只读扫描，不消费 {@code pendingRestore}，由 {@link SafeSaveManager} 统一协调。
     */
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
