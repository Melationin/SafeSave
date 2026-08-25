package com.carpet.safesave.mixin.blockentity;

import com.carpet.safesave.safesave.blockentity.PistonManager;
import com.carpet.safesave.safesave.blockentity.PistonOrderHolder;
import com.carpet.safesave.util.SafeSaveNbt;
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



@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityMixin implements PistonOrderHolder {


    @Shadow
    private float progress;

    @Shadow
    private float progressO;

    @Shadow
    private long lastTicked;

    @Unique
    private long SS$order = Long.MIN_VALUE;

    @Override
    public long SS$pistonOrder() {
        return this.SS$order;
    }

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
        this.SS$order = PistonManager.nextPistonOrder();
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void save(final ValueOutput output, final CallbackInfo ci) {
        if (!SafeSaveNbt.enabled()) {
            return;
        }
        ValueOutput tag = SafeSaveNbt.child(output);
        tag.putFloat("progress", this.progress);
        tag.putFloat("progress_o", this.progressO);
        tag.putLong("lastTicked", this.lastTicked);
        tag.putLong("order", this.SS$order);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void carpetExample$load(final ValueInput input, final CallbackInfo ci) {
        if (!SafeSaveNbt.enabled()) {
            return;
        }
        ValueInput tag = SafeSaveNbt.childOrNull(input);
        if (tag != null) {
            float savedProgress = tag.getFloatOr("progress", Float.NaN);
            if (!Float.isNaN(savedProgress)) {
                this.progress = savedProgress;
                this.progressO = tag.getFloatOr("progress_o", savedProgress);
            }
            this.lastTicked = tag.getLongOr("lastTicked", this.lastTicked);

            long order = tag.getLongOr("order", Long.MIN_VALUE);
            if (order != Long.MIN_VALUE) {
                this.SS$order = order;

                PistonManager.observePistonOrder(order);
            }
        }

        PistonManager.markPistonTickOrderDirty();
    }
}
