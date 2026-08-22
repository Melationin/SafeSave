package com.carpet.safesave.safesave;

import java.util.List;

/**
 * Duck interface injected into {@code LevelChunkTicks}, giving safe-save direct control over a
 * chunk's tick container.
 *
 * <p>Deliberately wildcard-typed: the target class is generic ({@code LevelChunkTicks<T>}) and JVM
 * descriptors erase generics anyway, so keeping the interface non-generic avoids generic-mixin
 * friction while remaining type-correct at runtime (a block container only ever receives
 * {@code ScheduledTick<Block>} instances).
 */
public interface SafeTickContainer {

    /**
     * @return {@code true} when this container still holds un-unpacked {@code pendingTicks}
     *         (i.e. the chunk was read from disk but never reached {@code BLOCK_TICKING}).
     *         Such chunks carry no absolute timing, so safe-save must not snapshot them.
     */
    boolean SS$hasPendingTicks();

    /**
     * Wipes the queue, the {@code (type,pos)} de-duplication set and any {@code pendingTicks}, then
     * re-schedules exactly the supplied {@code ScheduledTick} instances.
     *
     * <p>Wiping first is essential: {@code LevelChunkTicks.schedule} silently drops a tick whose
     * {@code (type,pos)} pair is already present, so without the wipe the vanilla re-anchored ticks
     * would win and the restore would be a no-op.
     *
     * <p>Re-scheduling through the normal {@code schedule} path keeps the parent
     * {@code LevelTicks.nextTickForContainer} cache coherent via the {@code onTickAdded} callback.
     *
     * @param scheduledTicks list of {@code ScheduledTick} carrying absolute
     *                       {@code triggerTick}/{@code subTickOrder}
     */
    void SS$replaceAll(List<?> scheduledTicks);

    /** Live {@code ScheduledTick} entries currently queued (absolute timing intact). */
    List<?> SS$snapshotQueue();
}
