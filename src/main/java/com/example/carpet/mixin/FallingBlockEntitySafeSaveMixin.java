package com.example.carpet.mixin;

import com.example.carpet.safesave.SafeSaveEntityAccess;
import com.example.carpet.safesave.SafeSaveManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persists {@code FallingBlockEntity} state that vanilla drops on reload: the end-portal forced-tick
 * marker and the client render start position.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntitySafeSaveMixin {

    @Shadow
    public boolean forceTickAfterTeleportToDuplicate;

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void carpetExample$save(final ValueOutput output, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        ValueOutput safe = ((SafeSaveEntityAccess) this).carpetExample$safeSaveOutput();
        if (safe == null) {
            return;
        }
        safe.putBoolean("force_tick_after_teleport_to_duplicate", this.forceTickAfterTeleportToDuplicate);
        safe.store("start_pos", BlockPos.CODEC, this.getStartPos());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void carpetExample$load(final ValueInput input, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        ValueInput safe = ((SafeSaveEntityAccess) this).carpetExample$safeSaveInput();
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
