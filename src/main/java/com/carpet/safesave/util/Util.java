package com.carpet.safesave.util;

import net.minecraft.server.level.ServerLevel;

public class Util
{
    public static String dimensionId(final ServerLevel level) {
        //? if <1.21.11 {
        /*return level.dimension().location().toString();
        *///?} else {
        return level.dimension().identifier().toString();
        //?}
    }
}
