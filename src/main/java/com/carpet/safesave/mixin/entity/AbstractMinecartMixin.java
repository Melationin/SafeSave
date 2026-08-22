package com.carpet.safesave.mixin.entity;

import com.carpet.safesave.safesave.SafeSaveManager;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.carpet.safesave.util.Util.KEY_SAFE_SAVE;


@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartMixin {

    @Shadow
    private boolean onRails;

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void save(final ValueOutput output, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        if(!(output instanceof TagValueOutput tagValueOutput)) return ;
        ValueOutput safe = tagValueOutput.getChild(KEY_SAFE_SAVE);
        if(safe == null) safe = tagValueOutput.child(KEY_SAFE_SAVE);
        safe.putBoolean("on_rails", this.onRails);

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
        this.onRails = safe.getBooleanOr("on_rails", this.onRails);
    }
}
