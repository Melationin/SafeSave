package com.carpet.safesave.mixin.chunk;

import com.carpet.safesave.safesave.region.RegionLifecycle;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveManager;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
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
        if (SafeSaveManager.shouldRun() && !SafeSaveManager.canCaptureSnapshot(this.level)) {
            var state = SafeSaveLevelAccess.of(this.level);
            state.deferredFullSave = true;
            state.deferredFullSaveFlush |= flush;
            ci.cancel();
            return;
        }
        for (var holder : ((ChunkMap) (Object) this).visibleChunkMap.values()) {
            ChunkAccess chunk = holder.getLatestChunk();
            if (chunk != null && RegionLifecycle.isProtected(this.level, chunk.getPos().pack())) chunk.markUnsaved();
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;processUnloads(Ljava/util/function/BooleanSupplier;)V"))
    private void SS$deferUnloads(ChunkMap instance, BooleanSupplier haveTime, Operation<Void> original) {
        if (SafeSaveManager.shouldRun() && SafeSaveLevelAccess.of(this.level).worldTickRunning) {
            SafeSaveLevelAccess.of(this.level).deferredUnloads = true;
            return;
        }
        original.call(instance, haveTime);
    }

    // Two direct call sites: saveChunkIfNeeded and the compiler-generated unload lambda.
    // The flush loop uses this::save; DO NOT mark dirty inside save itself or that loop never ends.
    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z"),
            require = 2, allow = 2)
    private boolean SS$forceProtectedSave(ChunkMap instance, ChunkAccess chunk, Operation<Boolean> original) {
        if (RegionLifecycle.isProtected(this.level, chunk.getPos().pack())) {
            chunk.markUnsaved();
        }
        return original.call(instance, chunk);
    }
}
