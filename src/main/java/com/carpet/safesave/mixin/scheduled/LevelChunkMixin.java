package com.carpet.safesave.mixin.scheduled;

import com.carpet.safesave.safesave.scheduled.SafeTickContainer;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 覆盖原版的刻重新锚定。
 *
 * <p>{@code unpackTicks(currentTick)} 把每个保存的 {@code SavedTick} 变成
 * {@code ScheduledTick(triggerTick = currentTick + delay, subTickOrder = -N..-1)}——绝对触发时间和
 * 全局顺序正是在这里丢失的。
 *
 * <p>HEAD 捕获<em>已经</em>排队的任何内容。那一刻 {@code pendingTicks} 尚未合并，
 * 因此已存在的只能是本会话期间、区块处于 {@code FULL} 但尚未方块刻时调度的刻
 * （例如跨区块边界的侦测器）。这些会在恢复之后重新加入，因此本功能绝不会比原版更差。
 *
 * <p>TAIL 让原版先完成（清空 {@code pendingTicks}），然后整体替换为 safe-save 保存的绝对数据。
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Unique
    private List<?> SS$preUnpackBlockTicks;

    @Unique
    private List<?> SS$preUnpackFluidTicks;

    @Inject(method = "unpackTicks", at = @At("HEAD"))
    private void SS$capturePreUnpackTicks(final long currentTick, final CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (!ScheduledTickManager.hasPendingRestore(self)) {
            return;
        }
        this.SS$preUnpackBlockTicks =
                ((SafeTickContainer) self.getBlockTicks()).SS$snapshotQueue();
        this.SS$preUnpackFluidTicks =
                ((SafeTickContainer) self.getFluidTicks()).SS$snapshotQueue();
    }

    @Inject(method = "unpackTicks", at = @At("TAIL"))
    private void SS$restoreAbsoluteTicks(final long currentTick, final CallbackInfo ci) {
        ScheduledTickManager.restoreChunk((LevelChunk) (Object) this,
                this.SS$preUnpackBlockTicks,
                this.SS$preUnpackFluidTicks);
        this.SS$preUnpackBlockTicks = null;
        this.SS$preUnpackFluidTicks = null;
    }
}
