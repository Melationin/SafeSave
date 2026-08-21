package com.example.carpet.mixin;

import com.example.carpet.safesave.PistonOrderHolder;
import com.example.carpet.safesave.SafeSaveManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Repairs three ways a moving piston loses fidelity across a save/load.
 *
 * <h2>#2 — vanilla saves {@code progressO}, not {@code progress}</h2>
 * <pre>
 * saveAdditional: output.putFloat("progress", this.progressO);   // the PREVIOUS tick's value
 * loadAdditional: this.progress = input.getFloatOr("progress", 0.0F);
 *                 this.progressO = this.progress;
 * </pre>
 * {@code tick()} sets {@code progressO = progress} at its head, so the two always differ by 0.5 while
 * a piston is in flight. Saving the older value therefore <em>rewinds the piston half a step</em>,
 * costing exactly one tick per save/load cycle. Worse, {@code moveStuckEntities} (honey block,
 * horizontal only) applies {@code deltaProgress} unconditionally, with no overlap test — so repeating
 * a half step drags a passenger an extra 0.5, turning a 1-block pull into 1.5.
 *
 * <p>Both fields are stored under our own keys and restored verbatim. Vanilla's {@code progress} key
 * is left exactly as vanilla writes it, so removing this mod degrades cleanly to vanilla behaviour
 * instead of corrupting anything.
 *
 * <h2>#5 — {@code lastTicked} is not persisted</h2>
 * {@code PistonBaseBlock.checkIfExtend} uses {@code getGameTime() == pistonEntity.getLastTicked()} as
 * one of three disjuncts deciding {@code TRIGGER_DROP} vs {@code TRIGGER_CONTRACT}. The other two
 * usually mask its loss, but not when {@code isHandlingTick()} is false — i.e. player-triggered
 * updates, which are processed after {@code level.tick()} has already cleared the flag.
 *
 * <h2>#4 — block entity tick order</h2>
 * See {@link PistonOrderHolder}.
 */
@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntitySafeSaveMixin implements PistonOrderHolder {

    /** Our own keys, so vanilla's {@code progress} keeps its vanilla meaning. */
    @Unique
    private static final String KEY_PROGRESS = "safesave_progress";
    @Unique
    private static final String KEY_PROGRESS_O = "safesave_progress_o";
    @Unique
    private static final String KEY_LAST_TICKED = "safesave_last_ticked";
    @Unique
    private static final String KEY_ORDER = "safesave_order";

    @Shadow
    private float progress;

    @Shadow
    private float progressO;

    @Shadow
    private long lastTicked;

    @Unique
    private long carpetExample$order = Long.MIN_VALUE;

    @Override
    public long carpetExample$pistonOrder() {
        return this.carpetExample$order;
    }

    /**
     * Assigns the creation sequence number. Targets the 6-arg constructor, the one
     * {@code PistonBaseBlock.moveBlocks} / {@code MovingPistonBlock.newMovingBlockEntity} use; the
     * 2-arg constructor is the deserialization path and gets its number from NBT instead.
     */
    @Inject(
            method = "<init>(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;ZZ)V",
            at = @At("TAIL")
    )
    private void carpetExample$assignOrder(final BlockPos worldPosition,
                                           final BlockState blockState,
                                           final BlockState movedState,
                                           final Direction direction,
                                           final boolean extending,
                                           final boolean isSourcePiston,
                                           final CallbackInfo ci) {
        this.carpetExample$order = SafeSaveManager.nextPistonOrder();
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void carpetExample$save(final ValueOutput output, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        output.putFloat(KEY_PROGRESS, this.progress);
        output.putFloat(KEY_PROGRESS_O, this.progressO);
        output.putLong(KEY_LAST_TICKED, this.lastTicked);
        output.putLong(KEY_ORDER, this.carpetExample$order);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void carpetExample$load(final ValueInput input, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        // Sentinel: absent => this block entity predates the mod (or the rule was off), so leave
        // vanilla's already-applied values alone rather than clobbering them with defaults.
        float savedProgress = input.getFloatOr(KEY_PROGRESS, Float.NaN);
        if (!Float.isNaN(savedProgress)) {
            this.progress = savedProgress;
            this.progressO = input.getFloatOr(KEY_PROGRESS_O, savedProgress);
        }
        this.lastTicked = input.getLongOr(KEY_LAST_TICKED, this.lastTicked);

        long order = input.getLongOr(KEY_ORDER, Long.MIN_VALUE);
        if (order != Long.MIN_VALUE) {
            this.carpetExample$order = order;
            // Keep freshly created pistons strictly after every restored one.
            SafeSaveManager.observePistonOrder(order);
        }
        // Tick order must be rebuilt: this block entity's ticker was just registered in hash order.
        SafeSaveManager.markPistonTickOrderDirty();
    }
}
