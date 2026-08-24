package com.carpet.safesave.mixin.blockentity;

import com.carpet.safesave.safesave.blockentity.PistonManager;
import com.carpet.safesave.safesave.blockentity.PistonOrderHolder;
import com.carpet.safesave.safesave.SafeSaveManager;
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

import static com.carpet.safesave.util.Util.KEY_SAFE_SAVE;


@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityMixin implements PistonOrderHolder {


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
        if (!SafeSaveManager.enabled()) {
            return;
        }
        var tag = output.child(KEY_SAFE_SAVE);
        tag.putFloat("progress", this.progress);
        tag.putFloat("progress_o", this.progressO);
        tag.putLong("lastTicked", this.lastTicked);
        tag.putLong("order", this.SS$order);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void carpetExample$load(final ValueInput input, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        var tag = input.child(KEY_SAFE_SAVE);
        if(tag.isPresent()) {
            var tag2 = tag.get();
            float savedProgress = tag2.getFloatOr("progress", Float.NaN);
            if (!Float.isNaN(savedProgress)) {
                this.progress = savedProgress;
                this.progressO = tag2.getFloatOr("progress_o", savedProgress);
            }
            this.lastTicked = tag2.getLongOr("lastTicked", this.lastTicked);

            long order = tag2.getLongOr("order", Long.MIN_VALUE);
            if (order != Long.MIN_VALUE) {
                this.SS$order = order;

                PistonManager.observePistonOrder(order);
            }
        }else {

            // 旧版的保存方式
            float savedProgress = input.getFloatOr(KEY_PROGRESS, Float.NaN);
            if (!Float.isNaN(savedProgress)) {
                this.progress = savedProgress;
                this.progressO = input.getFloatOr(KEY_PROGRESS_O, savedProgress);
            }
            this.lastTicked = input.getLongOr(KEY_LAST_TICKED, this.lastTicked);

            long order = input.getLongOr(KEY_ORDER, Long.MIN_VALUE);
            if (order != Long.MIN_VALUE) {
                this.SS$order = order;
                PistonManager.observePistonOrder(order);
            }
        }

        PistonManager.markPistonTickOrderDirty();
    }
}
