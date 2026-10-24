package com.carpet.safesave.safesave.scheduled;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.ServerLevel;


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

}
