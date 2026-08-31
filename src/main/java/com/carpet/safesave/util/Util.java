package com.carpet.safesave.util;

import net.minecraft.server.level.ServerLevel;

public class Util
{
    public static String dimensionId(final ServerLevel level) {
        return level.dimension().identifier().toString();
    }
}
