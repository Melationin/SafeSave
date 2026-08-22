package com.carpet.safesave.mixin.entity;

import com.carpet.safesave.safesave.SafeSaveManager;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.carpet.safesave.util.Util.KEY_SAFE_SAVE;


@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin {

    @Shadow
    private boolean usedPortal;

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void save(final ValueOutput output, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        if(!(output instanceof TagValueOutput tagValueOutput)) return ;
        ValueOutput safe = tagValueOutput.getChild(KEY_SAFE_SAVE);
        if(safe == null) safe = tagValueOutput.child(KEY_SAFE_SAVE);
        safe.putBoolean("used_portal", this.usedPortal);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void load(final ValueInput input, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        ValueInput safe = input.child(KEY_SAFE_SAVE).orElse(null);
        if (safe == null) {
            return;
        }
        this.usedPortal = safe.getBooleanOr("used_portal", this.usedPortal);
    }
}
