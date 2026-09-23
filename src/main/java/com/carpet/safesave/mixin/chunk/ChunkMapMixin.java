package com.carpet.safesave.mixin.chunk;

import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveManager;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import java.util.function.BooleanSupplier;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    private void SS$markBatch(boolean flush, CallbackInfo ci) {
        if (SafeSaveManager.shouldDeferChunkMapSave(this.level)) {
            var state = SafeSaveLevelAccess.of(this.level);
            state.deferredFullSave = true;
            state.deferredFullSaveFlush |= flush;
            ci.cancel();
            return;
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;processUnloads(Ljava/util/function/BooleanSupplier;)V"))
    private void SS$deferUnloads(ChunkMap instance, BooleanSupplier haveTime, Operation<Void> original) {
        if (SafeSaveManager.shouldRun() && !this.level.getServer().isStopped()
                && SafeSaveLevelAccess.of(this.level).worldTickRunning) {
            SafeSaveLevelAccess.of(this.level).deferredUnloads = true;
            return;
        }
        original.call(instance, haveTime);
    }

}
