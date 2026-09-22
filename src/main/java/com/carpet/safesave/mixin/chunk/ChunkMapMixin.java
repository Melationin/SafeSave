package com.carpet.safesave.mixin.chunk;

import com.carpet.safesave.safesave.region.RegionLifecycle;
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

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "saveAllChunks", at = @At("HEAD"))
    private void SS$markBatch(boolean flush, CallbackInfo ci) {
        for (var holder : ((ChunkMap) (Object) this).visibleChunkMap.values()) {
            ChunkAccess chunk = holder.getLatestChunk();
            if (chunk != null && RegionLifecycle.isProtected(this.level, chunk.getPos().pack())) chunk.markUnsaved();
        }
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
