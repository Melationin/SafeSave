package com.carpet.safesave.mixin;

import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.entity.ServerLevelTickListAccess;
import com.carpet.safesave.safesave.region.ProtectedRegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/**
 * 世界刻调试输出、方块事件调试输出，以及 safe-save 的新加载区块统一重建。
 *
 * <p>同时通过 {@code @Unique} 字段实现 {@link SafeSaveLevelAccess}：safe-save 的维度级状态
 * 直接挂在 {@code ServerLevel} 实例上，随世界创建/丢弃天然隔离。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements ServerLevelTickListAccess, SafeSaveLevelAccess {

    /** safe-save 维度级状态；构造期初始化，parse 线程经 {@link SafeSaveLevelAccess} 读取。 */
    @Unique
    private final SafeSaveLevelState SS$safeSaveLevelState = new SafeSaveLevelState();

    /** 暴露 private 的 {@code entityTickList} 字段供实体顺序管理访问。 */
    @Accessor("entityTickList")
    @Override
    public abstract EntityTickList SS$getEntityTickList();

    @Override
    public SafeSaveLevelState SS$safeSaveLevelState() {
        return this.SS$safeSaveLevelState;
    }

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

    /**
     * ProtectedRegion 门控：冻结 region 的区块不执行计划刻/方块事件/方块实体
     * （vanilla 这三类 tick 都经由 {@code ServerLevel.shouldTickBlocksAt} 判定）。
     */
    @Inject(method = "shouldTickBlocksAt(J)Z", at = @At("HEAD"), cancellable = true)
    private void SS$gateShouldTickBlocksAt(final long chunkPos, final CallbackInfoReturnable<Boolean> cir) {
        if (ProtectedRegionManager.isChunkFrozen((ServerLevel) (Object) this, chunkPos)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * ProtectedRegion 门控：随机刻/雨雪/冰直接走 {@code ServerLevel.tickChunk}，
     * 不经过 {@code shouldTickBlocksAt}，需要单独拦截。
     */
    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void SS$gateTickChunk(final LevelChunk chunk, final int tickSpeed, final CallbackInfo ci) {
        if (ProtectedRegionManager.isChunkFrozen((ServerLevel) (Object) this, chunk.getPos().pack())) {
            ci.cancel();
        }
    }

    /**
     * ProtectedRegion 门控：实体 tick。玩家始终 tick；其余实体所在区块被冻结时暂停。
     */
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void SS$gateTickNonPassenger(final Entity entity, final CallbackInfo ci) {
        if (!(entity instanceof ServerPlayer)
                && entity.level() == (Object) this
                && ProtectedRegionManager.isChunkFrozen((ServerLevel) (Object) this, entity.chunkPosition().pack())) {
            ci.cancel();
        }
    }

    /**
     * ProtectedRegion 门控：雷暴。属于 {@code tickSpawningChunk} 路径，不经过
     * {@code shouldTickBlocksAt}。
     */
    @Inject(method = "tickThunder", at = @At("HEAD"), cancellable = true)
    private void SS$gateTickThunder(final LevelChunk chunk, final CallbackInfo ci) {
        if (ProtectedRegionManager.isChunkFrozen((ServerLevel) (Object) this, chunk.getPos().pack())) {
            ci.cancel();
        }
    }
}
