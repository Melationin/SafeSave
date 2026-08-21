package com.example.carpet.mixin;

import com.example.carpet.safesave.SafeSaveEntityAccess;
import com.example.carpet.safesave.SafeSaveManager;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persists {@code PrimedTnt.usedPortal}, which vanilla only sets after an actual end-portal
 * teleport and otherwise loses on reload.
 */
@Mixin(PrimedTnt.class)
public abstract class PrimedTntSafeSaveMixin {

    @Shadow
    private boolean usedPortal;

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void carpetExample$save(final ValueOutput output, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        ValueOutput safe = ((SafeSaveEntityAccess) this).carpetExample$safeSaveOutput();
        if (safe != null) {
            safe.putBoolean("used_portal", this.usedPortal);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void carpetExample$load(final ValueInput input, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        ValueInput safe = ((SafeSaveEntityAccess) this).carpetExample$safeSaveInput();
        if (safe != null) {
            this.usedPortal = safe.getBooleanOr("used_portal", this.usedPortal);
        }
    }
}
