package com.carpet.safesave.mixin;

import com.carpet.safesave.safesave.SafeSaveManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {


    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void SS$onLevelsCreated(final CallbackInfo ci) {
        SafeSaveManager.onLevelsCreated((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void SS$onServerTickHead(final BooleanSupplier haveTime, final CallbackInfo ci) {
        SafeSaveManager.onFirstServerTick((MinecraftServer) (Object) this);
    }

    @Inject(method = "saveAllChunks", at = @At("HEAD"))
    private void SS$onSaveAllChunks(final boolean silent,
                                               final boolean flush,
                                               final boolean force,
                                               final CallbackInfoReturnable<Boolean> cir) {
        SafeSaveManager.saveAll((MinecraftServer) (Object) this);
    }
}
