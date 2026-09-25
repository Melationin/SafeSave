package com.carpet.safesave.mixin;

import com.carpet.safesave.config.SafeSaveConfig;
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


    @Inject(method = "loadLevel", at = @At("HEAD"))
    private void SS$onServerLoaded(final CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        // 必须先于任何 shouldRun() 判定读档。
        SafeSaveConfig.load(server);
        SafeSaveManager.onServerLoaded(server);
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void SS$onServerStopping(final CallbackInfo ci) {
        SafeSaveManager.saveAtShutdown((MinecraftServer) (Object) this);
    }

    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void SS$onLevelsCreated(final CallbackInfo ci) {
        SafeSaveManager.onLevelsCreated((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void SS$onServerTickHead(final BooleanSupplier haveTime, final CallbackInfo ci) {
        SafeSaveManager.onFirstServerTick((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickChildren", at = @At("RETURN"))
    private void SS$onServerTickEnd(final BooleanSupplier haveTime, final CallbackInfo ci) {
        SafeSaveManager.onServerTickEnd((MinecraftServer) (Object) this, haveTime);
    }

    @Inject(method = "tickChildren", at = @At("HEAD"))
    private void SS$onServerTickChildrenStart(final BooleanSupplier haveTime, final CallbackInfo ci) {
        SafeSaveManager.onServerTickChildrenStart();
    }

    @Inject(method = "saveEverything", at = @At("HEAD"), cancellable = true)
    private void SS$deferSaveEverything(final boolean silent, final boolean flush,
                                       final boolean force, final CallbackInfoReturnable<Boolean> cir) {
        if (SafeSaveManager.deferSaveEverything((MinecraftServer) (Object) this, silent, flush, force)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    private void SS$onSaveAllChunks(final boolean silent,
                                               final boolean flush,
                                               final boolean force,
                                               final CallbackInfoReturnable<Boolean> cir) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (SafeSaveManager.deferSaveAllChunks(server, silent, flush, force)) {
            cir.setReturnValue(true);
            return;
        }
        SafeSaveManager.saveAll(server);
    }
}
