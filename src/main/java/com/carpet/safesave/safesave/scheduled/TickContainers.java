package com.carpet.safesave.safesave.scheduled;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;


public final class TickContainers {
    private TickContainers() {
    }

    public static Long2ObjectMap<?> blockContainers(final ServerLevel level) {
        return ((TickContainerHolder) level.getBlockTicks()).SS$containers();
    }

    public static Long2ObjectMap<?> fluidContainers(final ServerLevel level) {
        return ((TickContainerHolder) level.getFluidTicks()).SS$containers();
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
