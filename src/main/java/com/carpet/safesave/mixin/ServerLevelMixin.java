package com.carpet.safesave.mixin;

import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.carpet.safesave.safesave.region.RegionLifecycle;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BooleanSupplier;

/**
 * 维度级状态直接挂在 {@code ServerLevel} 实例上，随世界创建/丢弃天然隔离。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements SafeSaveLevelAccess {

    /** parse 线程经 {@link SafeSaveLevelAccess} 读取。 */
    @Unique
    private final SafeSaveLevelState SS$safeSaveLevelState = new SafeSaveLevelState();

    @Override
    public SafeSaveLevelState SS$safeSaveLevelState() {
        return this.SS$safeSaveLevelState;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void SS$onWorldTickHead(final BooleanSupplier haveTime, final CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        SafeSaveManager.onLevelTickStart(self);
    }

    // Chunks promoted in the middle of a tick wait for the next HEAD barrier.
    @Inject(method = "shouldTickBlocksAt(J)Z", at = @At("HEAD"), cancellable = true)
    private void SS$gateBlocks(long key, CallbackInfoReturnable<Boolean> cir) {
        if (!RegionLifecycle.maySimulate((ServerLevel) (Object) this, key)) cir.setReturnValue(false);
    }

    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void SS$gateRandomTicks(LevelChunk chunk, int speed, CallbackInfo ci) {
        if (!RegionLifecycle.maySimulate((ServerLevel) (Object) this, chunk.getPos().pack())) ci.cancel();
    }

    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void SS$gateEntity(Entity entity, CallbackInfo ci) {
        if (!(entity instanceof ServerPlayer)
                && !RegionLifecycle.maySimulate((ServerLevel) (Object) this, entity.chunkPosition().pack())) ci.cancel();
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void SS$gatePassenger(Entity vehicle, Entity entity, CallbackInfo ci) {
        if (!(entity instanceof ServerPlayer)
                && !RegionLifecycle.maySimulate((ServerLevel) (Object) this, entity.chunkPosition().pack())) ci.cancel();
    }

    /**
     * {@code ServerLevel.blockEvent} 的 TAIL：仅对成功入队的事件分配全局顺序号，
     * 供按区块保存方块事件后重建世界级执行顺序。
     */
    @Inject(method = "blockEvent", at = @At("TAIL"))
    private void SS$onBlockEvent(final BlockPos pos, final Block block, final int b0, final int b1, final CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        BlockEventManager.onBlockEvent(self, new BlockEventData(pos, block, b0, b1));
    }

}
