package com.carpet.safesave.mixin.chunk;

import com.carpet.safesave.safesave.SafeSaveManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 区块 NBT 的 safe-save 读写接线。
 *
 * <p>{@code parse} 是唯一能看到原始区块 NBT 的加载点（且第一个参数就是 {@code ServerLevel}，
 * 因此维度已知）；{@code copyOf}/{@code write} 是保存时唯一能拿到世界与最终 NBT 的点。
 * 本 mixin 只做“暂存/注入”，实际编解码与窗口保护逻辑都在 {@code SafeSaveManager}。
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin {

    @Inject(method = "parse", at = @At("HEAD"))
    private static void SS$onParse(final LevelHeightAccessor levelHeight,
                                   final PalettedContainerFactory containerFactory,
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
        SafeSaveManager.onChunkSerializing(level, chunk, cir.getReturnValue());
    }

    @ModifyReturnValue(method = "write", at = @At("RETURN"))
    private CompoundTag SS$modifyWrite(final CompoundTag original) {
        return SafeSaveManager.injectChunkData((SerializableChunkData) (Object) this, original);
    }
}
