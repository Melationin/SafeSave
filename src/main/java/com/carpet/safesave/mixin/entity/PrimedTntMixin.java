package com.carpet.safesave.mixin.entity;

import com.carpet.safesave.util.SafeSaveNbt;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;



@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin {

    @Shadow
    private boolean usedPortal;

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void save(final ValueOutput output, final CallbackInfo ci) {
        if (!SafeSaveNbt.enabled()) {
            return;
        }
        ValueOutput safe = SafeSaveNbt.child(output);
        safe.putBoolean("used_portal", this.usedPortal);
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
        this.usedPortal = safe.getBooleanOr("used_portal", this.usedPortal);
    }
}
