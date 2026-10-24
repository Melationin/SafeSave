package com.carpet.safesave.mixin;

import com.carpet.safesave.safesave.SafeSaveSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerTickRateManager.class)
public abstract class ServerTickRateManagerMixin {
    @Shadow @Final private MinecraftServer server;

    @Inject(method = "setFrozen", at = @At("TAIL"))
    private void SS$recordFirstUnfreeze(final boolean frozen, final CallbackInfo ci) {
        if (!frozen) {
            SafeSaveSession.of(this.server).startupUnfrozen = true;
        }
    }
}
