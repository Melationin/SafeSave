package com.carpet.safesave.safesave;

import static com.carpet.safesave.util.SafeSaveNbt.KEY_SAFE_SAVE;
import static com.carpet.safesave.util.Util.dimensionId;

import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.chunk.SerializableChunkDataAccess;
import com.carpet.safesave.safesave.chunk.ChunkNbtBridge;
import com.carpet.safesave.safesave.chunk.ChunkRebuildCoordinator;
import com.carpet.safesave.safesave.blockentity.PistonManager;
import com.carpet.safesave.safesave.entity.EntityOrderManager;
import com.carpet.safesave.safesave.startup.StartupChunkRecovery;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Set;
import java.util.function.BooleanSupplier;

public final class SafeSaveManager {

    private SafeSaveManager() {
    }

    public static boolean shouldRun() {
        return SafeSaveRules.safeSave;
    }

    private static boolean capturesChunk(ServerLevel level, long key) {
        SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
        return shouldRun() || state.pendingChunks.containsKey(key);
    }

    public static void onServerLoaded(final MinecraftServer server) {
        if (!shouldRun()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.begin();
        SafeSaveFiles.loadAll(server, session);
    }

    public static void onLevelsCreated(final MinecraftServer server) {
        if (!shouldRun()) {
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


    public static void onFirstServerTick(final MinecraftServer server) {
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null) {
            return;
        }
        if (session.freezeArmed) {
            session.freezeArmed = false;
            StartupChunkRecovery.arm(server, session);
        }
        StartupChunkRecovery.update(server, session);
    }

    public static void onServerTickChildrenStart() {
        SafeSaveSession session = SafeSaveSession.current();
        if (session != null) session.serverTickRunning = true;
    }

    public static void onPlayerJoined(final ServerPlayer player) {
        SafeSaveSession session = SafeSaveSession.current();
        if (session != null) StartupChunkRecovery.onPlayerJoined(player, session);
    }

    public static void onChunkTagRead(final ServerLevel level, final CompoundTag chunkData) {
        long key = ChunkPos.pack(chunkData.getIntOr("xPos", 0), chunkData.getIntOr("zPos", 0));
        if (!capturesChunk(level, key)) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        ChunkNbtBridge.onChunkTagRead(level, chunkData, session, SafeSaveLevelAccess.of(level));
    }

    public static void onChunkSerializing(final ServerLevel level,
                                          final ChunkAccess chunk,
                                          final Object data) {
        if (!capturesChunk(level, chunk.getPos().pack())) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        CompoundTag tag = ChunkNbtBridge.onChunkSerializing(level, chunk, session, SafeSaveLevelAccess.of(level));
        ((SerializableChunkDataAccess) data).SS$setSafeSaveTag(tag);
    }

    public static CompoundTag injectChunkData(final Object data,
                                              final CompoundTag root) {
        CompoundTag tag = ((SerializableChunkDataAccess) data).SS$getSafeSaveTag();
        if (tag != null) {
            root.put(KEY_SAFE_SAVE, tag);
        }
        return root;
    }

    public static void onLevelTickStart(final ServerLevel level) {
        SafeSaveSession startupSession = SafeSaveSession.current();
        if (startupSession != null) StartupChunkRecovery.enforceFreeze(level, startupSession);
        if (!shouldRun() && SafeSaveLevelAccess.of(level).pendingChunks.isEmpty()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        SafeSaveLevelState levelState = SafeSaveLevelAccess.of(level);
        if (shouldRun()) {
            // 活塞刻顺序重建必须在冻结期间也运行：ServerLevel.tick 本身不受 tickRateManager 门控，
            // 而 PME loadAdditional 发生在区块加载时（可能早于第一个非冻结 tick）。
            PistonManager.onLevelTickStart(level, session, levelState);
        }
        if (!level.tickRateManager().runsNormally()) {
            return;
        }
        if (shouldRun() || !levelState.pendingChunks.isEmpty()) {
            Set<Long> newChunks = ChunkRebuildCoordinator.rebuildNewChunks(level, session, levelState);
            EntityOrderManager.rebuildChunks(level, newChunks);
        }
    }

    public static boolean canCaptureSnapshot(final ServerLevel level) {
        SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
        return !state.worldTickRunning && state.completedWorldTick == level.getServer().getTickCount();
    }

    public static boolean isTickEndPending(MinecraftServer server) {
        SafeSaveSession session = SafeSaveSession.current();
        return session != null && session.serverTickRunning
                && session.finalizedServerTick != server.getTickCount();
    }

    public static boolean shouldDeferChunkMapSave(ServerLevel level) {
        SafeSaveSession session = SafeSaveSession.current();
        return shouldRun() && session != null && !session.freezeArmed
                && !level.getServer().isStopped()
                && (isTickEndPending(level.getServer()) || !canCaptureSnapshot(level));
    }

    public static void onServerTickEnd(final MinecraftServer server, final BooleanSupplier haveTime) {
        SafeSaveSession session = SafeSaveSession.current();
        if (session != null) {
            session.finalizedServerTick = server.getTickCount();
            session.serverTickRunning = false;
            if (session.deferredSaveEverything) {
                boolean silent = session.deferredSilent;
                boolean flush = session.deferredFlush;
                boolean force = session.deferredForce;
                clearDeferredServerSave(session);
                server.saveEverything(silent, flush, force);
            } else if (session.deferredSaveAllChunks) {
                boolean silent = session.deferredSilent;
                boolean flush = session.deferredFlush;
                boolean force = session.deferredForce;
                clearDeferredServerSave(session);
                server.saveAllChunks(silent, flush, force);
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
            if (state.deferredFullSave) {
                boolean flush = state.deferredFullSaveFlush;
                state.deferredFullSave = false;
                state.deferredFullSaveFlush = false;
                level.getChunkSource().chunkMap.saveAllChunks(flush);
            }
            if (state.deferredUnloads) {
                state.deferredUnloads = false;
                level.getChunkSource().chunkMap.processUnloads(haveTime);
            }
        }
    }

    private static void clearDeferredServerSave(SafeSaveSession session) {
        session.deferredSaveEverything = false;
        session.deferredSaveAllChunks = false;
        session.deferredSilent = true;
        session.deferredFlush = false;
        session.deferredForce = false;
    }

    public static boolean deferSaveEverything(MinecraftServer server,
                                              boolean silent, boolean flush, boolean force) {
        SafeSaveSession session = SafeSaveSession.current();
        if (!needsTickEndSave(server, session)) return false;
        session.deferredSaveEverything = true;
        rememberSaveFlags(session, silent, flush, force);
        return true;
    }

    public static boolean deferSaveAllChunks(MinecraftServer server,
                                             boolean silent, boolean flush, boolean force) {
        SafeSaveSession session = SafeSaveSession.current();
        if (!needsTickEndSave(server, session)) return false;
        session.deferredSaveAllChunks = true;
        rememberSaveFlags(session, silent, flush, force);
        return true;
    }

    private static boolean needsTickEndSave(MinecraftServer server, SafeSaveSession session) {
        if (!shouldRun() || session == null || session.store == null
                || session.freezeArmed || server.isStopped()) return false;
        return isTickEndPending(server);
    }

    private static void rememberSaveFlags(SafeSaveSession session,
                                          boolean silent, boolean flush, boolean force) {
        session.deferredSilent &= silent;
        session.deferredFlush |= flush;
        session.deferredForce |= force;
    }

    public static void saveAtShutdown(final MinecraftServer server) {
        save(server, true);
    }

    /*
      挂在 MinecraftServer.saveAllChunks 的 HEAD（自动保存、save-all、关闭时的最终保存）。
      区块数据由 SerializableChunkDataMixin 在随后的每个区块保存中写入。
     */
    public static void saveAll(final MinecraftServer server) {
        save(server, !server.isStopped());
    }

    /*
      模拟等级清单只在保存时采集。stopServer 的 HEAD 处区块尚未卸载，由 saveAtShutdown 采一次；
      此后的最终 flush 时 isStopped() 已为 true，不再重采，否则会扫描到已被卸载的区块表。
     */
    private static void save(final MinecraftServer server, final boolean captureTicking) {
        if (!shouldRun()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null || session.freezeArmed) {
            return;
        }
        if (captureTicking) {
            StartupChunkRecovery.captureTicking(server, session);
        }
        SafeSaveFiles.saveAll(server, session);
    }
}
