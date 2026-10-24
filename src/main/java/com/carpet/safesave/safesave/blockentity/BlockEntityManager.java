package com.carpet.safesave.safesave.blockentity;


import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.chunk.ChunkTickerAccess;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import static com.carpet.safesave.util.Util.dimensionId;


public final class BlockEntityManager {

    private BlockEntityManager() {
    }

    public static long nextBlockEntityOrder(final ServerLevel level) {
        return SafeSaveLevelAccess.of(level).blockEntityOrder.next();
    }

    public static void observeBlockEntityOrder(final ServerLevel level, final long restored) {
        SafeSaveLevelAccess.of(level).blockEntityOrder.observe(restored);
    }

    public static boolean stageLoadedTicker(final LevelChunk chunk,
                                            final BlockEntity blockEntity,
                                            final Consumer<BlockEntity> register) {
        if (!(blockEntity instanceof HopperBlockEntity || blockEntity instanceof PistonMovingBlockEntity)
                || !(blockEntity instanceof BlockEntityOrderHolder holder)
                || !holder.SS$hasLoadedBlockEntityOrder()) {
            return false;
        }
        ((ChunkTickerAccess) chunk).SS$stageBlockEntityTicker(new PendingTicker(blockEntity, register));
        return true;
    }

    public static void restoreTickers(final ServerLevel level, final LongCollection chunks) {
        if (chunks.isEmpty()) return;
        List<PendingTicker> ordered = new ArrayList<>();
        for (LongIterator iterator = chunks.iterator(); iterator.hasNext();) {
            long key = iterator.nextLong();
            var chunk = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            for (PendingTicker ticker : ((ChunkTickerAccess) chunk).SS$takeBlockEntityTickers()) {
                BlockEntity blockEntity = ticker.blockEntity();
                if (!blockEntity.isRemoved()
                        && chunk.getBlockEntities().get(blockEntity.getBlockPos()) == blockEntity
                        && blockEntity instanceof BlockEntityOrderHolder holder) {
                    if (holder instanceof PistonOrderHolder piston) {
                        piston.SS$rebaseTime(SafeSaveSession.of(level.getServer()).serverGameTime);
                    }
                    // 活塞和漏斗都进入同一个列表，按共用序号恢复相对执行顺序。
                    ordered.add(ticker);
                }
            }
        }
        ordered.sort(Comparator.comparingLong(
                entry -> ((BlockEntityOrderHolder) entry.blockEntity()).SS$blockEntityOrder()));
        for (PendingTicker entry : ordered) {
            entry.register().accept(entry.blockEntity());
        }
        if (!ordered.isEmpty() && DebugLog.DEBUG) {
            DebugLog.debug("{}: registered {} piston/hopper ticker(s) from this chunk batch in saved order",
                    dimensionId(level), ordered.size());
        }
    }

    public record PendingTicker(BlockEntity blockEntity, Consumer<BlockEntity> register) {}
}
