package com.carpet.safesave.mixin.entity;

import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.2 {
import com.carpet.safesave.util.NbtView;
import com.carpet.safesave.util.SafeSaveNbt;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if <1.21.6 {
/*import net.minecraft.nbt.CompoundTag;
*///?} else {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?}
//?}

@Mixin(MinecartFurnace.class)
public abstract class MinecartFurnaceMixin {
    //? if >=1.21.2 {
    @Shadow public Vec3 push;

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void SS$savePush(final
                            //? if <1.21.6 {
                            /*CompoundTag
                            *///?} else {
                            ValueOutput
                            //?}
                            output, final CallbackInfo ci) {
        if (SafeSaveNbt.enabled(((Entity) (Object) this).level())) {
            SafeSaveNbt.child(NbtView.writer(output)).store("furnace_push", Vec3.CODEC, this.push);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void SS$loadPush(final
                            //? if <1.21.6 {
                            /*CompoundTag
                            *///?} else {
                            ValueInput
                            //?}
                            input, final CallbackInfo ci) {
        if (!SafeSaveNbt.enabled(((Entity) (Object) this).level())) return;
        var safe = SafeSaveNbt.childOrNull(NbtView.reader(input));
        if (safe != null) safe.read("furnace_push", Vec3.CODEC).ifPresent(value -> this.push = value);
    }
    //?}
}
