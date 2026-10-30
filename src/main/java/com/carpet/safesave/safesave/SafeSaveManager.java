package com.carpet.safesave.safesave;

import static com.carpet.safesave.util.SafeSaveNbt.KEY_SAFE_SAVE;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.config.SafeSaveConfig;
import com.carpet.safesave.util.ChunkPosHelper;
import com.carpet.safesave.safesave.chunk.SerializableChunkDataAccess;
import com.carpet.safesave.safesave.chunk.ChunkNbtBridge;
import com.carpet.safesave.safesave.chunk.ChunkRebuildCoordinator;
import com.carpet.safesave.safesave.entity.EntityOrderManager;
import com.carpet.safesave.safesave.startup.StartupChunkRecovery;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Locale;
import java.util.function.BooleanSupplier;

public final class SafeSaveManager {

    private SafeSaveManager() {
    }

    public static boolean shouldRun(final MinecraftServer server) {
        return SafeSaveConfig.of(server).safeSave;
    }

    private static boolean capturesChunk(ServerLevel level, long key) {
        SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
        return shouldRun(level.getServer()) || state.pendingChunks.containsKey(key);
    }

    public static void onLevelsCreated(final MinecraftServer server) {
        SafeSaveSession session = SafeSaveSession.of(server);
        session.serverGameTime = server.overworld().getGameTime();
        if (!shouldRun(server)) {
            return;
        }
        int loaded = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (SafeSaveFiles.loadForLevel(level, session)) {
                loaded++;
            }
            ScheduledTickManager.restoreSubTickCount(level, SafeSaveLevelAccess.of(level).savedDimension);
        }
        if (loaded == 0) {
            DebugLog.debug("no {} found; this session starts from vanilla chunk ticks", SafeSaveFiles.FILE_NAME);
        } else {
            DebugLog.info("loaded {} world metadata file(s)", loaded);
        }
    }


    public static void onFirstServerTick(final MinecraftServer server) {
        SafeSaveSession session = SafeSaveSession.of(server);
        if (session.freezeArmed) {
            session.freezeArmed = false;
            StartupChunkRecovery.arm(server);
        }
        StartupChunkRecovery.update(server, session);
        // 没有启动屏障时，以首次正常运行作为启动接管窗口的结束。
        if (!server.tickRateManager().isFrozen()) {
            session.startupUnfrozen = true;
        }
    }

    public static void onServerTickChildrenStart(final MinecraftServer server) {
        SafeSaveSession.of(server).serverTickRunning = true;
    }

    public static void onPlayerJoined(final ServerPlayer player) {
        SafeSaveSession session = SafeSaveSession.of(player.level().getServer());
        StartupChunkRecovery.onPlayerJoined(player, session);
    }

    public static void onChunkTagRead(final ServerLevel level, final CompoundTag chunkData) {
        long key = ChunkPosHelper.pack(chunkData.getIntOr("xPos", 0), chunkData.getIntOr("zPos", 0));
        if (!capturesChunk(level, key)) {
            return;
        }
        ChunkNbtBridge.onChunkTagRead(level, chunkData, SafeSaveLevelAccess.of(level));
    }

    public static void onChunkSerializing(final ServerLevel level,
                                          final ChunkAccess chunk,
                                          final Object data) {
        if (!capturesChunk(level, ChunkPosHelper.pack(chunk.getPos()))) {
            return;
        }
        CompoundTag tag = ChunkNbtBridge.onChunkSerializing(level, chunk, SafeSaveLevelAccess.of(level));
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

    // 1.21.1: ChunkSerializer#write 直接拿得到活 chunk，没有两阶段交接，tag 就地算完。
    public static CompoundTag injectChunkData(final ServerLevel level,
                                              final ChunkAccess chunk,
                                              final CompoundTag root) {
        if (!capturesChunk(level, ChunkPosHelper.pack(chunk.getPos()))) {
            return root;
        }
        CompoundTag tag = ChunkNbtBridge.onChunkSerializing(level, chunk, SafeSaveLevelAccess.of(level));
        if (tag != null) {
            root.put(KEY_SAFE_SAVE, tag);
        }
        return root;
    }

    public static void onLevelTickStart(final ServerLevel level) {
        StartupChunkRecovery.enforceFreeze(level);
        SafeSaveLevelState levelState = SafeSaveLevelAccess.of(level);
        if (levelState.pendingChunks.isEmpty()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.of(level.getServer());
        if (session.freezeArmed || levelState.startupBarrierPending
                || !level.tickRateManager().runsNormally()) {
            return;
        }
        int pending = DebugLog.DEBUG ? levelState.pendingChunks.size() : 0;
        long startedAt = DebugLog.DEBUG ? System.nanoTime() : 0L;
        LongSet chunks = ChunkRebuildCoordinator.rebuildNewChunks(level, levelState);
        EntityOrderManager.rebuildChunks(level, chunks);
        if (DebugLog.DEBUG && !chunks.isEmpty()) {
            double elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0;
            String timing = String.format(Locale.ROOT,
                    "%s: restore took %.3f ms (pending chunks=%d, server tick=%d)",
                    com.carpet.safesave.util.Util.dimensionId(level), elapsedMs,
                    pending, level.getServer().getTickCount());
            DebugLog.debug("{}", timing);
        }
    }

    public static boolean canCaptureSnapshot(final ServerLevel level) {
        SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
        return !state.worldTickRunning && state.completedWorldTick == level.getServer().getTickCount();
    }

    public static boolean isTickEndPending(MinecraftServer server) {
        SafeSaveSession session = SafeSaveSession.of(server);
        return session.serverTickRunning
                && session.finalizedServerTick != server.getTickCount();
    }

    public static boolean shouldDeferChunkMapSave(ServerLevel level) {
        SafeSaveSession session = SafeSaveSession.of(level.getServer());
        return shouldRun(level.getServer()) && !session.freezeArmed
                && !level.getServer().isStopped()
                && (isTickEndPending(level.getServer()) || !canCaptureSnapshot(level));
    }

    public static void onServerTickEnd(final MinecraftServer server, final BooleanSupplier haveTime) {
        SafeSaveSession session = SafeSaveSession.of(server);
        session.finalizedServerTick = server.getTickCount();
        session.serverTickRunning = false;
        if (session.deferredSaveEverything || session.deferredSaveAllChunks) {
            boolean everything = session.deferredSaveEverything;
            boolean silent = session.deferredSilent;
            boolean flush = session.deferredFlush;
            boolean force = session.deferredForce;
            session.deferredSaveEverything = false;
            session.deferredSaveAllChunks = false;
            session.deferredSilent = true;
            session.deferredFlush = false;
            session.deferredForce = false;
            if (everything) {
                server.saveEverything(silent, flush, force);
            } else {
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

    public static boolean deferSaveEverything(MinecraftServer server,
                                              boolean silent, boolean flush, boolean force) {
        SafeSaveSession session = SafeSaveSession.of(server);
        if (!needsTickEndSave(server, session)) return false;
        session.deferredSaveEverything = true;
        rememberSaveFlags(session, silent, flush, force);
        return true;
    }

    public static boolean deferSaveAllChunks(MinecraftServer server,
                                             boolean silent, boolean flush, boolean force) {
        SafeSaveSession session = SafeSaveSession.of(server);
        if (!needsTickEndSave(server, session)) return false;
        session.deferredSaveAllChunks = true;
        rememberSaveFlags(session, silent, flush, force);
        return true;
    }

    private static boolean needsTickEndSave(MinecraftServer server, SafeSaveSession session) {
        if (!shouldRun(server) || session.freezeArmed || server.isStopped()) return false;
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


    public static void saveAll(final MinecraftServer server) {
        save(server, !server.isStopped());
    }


    private static void save(final MinecraftServer server, final boolean captureTicking) {
        if (!shouldRun(server)) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.of(server);
        if (session.freezeArmed) {
            return;
        }
        SafeSaveFiles.saveAll(server, session, captureTicking);
    }
}
