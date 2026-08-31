package com.carpet.safesave.debug;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 每个公开方法都会自查自己的通道，调用方可以无条件调用；仍建议在调用处加
 * {@code DebugSwitches.DEBUG &&} 守卫，让 javac 在发布构建中完全剥离该调用。
 */
public final class DebugLog {
    private static final Logger LOG = LoggerFactory.getLogger("safesave");

    private DebugLog() {
    }

    // ---------------------------------------------------------------- 辅助方法

    public static String typeId(final Object type) {
        if (type instanceof Block block) {
            return BuiltInRegistries.BLOCK.getKey(block).toString();
        }
        if (type instanceof Fluid fluid) {
            return BuiltInRegistries.FLUID.getKey(fluid).toString();
        }
        return String.valueOf(type);
    }


    // ------------------------------------------------------------ 通用信息

    public static void info(final String format, final Object... args) {
        LOG.info("[safe-save] " + format, args);
    }

    public static void warn(final String format, final Object... args) {
        LOG.warn("[safe-save] " + format, args);
    }

    private static final java.util.Set<String> WARNED_ONCE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 每个 {@code key} 最多记录一次警告，避免按区块的异常刷屏日志。 */
    public static void warnOnce(final String key, final String format, final Object... args) {
        if (WARNED_ONCE.add(key)) {
            LOG.warn("[safe-save] " + format, args);
        }
    }
}
