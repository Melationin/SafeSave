package com.carpet.safesave.safesave.chunk;

import static com.carpet.safesave.util.DimensionIds.dimensionId;
import static com.carpet.safesave.util.SafeSaveNbt.KEY_SAFE_SAVE;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.SafeSaveStore;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.blockevent.SafeBlockEvent;
import com.carpet.safesave.safesave.scheduled.SafeTickContainer;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.TickContainerAccess;

import java.util.List;

/**
 * 区块 NBT 的 safe-save 子节点读写与 load→rebuild 窗口保护。
 *
 * <p>{@code SerializableChunkData.parse} 是唯一能看到原始区块 NBT 的加载点（且第一个参数就是
 * {@code ServerLevel}，因此维度已知）；{@code copyOf}/{@code write} 是保存时唯一能拿到世界与
 * 最终 NBT 的点。本类只做“暂存/注入”，tag 在 record 实例上的交接由
 * {@code SerializableChunkDataMixin} 完成。
 */
public final class ChunkNbtBridge {

    private ChunkNbtBridge() {
    }

    /**
     * 在 {@code SerializableChunkData.parse} 的 HEAD 处调用：读取区块 NBT 中的
     * {@code safeSave} 子节点，登记为待恢复快照。
     */
    public static void onChunkTagRead(final ServerLevel level, final CompoundTag chunkData,
                                      final SafeSaveSession session, final SafeSaveLevelState levelState) {
        if (session.store == null) {
            return;
        }
        String dimension = dimensionId(level);
        long key = ChunkPos.pack(chunkData.getIntOr("xPos", 0), chunkData.getIntOr("zPos", 0));
        CompoundTag safeSave = chunkData.getCompound(KEY_SAFE_SAVE).orElse(null);
        if (safeSave == null) {
            ChunkRebuildCoordinator.removePending(levelState, key, session);
            return;
        }
        SafeSaveStore.ChunkSnapshot snapshot = SafeSaveStore.loadChunkData(safeSave);
        // 同一区块在同一会话内卸载→重载时，先冲销旧快照的计数，避免 /safesave status 重复累计。
        SafeSaveStore.ChunkSnapshot old = levelState.pendingChunks.get(key);
        if (old != null) {
            session.loadedTickCount.addAndGet(-(old.blockTicks().size() + old.fluidTicks().size()));
            session.loadedBlockEventCount.addAndGet(-old.blockEvents().size());
        }
        if (snapshot == null || snapshot.isEmpty()) {
            levelState.pendingChunks.remove(key);
            return;
        }
        levelState.pendingChunks.put(key, snapshot);
        session.loadedTickCount.addAndGet(snapshot.blockTicks().size() + snapshot.fluidTicks().size());
        session.loadedBlockEventCount.addAndGet(snapshot.blockEvents().size());
        DebugLog.info("{} {}: read {} block + {} fluid tick(s), {} block event(s) from chunk NBT",
                dimension, ChunkPos.unpack(key),
                snapshot.blockTicks().size(), snapshot.fluidTicks().size(), snapshot.blockEvents().size());
    }

    /**
     * 在 {@code SerializableChunkData.copyOf} 的 RETURN 处调用：为即将序列化的区块计算
     * safe-save 子节点。
     *
     * <p>load→rebuild 窗口保护：若该区块仍有待恢复快照（还没在非冻结 tick 开头重建），
     * 则把待恢复快照写回区块 NBT，而不是 vanilla 重新锚定后的临时容器内容。
     *
     * @return 需要挂到区块 NBT 的 safe-save 子节点；为空时返回 {@code null}
     */
    public static CompoundTag onChunkSerializing(final ServerLevel level, final ChunkAccess chunk,
                                                 final SafeSaveSession session,
                                                 final SafeSaveLevelState levelState) {
        if (session.store == null) {
            return null;
        }
        if (!(chunk instanceof LevelChunk)) {
            return null;
        }
        long key = chunk.getPos().pack();

        // 窗口保护：待恢复快照只有在 rebuildNewChunks 消费后才会移除。这里只读取（peek），
        // 这样在 load→rebuild 窗口内被保存多少次，写回磁盘的都是原始绝对快照。
        SafeSaveStore.ChunkSnapshot snapshot = levelState.pendingChunks.get(key);
        if (snapshot == null) {
            if (!(chunk.getBlockTicks() instanceof SafeTickContainer)
                    || !(chunk.getFluidTicks() instanceof SafeTickContainer)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            TickContainerAccess<Block> blockAccess =
                    (TickContainerAccess<Block>) chunk.getBlockTicks();
            @SuppressWarnings("unchecked")
            TickContainerAccess<Fluid> fluidAccess =
                    (TickContainerAccess<Fluid>) chunk.getFluidTicks();
            ScheduledTickManager.ChunkTickSnapshot ticks =
                    ScheduledTickManager.snapshotChunkTicks(level, key, blockAccess, fluidAccess);
            if (ticks == null) {
                return null;
            }
            List<SafeBlockEvent> events = BlockEventManager.snapshotChunkEvents(level, key, levelState);
            if (ticks.isEmpty() && events.isEmpty()) {
                return null;
            }
            snapshot = new SafeSaveStore.ChunkSnapshot(ticks.blockTicks(), ticks.fluidTicks(), events,
                    level.getGameTime());
        }

        CompoundTag tag = SafeSaveStore.saveChunkData(snapshot);
        return tag.isEmpty() ? null : tag;
    }
}
