package com.carpet.safesave.mixin.chunk;

import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.chunk.SerializableChunkDataAccess;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code parse} 是唯一能看到原始区块 NBT 的加载点（首参即 {@code ServerLevel}，维度已知），
 * {@code copyOf}/{@code write} 是保存侧唯一能拿到世界与最终 NBT 的点。
 * {@code copyOf}（服务器线程）将 tag 暂存于 record 实例的 {@code @Unique} 字段，
 * {@code write}（后台写线程）从同一实例读回——实例经线程池提交，写读间有 happens-before；
 * tag 为 {@code null} 表示无数据或未启用。
 */
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
        SerializableChunkData data = cir.getReturnValue();
        SafeSaveManager.onChunkSerializing(level, chunk, (Object) data);
    }

    @ModifyReturnValue(method = "write", at = @At("RETURN"))
    private CompoundTag SS$modifyWrite(final CompoundTag original) {
        return SafeSaveManager.injectChunkData((Object) this, original);
    }
}
