package com.example.carpet.mixin;

import com.example.carpet.safesave.SafeSaveEntityAccess;
import com.example.carpet.safesave.SafeSaveManager;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persists {@code AbstractMinecart} runtime state that vanilla does not write: the on-rails flag and
 * the {@code VehicleEntity} hurt animation/damage counters.
 */
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartSafeSaveMixin {

    @Shadow
    private boolean onRails;

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void carpetExample$save(final ValueOutput output, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        ValueOutput safe = ((SafeSaveEntityAccess) this).carpetExample$safeSaveOutput();
        if (safe == null) {
            return;
        }
        safe.putBoolean("on_rails", this.onRails);
        safe.putInt("hurt_time", this.getHurtTime());
        safe.putInt("hurt_dir", this.getHurtDir());
        safe.putFloat("damage", this.getDamage());
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
        this.onRails = safe.getBooleanOr("on_rails", this.onRails);
        this.setHurtTime(safe.getIntOr("hurt_time", this.getHurtTime()));
        this.setHurtDir(safe.getIntOr("hurt_dir", this.getHurtDir()));
        this.setDamage(safe.getFloatOr("damage", this.getDamage()));
    }
}
