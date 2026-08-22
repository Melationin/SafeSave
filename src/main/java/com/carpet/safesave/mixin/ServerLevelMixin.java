package com.carpet.safesave.mixin;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.debug.DebugSwitches;
import com.carpet.safesave.safesave.SafeSaveManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/**
 * World-tick debug output, block-event debug output, and the safe-save chunk-unload snapshot.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    /**
     * Head of {@code ServerLevel.tick}: the requested "print the world tick" channel, plus the
     * one-shot per-dimension restore sweep.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void SS$onWorldTickHead(final BooleanSupplier haveTime, final CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        SafeSaveManager.onLevelTickStart(self);
        if (DebugSwitches.DEBUG) {
            DebugLog.worldTickStart(self, self.getServer().getTickCount(), !self.tickRateManager().runsNormally());
        }
    }

    /**
     * {@code blockEvents} is a {@code Set}, so an identical event queued twice in one tick is
     * silently dropped. Sampling {@code contains} at HEAD is the only way to observe that.
     */
    @Inject(method = "blockEvent", at = @At("HEAD"))
    private void SS$onBlockEventAdded(final BlockPos pos,
                                                 final Block block,
                                                 final int paramA,
                                                 final int paramB,
                                                 final CallbackInfo ci) {
        if (!DebugSwitches.DEBUG || !DebugSwitches.isEnabled(DebugSwitches.Channel.BLOCK_EVENTS)) {
            return;
        }
        ServerLevel self = (ServerLevel) (Object) this;
        var queue = self.blockEvents;
        boolean accepted = !queue.contains(new BlockEventData(pos, block, paramA, paramB));
        DebugLog.blockEventAdded(self, pos, block, paramA, paramB, accepted,
                accepted ? queue.size() + 1 : queue.size());
    }

    @Inject(method = "doBlockEvent", at = @At("RETURN"))
    private void SS$onBlockEventRun(final BlockEventData eventData,
                                               final CallbackInfoReturnable<Boolean> cir) {
        if (!DebugSwitches.DEBUG || !DebugSwitches.isEnabled(DebugSwitches.Channel.BLOCK_EVENTS)) {
            return;
        }
        DebugLog.blockEventRun((ServerLevel) (Object) this, eventData, cir.getReturnValueZ());
    }

    /**
     * Head of {@code ServerLevel.unload}: the last moment at which the chunk's tick containers are
     * still registered with the level, so absolute timings can be captured before they are dropped.
     */
    @Inject(method = "unload", at = @At("HEAD"))
    private void SS$onChunkUnload(final LevelChunk levelChunk, final CallbackInfo ci) {
        SafeSaveManager.snapshotChunk((ServerLevel) (Object) this, levelChunk);
    }
}
