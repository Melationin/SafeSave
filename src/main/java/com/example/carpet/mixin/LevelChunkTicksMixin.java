package com.example.carpet.mixin;

import com.example.carpet.safesave.SafeTickContainer;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Gives safe-save write access to a single chunk's tick container.
 *
 * <p>Shadow fields use wildcards because JVM field descriptors erase generics; this keeps the mixin
 * non-generic while matching {@code LevelChunkTicks<T>}.
 */
@Mixin(LevelChunkTicks.class)
public abstract class LevelChunkTicksMixin implements SafeTickContainer {

    @Shadow
    @Final
    private Queue<ScheduledTick<?>> tickQueue;

    @Shadow
    @Final
    private Set<ScheduledTick<?>> ticksPerPosition;

    /** Non-final in vanilla: nulled out by {@code unpack()}. */
    @Shadow
    private List<SavedTick<?>> pendingTicks;

    @Override
    public boolean carpetExample$hasPendingTicks() {
        return this.pendingTicks != null;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void carpetExample$replaceAll(final List<?> scheduledTicks) {
        this.tickQueue.clear();
        this.ticksPerPosition.clear();
        // Drop anything still waiting to be unpacked: the supplied list is authoritative.
        this.pendingTicks = null;

        LevelChunkTicks self = (LevelChunkTicks) (Object) this;
        for (Object entry : scheduledTicks) {
            self.schedule((ScheduledTick) entry);
        }
    }

    @Override
    public List<?> carpetExample$snapshotQueue() {
        return new ArrayList<>(this.tickQueue);
    }
}
