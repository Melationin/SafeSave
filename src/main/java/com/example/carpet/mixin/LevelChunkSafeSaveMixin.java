package com.example.carpet.mixin;

import com.example.carpet.safesave.SafeSaveManager;
import com.example.carpet.safesave.SafeTickContainer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Overrides vanilla's tick re-anchoring.
 *
 * <p>{@code unpackTicks(currentTick)} turns each saved {@code SavedTick} into
 * {@code ScheduledTick(triggerTick = currentTick + delay, subTickOrder = -N..-1)} — the exact point at
 * which the absolute trigger time and the global ordering are lost.
 *
 * <p>HEAD captures whatever is <em>already</em> queued. At that moment {@code pendingTicks} has not
 * been merged yet, so anything present can only be a tick scheduled during this session while the
 * chunk sat at {@code FULL} without block-ticking (e.g. an observer across a chunk border). Those are
 * re-added after the restore, so this feature is never worse than vanilla.
 *
 * <p>TAIL lets vanilla finish (clearing {@code pendingTicks}) and then replaces the result wholesale
 * with the absolute data safe-save kept.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkSafeSaveMixin {

    @Unique
    private List<?> carpetExample$preUnpackBlockTicks;

    @Unique
    private List<?> carpetExample$preUnpackFluidTicks;

    @Inject(method = "unpackTicks", at = @At("HEAD"))
    private void carpetExample$capturePreUnpackTicks(final long currentTick, final CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (!SafeSaveManager.hasPendingRestore(self)) {
            return;
        }
        this.carpetExample$preUnpackBlockTicks =
                ((SafeTickContainer) self.getBlockTicks()).carpetExample$snapshotQueue();
        this.carpetExample$preUnpackFluidTicks =
                ((SafeTickContainer) self.getFluidTicks()).carpetExample$snapshotQueue();
    }

    @Inject(method = "unpackTicks", at = @At("TAIL"))
    private void carpetExample$restoreAbsoluteTicks(final long currentTick, final CallbackInfo ci) {
        SafeSaveManager.restoreChunk((LevelChunk) (Object) this,
                this.carpetExample$preUnpackBlockTicks,
                this.carpetExample$preUnpackFluidTicks);
        this.carpetExample$preUnpackBlockTicks = null;
        this.carpetExample$preUnpackFluidTicks = null;
    }
}
