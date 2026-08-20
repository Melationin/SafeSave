package com.example.carpet.mixin;

import com.example.carpet.safesave.SafeSaveManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/**
 * Server-level safe-save wiring: level binding, the pre-first-tick freeze, and the save snapshot.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerSafeSaveMixin {

    /**
     * {@code prepareLevels} runs after {@code createLevels} but before any chunk is prepared for
     * ticking, and it is still inside {@code loadLevel} — i.e. after Carpet's {@code onServerLoaded}
     * has already read the side file. That makes it the earliest point where both the levels and the
     * restore data exist, which is exactly what restoring {@code Level.subTickCount} requires.
     */
    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void carpetExample$onLevelsCreated(final CallbackInfo ci) {
        SafeSaveManager.onLevelsCreated((MinecraftServer) (Object) this);
    }

    /** Freeze before the very first server tick, so nothing advances before the restore is verified. */
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void carpetExample$beforeFirstServerTick(final BooleanSupplier haveTime, final CallbackInfo ci) {
        SafeSaveManager.onFirstServerTick((MinecraftServer) (Object) this);
    }

    /**
     * HEAD, not RETURN: with {@code flush=true} vanilla runs {@code processUnloads} during the save,
     * which unregisters tick containers, so at RETURN part of the world would already have vanished
     * from {@code LevelTicks.allContainers}.
     */
    @Inject(method = "saveAllChunks", at = @At("HEAD"))
    private void carpetExample$onSaveAllChunks(final boolean silent,
                                               final boolean flush,
                                               final boolean force,
                                               final CallbackInfoReturnable<Boolean> cir) {
        SafeSaveManager.saveAll((MinecraftServer) (Object) this);
    }
}
