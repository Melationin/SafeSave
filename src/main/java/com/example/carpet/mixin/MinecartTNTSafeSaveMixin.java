package com.example.carpet.mixin;

import com.example.carpet.safesave.SafeSaveEntityAccess;
import com.example.carpet.safesave.SafeSaveManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persists {@code MinecartTNT.ignitionSource} as the source entity's UUID and rebuilds the damage
 * source on load. The explosion random value is covered by {@code SafeSave.motion}/{@code tick_count}
 * handling in the entity base; the RNG state itself is intentionally not persisted in this build.
 */
@Mixin(MinecartTNT.class)
public abstract class MinecartTNTSafeSaveMixin {

    @Shadow
    private DamageSource ignitionSource;

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void carpetExample$save(final ValueOutput output, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        Entity source = this.ignitionSource != null ? this.ignitionSource.getEntity() : null;
        if (source == null) {
            return;
        }
        ValueOutput safe = ((SafeSaveEntityAccess) this).carpetExample$safeSaveOutput();
        if (safe == null) {
            return;
        }
        EntityReference.store(EntityReference.of(source), safe, "ignition_source");
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
        EntityReference<Entity> reference = EntityReference.read(safe, "ignition_source");
        if (reference == null || !(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity cause = EntityReference.getEntity(reference, serverLevel);
        if (cause != null) {
            this.ignitionSource = this.damageSources().explosion(this, cause);
        }
    }
}
