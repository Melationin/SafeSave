package com.carpet.safesave.mixin.entity;

import com.carpet.safesave.util.SafeSaveNbt;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {

    @Shadow
    public boolean forceTickAfterTeleportToDuplicate;

    @Shadow
    public abstract BlockPos getStartPos();

    @Shadow
    public abstract void setStartPos(BlockPos pos);

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void save(final ValueOutput output, final CallbackInfo ci) {
        if (!SafeSaveNbt.enabled()) {
            return;
        }
        ValueOutput safe = SafeSaveNbt.child(output);
        safe.putBoolean("force_tick_after_teleport_to_duplicate", this.forceTickAfterTeleportToDuplicate);
        safe.store("start_pos", BlockPos.CODEC, this.getStartPos());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void load(final ValueInput input, final CallbackInfo ci) {
        if (!SafeSaveNbt.enabled()) {
            return;
        }
        ValueInput safe = SafeSaveNbt.childOrNull(input);
        if (safe == null) {
            return;
        }
        this.forceTickAfterTeleportToDuplicate = safe.getBooleanOr(
                "force_tick_after_teleport_to_duplicate",
                this.forceTickAfterTeleportToDuplicate
        );
        safe.read("start_pos", BlockPos.CODEC).ifPresent(this::setStartPos);
    }
}
