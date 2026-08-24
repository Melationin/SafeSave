package com.carpet.safesave.mixin;

import com.carpet.safesave.safesave.SafeSaveManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/**
 * 服务端级 safe-save 接线：世界绑定、首刻前冻结，以及保存快照。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    /**
     * {@code prepareLevels} 在 {@code createLevels} 之后、任何区块被准备好用于刻之前运行，
     * 且仍在 {@code loadLevel} 内部——即 Carpet 的 {@code onServerLoaded} 已经读取旁置文件之后。
     * 这使它成为世界与恢复数据同时存在的最早时机，正是恢复 {@code Level.subTickCount} 所需的。
     */
    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void SS$onLevelsCreated(final CallbackInfo ci) {
        SafeSaveManager.onLevelsCreated((MinecraftServer) (Object) this);
    }

    /** 在第一个服务端刻之前冻结，确保恢复被确认前一切不推进。 */
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void SS$beforeFirstServerTick(final BooleanSupplier haveTime, final CallbackInfo ci) {
        SafeSaveManager.onFirstServerTick((MinecraftServer) (Object) this);
    }

    /**
     * 用 HEAD 而非 RETURN：当 {@code flush=true} 时原版会在保存期间运行 {@code processUnloads}，
     * 注销刻容器，因此到 RETURN 时世界的一部分已经从 {@code LevelTicks.allContainers} 中消失。
     */
    @Inject(method = "saveAllChunks", at = @At("HEAD"))
    private void SS$onSaveAllChunks(final boolean silent,
                                               final boolean flush,
                                               final boolean force,
                                               final CallbackInfoReturnable<Boolean> cir) {
        SafeSaveManager.saveAll((MinecraftServer) (Object) this);
    }
}
