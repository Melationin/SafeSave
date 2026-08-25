package com.carpet.safesave.util;

import net.minecraft.server.level.ServerLevel;

/**
 * 维度 id 的统一读取。
 */
public final class DimensionIds {
    private DimensionIds() {
    }

    /** 如 {@code minecraft:overworld}。 */
    public static String dimensionId(final ServerLevel level) {
        return level.dimension().identifier().toString();
    }
}
