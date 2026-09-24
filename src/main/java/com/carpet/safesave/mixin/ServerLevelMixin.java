package com.carpet.safesave.mixin;

import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/*
 * 维度级状态直接挂在 ServerLevel 实例上，随世界创建/丢弃天然隔离。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements SafeSaveLevelAccess {

    // parse 线程经 SafeSaveLevelAccess 读取。
    @Unique
    private final SafeSaveLevelState SS$safeSaveLevelState = new SafeSaveLevelState();

    @Override
    public SafeSaveLevelState SS$safeSaveLevelState() {
        return this.SS$safeSaveLevelState;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void SS$onWorldTickHead(final BooleanSupplier haveTime, final CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        this.SS$safeSaveLevelState.worldTickRunning = true;
        SafeSaveManager.onLevelTickStart(self);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void SS$onWorldTickEnd(final BooleanSupplier haveTime, final CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        this.SS$safeSaveLevelState.worldTickRunning = false;
        this.SS$safeSaveLevelState.completedWorldTick = self.getServer().getTickCount();
    }

    /*
     * ServerLevel.blockEvent 的入队点：直接给原版刚构造的 BlockEventData 分配全局顺序号，
     * 供按区块保存方块事件后重建世界级执行顺序。复用同一个实例，不再多造一个副本。
     */
    @ModifyArg(method = "blockEvent", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectLinkedOpenHashSet;add(Ljava/lang/Object;)Z"))
    private Object SS$assignBlockEventOrder(final Object event) {
        BlockEventManager.assignOrder((ServerLevel) (Object) this, (BlockEventData) event);
        return event;
    }

}
