package com.carpet.safesave.mixin.chunk;

import com.carpet.safesave.config.SafeSaveConfig;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.SafeSaveStore;
import com.carpet.safesave.safesave.blockentity.BlockEntityManager;
import com.carpet.safesave.safesave.blockentity.BlockEntityManager.PendingTicker;
import com.carpet.safesave.safesave.blockentity.PistonOrderHolder;
import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.chunk.ChunkTickerAccess;
import com.carpet.safesave.util.ChunkPosHelper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin implements ChunkTickerAccess {
    // 暂存活塞和漏斗的原版注册动作，排序后执行时才创建并加入实际 ticker。
    @Unique private List<PendingTicker> SS$pendingBlockEntityTickers;

    @Override
    public void SS$stageBlockEntityTicker(final PendingTicker ticker) {
        if (this.SS$pendingBlockEntityTickers == null) {
            this.SS$pendingBlockEntityTickers = new ArrayList<>();
        }
        this.SS$pendingBlockEntityTickers.add(ticker);
    }

    @Override
    public List<PendingTicker> SS$takeBlockEntityTickers() {
        List<PendingTicker> pending = this.SS$pendingBlockEntityTickers;
        this.SS$pendingBlockEntityTickers = null;
        return pending == null ? List.of() : pending;
    }

    @WrapOperation(method = "registerAllBlockEntitiesAfterLevelLoad", at = @At(value = "INVOKE",
            target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V"))
    private void SS$stageLoadedTickers(final Collection<BlockEntity> blockEntities,
                                       final Consumer<BlockEntity> register,
                                       final Operation<Void> original) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            original.call(blockEntities, register);
            return;
        }
        SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
        long key = ChunkPosHelper.pack(chunk.getPos());
        SafeSaveStore.ChunkSnapshot snapshot = state.loadedChunkSnapshots.remove(key);
        SafeSaveSession session = SafeSaveSession.of(level.getServer());
        boolean takeOver = SafeSaveManager.shouldRun(level.getServer())
                && (!SafeSaveConfig.of(level.getServer()).rebuildStartupOnly
                || !session.startupUnfrozen || state.chunksToRestore.contains(key));
        if (takeOver) {
            // 按新加载区块入队，不依赖快照内容或是否存在活塞、漏斗。
            state.pendingChunks.putIfAbsent(key,
                    snapshot == null ? SafeSaveStore.EMPTY_CHUNK_SNAPSHOT : snapshot);
        }
        original.call(blockEntities, (Consumer<BlockEntity>) blockEntity -> {
            if (takeOver && BlockEntityManager.stageLoadedTicker(chunk, blockEntity, register)) {
                return;
            }
            if (SafeSaveManager.shouldRun(level.getServer()) && blockEntity instanceof PistonOrderHolder piston) {
                piston.SS$rebaseTime(level.getGameTime());
            }
            register.accept(blockEntity);
        });
    }
}
