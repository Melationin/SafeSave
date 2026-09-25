package com.carpet.safesave.debug;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class DebugLog {
    private static final Logger LOG = LoggerFactory.getLogger("safesave");
    
    public static final boolean DEBUG = resolveDebug();

    private DebugLog() {
    }

    private static boolean resolveDebug() {
        String override = System.getProperty("safesave.debug");
        return override != null
                ? Boolean.parseBoolean(override)
                : FabricLoader.getInstance().isDevelopmentEnvironment();
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
        if (!DEBUG) {
            return;
        }
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
