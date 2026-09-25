package com.carpet.safesave.mixin.entity;

import com.carpet.safesave.util.NbtView;
import com.carpet.safesave.util.SafeSaveNbt;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
//? if <1.21.6 {
/*import net.minecraft.nbt.CompoundTag;
*///?} else {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;



@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartMixin {

    @Shadow
    private boolean onRails;

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void save(final
                      //? if <1.21.6 {
                      /*CompoundTag
                      *///?} else {
                      ValueOutput
                      //?}
                              output, final CallbackInfo ci) {
        if (!SafeSaveNbt.enabled()) {
            return;
        }
        NbtView.Writer safe = SafeSaveNbt.child(NbtView.writer(output));
        safe.putBoolean("on_rails", this.onRails);

    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void load(final
                      //? if <1.21.6 {
                      /*CompoundTag
                      *///?} else {
                      ValueInput
                      //?}
                              input, final CallbackInfo ci) {
        if (!SafeSaveNbt.enabled()) {
            return;
        }
        NbtView.Reader safe = SafeSaveNbt.childOrNull(NbtView.reader(input));
        if (safe == null) {
            return;
        }
        this.onRails = safe.getBooleanOr("on_rails", this.onRails);
    }
}
