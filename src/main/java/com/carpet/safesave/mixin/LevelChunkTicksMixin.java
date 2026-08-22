package com.carpet.safesave.mixin;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeTickContainer;
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
    public boolean SS$hasPendingTicks() {
        return this.pendingTicks != null;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void SS$replaceAll(final List<?> scheduledTicks) {
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
    public List<?> SS$snapshotQueue() {
        // tickQueue is `private final` with an initializer in vanilla, so this can only be null if
        // something else in the environment interfered with LevelChunkTicks (another mixin on its
        // constructor/field, or a mod/MC version this build was not compiled against). Crashing the
        // autosave over it would be far worse than skipping one chunk, so report loudly and degrade.
        if (this.tickQueue == null) {
            DebugLog.warnOnce("null-tickQueue",
                    "LevelChunkTicks.tickQueue is null on {} - skipping this chunk's scheduled ticks. "
                            + "vanilla declares it 'private final' with an initializer, so another mod's mixin or a "
                            + "version mismatch is the likely cause; please report the full log and mod list.",
                    this.getClass().getName());
            return List.of();
        }
        return new ArrayList<>(this.tickQueue);
    }
}
