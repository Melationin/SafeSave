package com.carpet.safesave.debug;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class DebugLog {
    private static final Logger LOG = LoggerFactory.getLogger("safesave");

    private DebugLog() {
    }

    public static String typeId(final Object type) {
        if (type instanceof Block block) {
            return BuiltInRegistries.BLOCK.getKey(block).toString();
        }
        if (type instanceof Fluid fluid) {
            return BuiltInRegistries.FLUID.getKey(fluid).toString();
        }
        return String.valueOf(type);
    }

    public static void info(final String format, final Object... args) {
        LOG.info("[safe-save] " + format, args);
    }

    public static void debug(final String format, final Object... args) {
        LOG.debug("[safe-save] " + format, args);
    }

    public static void warn(final String format, final Object... args) {
        LOG.warn("[safe-save] " + format, args);
    }

    private static final java.util.Set<String> WARNED_ONCE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void warnOnce(final String key, final String format, final Object... args) {
        if (WARNED_ONCE.add(key)) {
            LOG.warn("[safe-save] " + format, args);
        }
    }
}
