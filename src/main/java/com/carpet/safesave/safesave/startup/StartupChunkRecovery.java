package com.carpet.safesave.safesave.startup;

import carpet.patches.EntityPlayerMPFake;
import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.SafeSaveStore;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import static com.carpet.safesave.util.Util.dimensionId;

// Restores the loaded footprint of the last saved simulation tick without simulating it early.
public final class StartupChunkRecovery {
    private static final TicketType STARTUP_LOAD = new TicketType(0,
            TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    private StartupChunkRecovery() {}

    public static void initialize() {
        Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.parse("safesave:startup_load"), STARTUP_LOAD);
    }

    public static void arm(MinecraftServer server, SafeSaveSession session) {
        // 超时为 0 时完全不设屏障：不冻结，也不挂载入票。
        if (SafeSaveRules.safeSaveForceUnfreezeTimeout <= 0) {
            DebugLog.info("startup loading barrier disabled (safeSaveForceUnfreezeTimeout <= 0)");
            return;
        }
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveStore.DimensionData saved = session.store.dimensionOrNull(dimensionId(level));
            if (saved != null) total += saved.tickingChunks.size();
        }
        if (total == 0) {
            DebugLog.info("no level-31/32 chunks were recorded; startup needs no loading barrier");
            return;
        }
        server.tickRateManager().setFrozen(true);
        session.startupRecoveryWaiting = true;
        session.startupTicketsHeld = true;
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveStore.DimensionData saved = session.store.dimensionOrNull(dimensionId(level));
            if (saved == null) continue;
            SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
            for (Long2ByteMap.Entry entry : saved.tickingChunks.long2ByteEntrySet()) {
                byte ticketLevel = entry.getByteValue();
                if (ticketLevel != 31 && ticketLevel != 32) continue;
                long key = entry.getLongKey();
                state.startupTickets.put(key, ticketLevel);
                level.getChunkSource().ticketStorage.addTicket(key, new Ticket(STARTUP_LOAD, ticketLevel));
            }
            level.getChunkSource().runDistanceManagerUpdates();
        }
        DebugLog.info("startup frozen; loading {} previously ticking chunk(s), forced release after {} server ticks from the first real player",
                total, Math.max(0, SafeSaveRules.safeSaveForceUnfreezeTimeout));
    }

    public static void update(MinecraftServer server, SafeSaveSession session) {
        // 下面两段都不成立时无需每 tick 计算 now。
        if (!session.startupRecoveryWaiting && !session.startupTicketsHeld) {
            return;
        }
        int now = server.getTickCount();
        if (session.startupRecoveryWaiting) {
            if (!server.tickRateManager().isFrozen()) {
                server.tickRateManager().setFrozen(true);
                DebugLog.warn("startup loading barrier restored the server freeze before all targets were ready");
            }
            LoadStatus status = loadStatus(server);
            if (status.loaded == status.total) {
                finish(server, session, "after all chunks loaded");
            } else if (session.firstRealPlayerTick >= 0
                    && now - session.firstRealPlayerTick >= Math.max(0, SafeSaveRules.safeSaveForceUnfreezeTimeout)) {
                DebugLog.warn("startup chunk wait timed out: {}/{} loaded", status.loaded, status.total);
                finish(server, session, "after the loading timeout");
            } else if (now - session.startupLastLogTick >= 20) {
                session.startupLastLogTick = now;
                DebugLog.info("startup chunk wait: {}/{} loaded", status.loaded, status.total);
            }
        }
        if (session.startupTicketsHeld && !session.startupRecoveryWaiting) {
            int origin = SafeSaveRules.safeSaveTicketTimerFromFirstPlayer
                    ? session.firstRealPlayerTick : session.unfreezeTick;
            if (origin >= 0 && now - origin >= Math.max(0, SafeSaveRules.safeSaveTicketDuration)) {
                releaseTickets(server, session);
            }
        }
    }

    public static void onPlayerJoined(ServerPlayer player, SafeSaveSession session) {
        if (player instanceof EntityPlayerMPFake) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        if (session.firstRealPlayerTick < 0) session.firstRealPlayerTick = server.getTickCount();
        if (session.startupRecoveryWaiting) {
            player.sendSystemMessage(Component.translatable("safesave.message.startup_frozen",
                    Math.max(0, SafeSaveRules.safeSaveForceUnfreezeTimeout))
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    // 只在保存时采集，且关闭路径必须先于原版的区块卸载执行（见 SafeSaveManager.saveAtShutdown）。
    public static void captureTicking(MinecraftServer server, SafeSaveSession session) {
        if (session.startupRecoveryWaiting) return;
        for (ServerLevel level : server.getAllLevels()) {
            var source = level.getChunkSource();
            for (ServerPlayer player : level.players()) {
                if (!player.isRemoved()
                        && player.getLastSectionPos().asLong() != SectionPos.of(player).asLong()) {
                    source.move(player);
                }
            }
            source.runDistanceManagerUpdates();
            Long2ByteOpenHashMap levels = new Long2ByteOpenHashMap();
            for (Long2ByteMap.Entry entry : source.chunkMap.getDistanceManager()
                    .simulationChunkTracker.chunks.long2ByteEntrySet()) {
                byte simulationLevel = entry.getByteValue();
                if (simulationLevel <= 32) {
                    levels.put(entry.getLongKey(), simulationLevel <= 31 ? (byte)31 : (byte)32);
                }
            }
            SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
            state.tickingChunksAtTickEnd = levels;
            state.tickingSnapshotAvailable = true;
        }
    }

    private static LoadStatus loadStatus(MinecraftServer server) {
        int total = 0;
        int loaded = 0;
        for (ServerLevel level : server.getAllLevels()) {
            var source = level.getChunkSource();
            for (Long2ByteMap.Entry entry : SafeSaveLevelAccess.of(level).startupTickets.long2ByteEntrySet()) {
                total++;
                long key = entry.getLongKey();
                if (source.getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null
                        && TickContainers.isReady(TickContainers.blockContainers(level).get(key),
                            TickContainers.fluidContainers(level).get(key))
                        && (entry.getByteValue() != 31 || level.areEntitiesLoaded(key))) {
                    loaded++;
                }
            }
        }
        return new LoadStatus(total, loaded);
    }

    public static void enforceFreeze(ServerLevel level, SafeSaveSession session) {
        if (session.startupRecoveryWaiting && !level.tickRateManager().isFrozen()) {
            level.getServer().tickRateManager().setFrozen(true);
        }
    }

    private static void finish(MinecraftServer server, SafeSaveSession session, String reason) {
        session.startupRecoveryWaiting = false;
        session.unfreezeTick = server.getTickCount();
        server.tickRateManager().setFrozen(false);
        DebugLog.info("startup unfroze {} at server tick {}", reason, session.unfreezeTick);
        Component message = Component.translatable("safesave.message.startup_unfrozen")
                .withStyle(ChatFormatting.GREEN);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) player.sendSystemMessage(message);
    }

    private static void releaseTickets(MinecraftServer server, SafeSaveSession session) {
        int released = 0;
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
            for (Long2ByteMap.Entry entry : state.startupTickets.long2ByteEntrySet()) {
                level.getChunkSource().ticketStorage.removeTicket(entry.getLongKey(),
                        new Ticket(STARTUP_LOAD, entry.getByteValue()));
                released++;
            }
            state.startupTickets.clear();
            level.getChunkSource().runDistanceManagerUpdates();
        }
        session.startupTicketsHeld = false;
        DebugLog.info("released {} startup loading ticket(s) at server tick {}", released, server.getTickCount());
    }

    private record LoadStatus(int total, int loaded) {}
}
