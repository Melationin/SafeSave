package com.carpet.safesave.mixin;

import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 世界刻调试输出、方块事件调试输出，以及 safe-save 的区块卸载快照。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    /**
     * {@code ServerLevel.tick} 的 HEAD：输出请求的“打印世界刻”通道，外加每维度的一次性恢复扫描。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void SS$onWorldTickHead(final BooleanSupplier haveTime, final CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        SafeSaveManager.onLevelTickStart(self);
    }


    /**
     * {@code ServerLevel.unload} 的 HEAD：区块的刻容器仍注册在世界中的最后时刻，
     * 可在它们被移除之前捕获绝对时间。
     */
    @Inject(method = "unload", at = @At("HEAD"))
    private void SS$onChunkUnload(final LevelChunk levelChunk, final CallbackInfo ci) {
        ScheduledTickManager.snapshotChunk((ServerLevel) (Object) this, levelChunk);
    }
}
