package com.carpet.safesave.mixin.blockentity;

import com.carpet.safesave.safesave.blockentity.PistonOrderHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    @Inject(method = "setLevel", at = @At("TAIL"))
    private void SS$onLevelAttached(final Level level, final CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel
                && (Object) this instanceof PistonOrderHolder piston) {
            piston.SS$onLevelAttached(serverLevel);
        }
    }
}
