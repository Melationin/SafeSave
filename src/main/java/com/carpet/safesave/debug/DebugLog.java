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
 * Formatting + emission for the {@link DebugSwitches} channels.
 *
 * <p>Every public method here re-checks its channel, so callers may call unconditionally; the
 * {@code DebugSwitches.DEBUG &&} guard at the call site is still preferred because it lets javac
 * strip the call entirely in a release build.
 */
public final class DebugLog {
    private static final Logger LOG = LoggerFactory.getLogger("safesave");

    private DebugLog() {
    }

    // ---------------------------------------------------------------- helpers

    /** Short dimension name, e.g. {@code overworld}. Never throws. */
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

    /** Registry id of a scheduled-tick payload ({@link Block} or {@link Fluid}). */
    public static String typeId(final Object type) {
        if (type instanceof Block block) {
            return BuiltInRegistries.BLOCK.getKey(block).toString();
        }
        if (type instanceof Fluid fluid) {
            return BuiltInRegistries.FLUID.getKey(fluid).toString();
        }
        return String.valueOf(type);
    }

    private static String pos(final BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    // -------------------------------------------------- scheduled tick channel

    /**
     * @param owner       human-readable owner label, e.g. {@code minecraft:overworld/block}
     * @param accepted    {@code false} when the schedule call was swallowed by the
     *                    {@code (type, pos)} de-duplication rule
     * @param currentTick current game time, or {@link Long#MIN_VALUE} when unknown
     */
    public static void scheduledTickAdded(final String owner,
                                          final ScheduledTick<?> tick,
                                          final boolean accepted,
                                          final long currentTick) {
        if (!DebugSwitches.isEnabled(DebugSwitches.Channel.SCHEDULED_TICKS)) {
            return;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("[ST][").append(accepted ? "ADD  " : "DEDUP").append("] ")
                .append(owner).append(' ')
                .append(typeId(tick.type())).append(' ')
                .append(pos(tick.pos()))
                .append(" trigger=").append(tick.triggerTick());
        if (currentTick != Long.MIN_VALUE) {
            sb.append(" now=").append(currentTick)
                    .append(" delay=").append(tick.triggerTick() - currentTick);
        }
        sb.append(" prio=").append(tick.priority())
                .append('(').append(tick.priority().getValue()).append(')')
                .append(" sub=").append(tick.subTickOrder());
        LOG.info(sb.toString());
    }

    public static void scheduledTickRun(final String owner,
                                       final ScheduledTick<?> tick,
                                       final long currentTick) {
        if (!DebugSwitches.isEnabled(DebugSwitches.Channel.SCHEDULED_TICKS)) {
            return;
        }
        LOG.info("[ST][RUN  ] {} {} {} trigger={} now={} late={} prio={}({}) sub={}",
                owner,
                typeId(tick.type()),
                pos(tick.pos()),
                tick.triggerTick(),
                currentTick,
                currentTick - tick.triggerTick(),
                tick.priority(),
                tick.priority().getValue(),
                tick.subTickOrder());
    }

    /** Emitted when the payload type could not be resolved from {@code alreadyRunThisTick}. */
    public static void scheduledTickRunUnknown(final String owner, final BlockPos pos, final Object type) {
        if (!DebugSwitches.isEnabled(DebugSwitches.Channel.SCHEDULED_TICKS)) {
            return;
        }
        LOG.info("[ST][RUN  ] {} {} {} (no ScheduledTick metadata available)", owner, typeId(type), pos(pos));
    }

    // ----------------------------------------------------- block event channel

    /**
     * @param queueSizeAfter size of {@code ServerLevel.blockEvents} after the add attempt
     * @param accepted       {@code false} when the event was swallowed by the
     *                       {@code ObjectLinkedOpenHashSet} de-duplication
     */
    public static void blockEventAdded(final ServerLevel level,
                                       final BlockPos pos,
                                       final Block block,
                                       final int paramA,
                                       final int paramB,
                                       final boolean accepted,
                                       final int queueSizeAfter) {
        if (!DebugSwitches.isEnabled(DebugSwitches.Channel.BLOCK_EVENTS)) {
            return;
        }
        LOG.info("[BE][{}] {} {} {} a={} b={} queue={} gameTime={}",
                accepted ? "ADD  " : "DEDUP",
                dimensionName(level),
                BuiltInRegistries.BLOCK.getKey(block),
                pos(pos),
                paramA,
                paramB,
                queueSizeAfter,
                level.getGameTime());
    }

    public static void blockEventRun(final ServerLevel level, final BlockEventData data, final boolean handled) {
        if (!DebugSwitches.isEnabled(DebugSwitches.Channel.BLOCK_EVENTS)) {
            return;
        }
        LOG.info("[BE][RUN  ] {} {} {} a={} b={} handled={} state={}",
                dimensionName(level),
                BuiltInRegistries.BLOCK.getKey(data.block()),
                pos(data.pos()),
                data.paramA(),
                data.paramB(),
                handled,
                level.getBlockState(data.pos()));
    }

    // ------------------------------------------------------ world tick channel

    public static void worldTickStart(final ServerLevel level, final int serverTickCount, final boolean frozen) {
        if (!DebugSwitches.isEnabled(DebugSwitches.Channel.WORLD_TICK)) {
            return;
        }
        LOG.info("[TICK] {} gameTime={} serverTick={} frozen={} blockTicks={} fluidTicks={} blockEventsPending={}",
                dimensionName(level),
                level.getGameTime(),
                serverTickCount,
                frozen,
                level.getBlockTicks().count(),
                level.getFluidTicks().count(),
                SafeSaveManager.pendingBlockEventCount(level));
    }

    // ------------------------------------------------------------ generic info

    /** Always-on informational output for the safe-save feature (not gated by a channel). */
    public static void info(final String format, final Object... args) {
        LOG.info("[safe-save] " + format, args);
    }

    public static void warn(final String format, final Object... args) {
        LOG.warn("[safe-save] " + format, args);
    }

    private static final java.util.Set<String> WARNED_ONCE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Logs a warning at most once per {@code key}, so a per-chunk anomaly cannot flood the log. */
    public static void warnOnce(final String key, final String format, final Object... args) {
        if (WARNED_ONCE.add(key)) {
            LOG.warn("[safe-save] " + format, args);
        }
    }

    public static Identifier tryParse(final String id) {
        return Identifier.tryParse(id);
    }
}
