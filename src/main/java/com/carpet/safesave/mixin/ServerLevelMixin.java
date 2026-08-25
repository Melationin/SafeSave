package com.carpet.safesave.mixin;

import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.entity.ServerLevelTickListAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 世界刻调试输出、方块事件调试输出，以及 safe-save 的新加载区块统一重建。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements ServerLevelTickListAccess {

    /** 暴露 private 的 {@code entityTickList} 字段供实体顺序管理访问。 */
    @Accessor("entityTickList")
    @Override
    public abstract EntityTickList SS$getEntityTickList();

    /**
     * {@code ServerLevel.tick} 的 HEAD：输出请求的“打印世界刻”通道，外加每维度每非冻结 tick
     * 的计划刻/方块事件新加载区块统一重建。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void SS$onWorldTickHead(final BooleanSupplier haveTime, final CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        SafeSaveManager.onLevelTickStart(self);
    }

    /**
     * {@code ServerLevel.blockEvent} 的 TAIL：为每个成功加入队列的事件分配全局顺序号，
     * 供按区块保存方块事件后重建世界级执行顺序。
     */
    @Inject(method = "blockEvent", at = @At("TAIL"))
    private void SS$onBlockEvent(final BlockPos pos, final Block block, final int b0, final int b1, final CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        BlockEventManager.onBlockEvent(self, new BlockEventData(pos, block, b0, b1));
    }
}
