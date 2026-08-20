package com.example.carpet.debug;

import net.minecraft.server.level.ServerLevel;

/**
 * Duck interface injected into {@code LevelTicks} so that a tick container knows which dimension
 * and which kind (block/fluid) it belongs to.
 *
 * <p>{@code LevelTicks} has no back-reference to its {@code Level} in vanilla, which would leave the
 * debug output unable to say <em>which</em> dimension a scheduled tick belongs to, and unable to
 * report the current game time. Binding the owner once at level setup fixes both.
 */
public interface TickOwnerAware {
    void carpetExample$bindOwner(ServerLevel level, String kind);

    /** e.g. {@code minecraft:overworld/block}; {@code "?"} until bound. */
    String carpetExample$ownerLabel();

    /** Current game time of the owning level, or {@link Long#MIN_VALUE} when unbound. */
    long carpetExample$ownerGameTime();
}
