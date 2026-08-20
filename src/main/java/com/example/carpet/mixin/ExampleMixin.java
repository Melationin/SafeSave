package com.example.carpet.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public class ExampleMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void carpetExample$onServerTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        // Example mixin: runs every server tick.
        // Put your own mixin logic here.
    }
}
