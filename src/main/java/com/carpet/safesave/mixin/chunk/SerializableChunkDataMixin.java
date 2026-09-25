package com.carpet.safesave.mixin.chunk;

import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.chunk.SerializableChunkDataAccess;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
//? if <1.21.2 {
/*import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
*///?} else {
import net.minecraft.world.level.LevelHeightAccessor;
//? if <1.21.9 {
/*import net.minecraft.core.RegistryAccess;
*///?} else {
import net.minecraft.world.level.chunk.PalettedContainerFactory;
//?}
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


//? if <1.21.2 {
/*// 1.21.2 split chunk serialization into SerializableChunkData; 1.21.1 still writes straight
// through ChunkSerializer, whose write() receives the live chunk, so the safe-save tag is computed
// and merged in a single step there instead of being carried on a data object between phases.
@Mixin(ChunkSerializer.class)
public abstract class SerializableChunkDataMixin {

    @Inject(method = "read", at = @At("HEAD"))
    private static void SS$onRead(final ServerLevel level,
                                  final PoiManager poiManager,
                                  final RegionStorageInfo storageInfo,
                                  final ChunkPos chunkPos,
                                  final CompoundTag chunkData,
                                  final CallbackInfoReturnable<ProtoChunk> cir) {
        SafeSaveManager.onChunkTagRead(level, chunkData);
    }

    @Inject(method = "write", at = @At("RETURN"), cancellable = true)
    private static void SS$onWrite(final ServerLevel level,
                                   final ChunkAccess chunk,
                                   final CallbackInfoReturnable<CompoundTag> cir) {
        cir.setReturnValue(SafeSaveManager.injectChunkData(level, chunk, cir.getReturnValue()));
    }
}
*///?} else {
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin implements SerializableChunkDataAccess {

    @Unique
    private CompoundTag SS$safeSaveTag;

    @Override
    public CompoundTag SS$getSafeSaveTag() {
        return this.SS$safeSaveTag;
    }

    @Override
    public void SS$setSafeSaveTag(final CompoundTag tag) {
        this.SS$safeSaveTag = tag;
    }

    // 1.21.9 replaced the RegistryAccess parameter with a PalettedContainerFactory.
    @Inject(method = "parse", at = @At("HEAD"))
    private static void SS$onParse(final LevelHeightAccessor levelHeight,
                                   //? if <1.21.9 {
                                   /*final RegistryAccess containerFactory,
                                   *///?} else {
                                   final PalettedContainerFactory containerFactory,
                                   //?}
                                   final CompoundTag chunkData,
                                   final CallbackInfoReturnable<SerializableChunkData> cir) {
        if (levelHeight instanceof ServerLevel level) {
            SafeSaveManager.onChunkTagRead(level, chunkData);
        }
    }

    @Inject(method = "copyOf", at = @At("RETURN"))
    private static void SS$onCopyOf(final ServerLevel level,
                                    final ChunkAccess chunk,
                                    final CallbackInfoReturnable<SerializableChunkData> cir) {
        SerializableChunkData data = cir.getReturnValue();
        SafeSaveManager.onChunkSerializing(level, chunk, (Object) data);
    }

    @ModifyReturnValue(method = "write", at = @At("RETURN"))
    private CompoundTag SS$modifyWrite(final CompoundTag original) {
        return SafeSaveManager.injectChunkData((Object) this, original);
    }
}
//?}
