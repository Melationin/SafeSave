package com.carpet.safesave.debug;

import com.carpet.safesave.safesave.SafeSaveManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>这里每个公开方法都会重新检查自己的通道，因此调用方可以无条件调用；但仍然建议在调用处使用
 * {@code DebugSwitches.DEBUG &&} 守卫，因为它能让 javac 在发布构建中完全剥离该调用。
 */
public final class DebugLog {
    private static final Logger LOG = LoggerFactory.getLogger("safesave");

    private DebugLog() {
    }

    // ---------------------------------------------------------------- 辅助方法

    /** 简短的维度名，如 {@code overworld}。绝不抛异常。 */
    public static String dimensionName(final Level level) {
        if (level == null) {
            return "?";
        }
        try {
            return level.dimension().identifier().toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 计划刻载荷（{@link Block} 或 {@link Fluid}）的注册表 id。 */
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

    /** safe-save 功能常开的信息输出（不受通道门控）。 */
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

    public static Identifier tryParse(final String id) {
        return Identifier.tryParse(id);
    }
}
