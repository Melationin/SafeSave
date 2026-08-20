package com.example.carpet.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to {@code Level.subTickCount} — the global, monotonically increasing counter handed out by
 * {@code nextSubTickCount()} as every scheduled tick's {@code subTickOrder}.
 *
 * <p>Vanilla never persists it, so after a restart it resets to {@code 0} and there is nothing left
 * to disambiguate cross-chunk tick ordering. Safe-save saves and restores it.
 */
@Mixin(Level.class)
public interface LevelSubTickCountAccessor {

    @Accessor("subTickCount")
    long carpetExample$getSubTickCount();

    @Accessor("subTickCount")
    void carpetExample$setSubTickCount(long subTickCount);
}
