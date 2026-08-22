package com.carpet.safesave.debug;

import java.util.Locale;

/**
 * Central debug switchboard for SafeSave.
 *
 * <p>{@link #DEBUG} is a {@code public static final boolean} compile-time constant on purpose:
 * when it is flipped to {@code false} every {@code if (DebugSwitches.DEBUG && ...)} guard becomes
 * statically false and javac strips the guarded code out of the class file entirely, so the debug
 * instrumentation costs literally nothing in a release build.
 *
 * <p>While {@code DEBUG} is {@code true} the individual channels below are toggled at runtime with
 * the {@code /safesave} command. They all default to {@code false} so that merely shipping a debug
 * build does not spam the log.
 */
public final class DebugSwitches {
    /** Master compile-time debug switch. Set to {@code false} to strip all debug output. */
    public static final boolean DEBUG = true;

    /** Individually toggleable debug output channels. */
    public enum Channel {
        /** Detailed output whenever a scheduled tick is added to / executed from a tick container. */
        SCHEDULED_TICKS("scheduledTicks", "scheduled tick add/execute"),
        /** Detailed output whenever a block event is queued / executed. */
        BLOCK_EVENTS("blockEvents", "block event add/execute"),
        /** One line at the head of every {@code ServerLevel.tick}. */
        WORLD_TICK("worldTick", "world tick start");

        private final String id;
        private final String description;

        Channel(final String id, final String description) {
            this.id = id;
            this.description = description;
        }

        public String id() {
            return this.id;
        }

        public String description() {
            return this.description;
        }

        public static Channel byId(final String id) {
            for (Channel channel : values()) {
                if (channel.id.equalsIgnoreCase(id)) {
                    return channel;
                }
            }
            return null;
        }
    }

    private static final boolean[] ENABLED = new boolean[Channel.values().length];

    private DebugSwitches() {
    }

    /**
     * @return {@code true} when {@link #DEBUG} is on <em>and</em> the channel has been switched on.
     */
    public static boolean isEnabled(final Channel channel) {
        return DEBUG && ENABLED[channel.ordinal()];
    }

    /**
     * Switches a channel on or off. A no-op unless {@link #DEBUG} is on.
     *
     * @return the value actually stored
     */
    public static boolean set(final Channel channel, final boolean enabled) {
        if (!DEBUG) {
            return false;
        }
        ENABLED[channel.ordinal()] = enabled;
        return enabled;
    }

    public static void setAll(final boolean enabled) {
        for (Channel channel : Channel.values()) {
            set(channel, enabled);
        }
    }

    public static String describeState() {
        StringBuilder sb = new StringBuilder();
        sb.append("DEBUG=").append(DEBUG);
        for (Channel channel : Channel.values()) {
            sb.append(' ').append(channel.id()).append('=').append(isEnabled(channel));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
