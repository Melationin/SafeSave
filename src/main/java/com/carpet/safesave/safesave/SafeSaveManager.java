package com.carpet.safesave.safesave;

import static com.carpet.safesave.util.DimensionIds.dimensionId;
import static com.carpet.safesave.util.Util.KEY_SAFE_SAVE;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.mixin.chunk.SerializableChunkDataAccess;
import com.carpet.safesave.safesave.chunk.ChunkNbtBridge;
import com.carpet.safesave.safesave.chunk.ChunkRebuildCoordinator;
import com.carpet.safesave.safesave.blockentity.PistonManager;
import com.carpet.safesave.safesave.entity.EntityOrderManager;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Set;

/**
 * SafeSave 门面：规则状态、生命周期入口与外部调用点（mixin）的委托中枢。
 *
 * <p>会话级可变状态（store、freezeArmed、各类计数、活塞序号）在 {@link SafeSaveSession}；
 * 每个 ServerLevel 的维度级状态（knownChunks、pendingChunks、方块事件顺序、实体顺序）在
 * {@link SafeSaveLevelState}，由 {@link com.carpet.safesave.safesave.SafeSaveLevelAccess} 暴露。
 * 本类所有方法保留原签名，内部通过 {@link SafeSaveSession#current()} 解析会话状态。
 *
 * <p>计划刻与方块事件随区块 NBT 的 {@code safeSave} 子节点持久化；世界级
 * {@code Level.subTickCount} 在旁置元数据文件 {@code safesave.dat} 中。
 */
public final class SafeSaveManager {

    public static final String RULE_NAME = "safeSave";

    private SafeSaveManager() {
    }

    // -----------------------------------------------------------------------
    // 规则与状态访问
    // -----------------------------------------------------------------------

    public static boolean enabled() {
        return SafeSaveRules.safeSave;
    }

    public static SafeSaveStore store() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? null : session.store;
    }

    public static int restoredTickCount() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0 : session.restoredTickCount.get();
    }

    public static int droppedTickCount() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0 : session.droppedTickCount.get();
    }

    public static int restoredBlockEventCount() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0 : session.restoredBlockEventCount.get();
    }

    public static int droppedBlockEventCount() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0 : session.droppedBlockEventCount.get();
    }

    public static int loadedTickCount() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0 : session.loadedTickCount.get();
    }

    public static int loadedBlockEventCount() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0 : session.loadedBlockEventCount.get();
    }

    public static int pendingChunkCount(final ServerLevel level) {
        return SafeSaveLevelAccess.of(level).pendingChunks.size();
    }

    /** 待恢复区块键集合（仅供调试命令 / 协调层使用）。 */
    public static LongSet pendingChunkKeys(final ServerLevel level) {
        return new LongOpenHashSet(SafeSaveLevelAccess.of(level).pendingChunks.keySet());
    }

    // -----------------------------------------------------------------------
    // 生命周期：服务器启动
    // -----------------------------------------------------------------------

    /**
     * 在 Carpet 的 {@code onServerLoaded}（{@code MinecraftServer.loadLevel} 的 HEAD，早于
     * {@code createLevels}/{@code prepareLevels}）处调用：绑定会话并读取旁置元数据；冻结在首刻前由 {@link #onFirstServerTick} 执行。
     */
    public static void onServerLoaded(final MinecraftServer server) {
        if (!enabled()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.begin();
        // 读取所有维度的旁置元数据文件（Level.subTickCount + 调试字段）。区块级快照在
        // 区块解析（SerializableChunkData.parse）时逐个登记到各 LevelState.pendingChunks。
        SafeSaveFiles.loadAll(server, session);
        // 冻结统一在 onFirstServerTick 执行（只冻一次）。这里只绑定会话并读旁置元数据。
    }

    /**
     * 在 {@code MinecraftServer.prepareLevels} 的 HEAD 处调用：对所有已创建的世界恢复
     * {@code Level.subTickCount}。此时 {@code createLevels} 已完成，但任何区块尚未准备好刻。
     */
    public static void onLevelsCreated(final MinecraftServer server) {
        if (!enabled()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveStore.DimensionData data = session.store.dimensionOrNull(dimensionId(level));
            if (data != null) {
                ScheduledTickManager.restoreSubTickCount(level, data);
            }
        }
    }

    /**
     * 在 {@code MinecraftServer.tickServer} 的 HEAD 处调用（该方法每个服务端刻都会运行）：
     * 用 {@code freezeArmed} 保证只在首刻前冻结一次，不会在用户 {@code /tick unfreeze} 后重新冻结。
     */
    public static void onFirstServerTick(final MinecraftServer server) {
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || !session.freezeArmed) {
            return;
        }
        session.freezeArmed = false;
        if (!enabled()) {
            return;
        }
        server.tickRateManager().setFrozen(true);
        DebugLog.info("froze the server before its first tick. "
                + "Run '/tick unfreeze' once you are happy with the restored state.");
    }

    // -----------------------------------------------------------------------
    // 生命周期：区块 NBT 解析与序列化
    // -----------------------------------------------------------------------

    /**
     * 在 {@code SerializableChunkData.parse} 的 HEAD 处调用：读取区块 NBT 的 {@code safeSave}
     * 子节点，登记为待恢复快照。
     */
    public static void onChunkTagRead(final ServerLevel level, final CompoundTag chunkData) {
        if (!enabled()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        ChunkNbtBridge.onChunkTagRead(level, chunkData, session, SafeSaveLevelAccess.of(level));
    }

    /**
     * 在 {@code SerializableChunkData.copyOf} 的 RETURN 处调用：为即将序列化的区块计算
     * safe-save 子节点，并通过 {@link SerializableChunkDataAccess} 暂存到 record 实例上，
     * 供后台写线程在 {@code write()} 时注入 NBT。
     */
    public static void onChunkSerializing(final ServerLevel level,
                                          final ChunkAccess chunk,
                                          final Object data) {
        if (!enabled()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        CompoundTag tag = ChunkNbtBridge.onChunkSerializing(level, chunk, session, SafeSaveLevelAccess.of(level));
        ((SerializableChunkDataAccess) data).SS$setSafeSaveTag(tag);
    }

    /**
     * 在 {@code SerializableChunkData.write} 的 RETURN 处调用：把 copyOf 阶段计算好的 tag
     * 注入区块 NBT。
     *
     * @return 注入后的 NBT；无 safe-save 数据时返回原 tag
     */
    public static CompoundTag injectChunkData(final Object data,
                                              final CompoundTag root) {
        CompoundTag tag = ((SerializableChunkDataAccess) data).SS$getSafeSaveTag();
        if (tag != null) {
            root.put(KEY_SAFE_SAVE, tag);
        }
        return root;
    }

    // -----------------------------------------------------------------------
    // 生命周期：tick 与保存
    // -----------------------------------------------------------------------

    /**
     * 在 {@code ServerLevel.tick} 的 HEAD 处调用：解冻后第一个正常 tick 统一重建
     * 新加载区块的计划刻/方块事件，并协调活塞代数与实体顺序。
     */
    public static void onLevelTickStart(final ServerLevel level) {
        if (!enabled()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        SafeSaveLevelState levelState = SafeSaveLevelAccess.of(level);
        // 活塞刻顺序重建必须在冻结期间也运行：ServerLevel.tick 本身不受 tickRateManager 门控，
        // 而 PME loadAdditional 发生在区块加载时（可能早于第一个非冻结 tick）。
        PistonManager.onLevelTickStart(level, session, levelState);
        if (!level.tickRateManager().runsNormally()) {
            return;
        }
        Set<Long> newChunks = ChunkRebuildCoordinator.rebuildNewChunks(level, session, levelState);
        EntityOrderManager.rebuildChunks(level, newChunks);
    }

    /**
     * 在 {@code MinecraftServer.saveAllChunks} 的 HEAD 处调用：写世界级旁置元数据。
     */
    public static void saveAll(final MinecraftServer server) {
        if (!enabled()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        SafeSaveFiles.saveAll(server, session);
    }

    /**
     * 在 {@code MinecraftServer.onServerClosed} 的 HEAD 处调用：写世界级旁置元数据并关闭会话。
     * 关闭后会话保留（不得 clear），因为原版在 onServerClosed 之后还会保存一次。
     */
    public static void onServerClosed(final MinecraftServer server) {
        if (!enabled()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        SafeSaveFiles.saveAll(server, session);
    }
}
