package com.carpet.safesave.safesave.startup;

import com.carpet.safesave.config.SafeSaveConfig;
import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;



public final class StartupChunkRecovery {

    private StartupChunkRecovery() {}

    public static void arm(final MinecraftServer server) {
        // 超时为 0 时完全不设屏障：不冻结，也不挂载入票。
        if (SafeSaveConfig.of(server).unfreezeTimeout <= 0) {
            DebugLog.info("startup loading barrier disabled");
            return;
        }
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            total += LevelStartupBarrier.arm(level);
        }
        if (total == 0) {
            DebugLog.info("no level-31/32 chunks were recorded; startup needs no loading barrier");
            return;
        }
        server.tickRateManager().setFrozen(true);
        DebugLog.info("startup frozen; loading {} previously ticking chunk(s), forced release after {} server ticks from the first real player",
                total, Math.max(0, SafeSaveConfig.of(server).unfreezeTimeout));
    }

    public static void update(final MinecraftServer server, final SafeSaveSession session) {
        int now = server.getTickCount();
        boolean pending = false;
        boolean allReady = true;
        int total = 0;
        int loaded = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (!SafeSaveLevelAccess.of(level).startupBarrierPending) {
                continue;
            }
            pending = true;
            LevelStartupBarrier.LoadStatus status = LevelStartupBarrier.status(level);
            total += status.total();
            loaded += status.loaded();
            if (!status.isReady()) {
                allReady = false;
            }
        }
        boolean playerJoined = session.firstRealPlayerTick >= 0;
        if (pending) {
            if (!server.tickRateManager().isFrozen()) {
                server.tickRateManager().setFrozen(true);
                DebugLog.warn("startup loading barrier restored the server freeze before all targets were ready");
            }
            if (playerJoined && allReady) {
                finish(server, session, "after all chunks loaded");
            } else if (playerJoined
                    && now - session.firstRealPlayerTick >= Math.max(0, SafeSaveConfig.of(server).unfreezeTimeout)) {
                DebugLog.warn("startup chunk wait timed out: {}/{} loaded", loaded, total);
                finish(server, session, "after the loading timeout");
            } else if (now - session.startupLastLogTick >= 100) {
                session.startupLastLogTick = now;
                DebugLog.info("startup chunk wait: {}/{} loaded{}", loaded, total,
                        playerJoined ? "" : " (waiting for the first real player)");
            }
        }
        int origin = session.firstRealPlayerTick;
        if (origin >= 0 && now - origin >= Math.max(0, SafeSaveConfig.of(server).ticketDuration)) {
            releaseTickets(server);
        }
    }

    public static void onPlayerJoined(final ServerPlayer player, final SafeSaveSession session) {
        if (player.getClass() != ServerPlayer.class) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        if (session.firstRealPlayerTick < 0) session.firstRealPlayerTick = server.getTickCount();
        if (anyBarrierPending(server)) {
            player.sendSystemMessage(Component.literal(
                    "[SafeSave] Loading chunks active at the last save. Game ticks are frozen; the wait lasts at most "
                            + Math.max(0, SafeSaveConfig.of(server).unfreezeTimeout) + " server ticks after the first real player joins.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    public static void enforceFreeze(final ServerLevel level) {
        if (SafeSaveLevelAccess.of(level).startupBarrierPending
                && !level.tickRateManager().isFrozen()) {
            level.getServer().tickRateManager().setFrozen(true);
        }
    }

    private static boolean anyBarrierPending(final MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (SafeSaveLevelAccess.of(level).startupBarrierPending) {
                return true;
            }
        }
        return false;
    }

    private static void finish(final MinecraftServer server, final SafeSaveSession session, final String reason) {
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveLevelAccess.of(level).startupBarrierPending = false;
        }
        session.unfreezeTick = server.getTickCount();
        server.tickRateManager().setFrozen(false);
        DebugLog.info("startup unfroze {} at server tick {}", reason, session.unfreezeTick);
        Component message = Component.literal("[SafeSave] Startup loading wait ended; game ticks have resumed.")
                .withStyle(ChatFormatting.GREEN);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) player.sendSystemMessage(message);
    }

    private static void releaseTickets(final MinecraftServer server) {
        int released = 0;
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
            if (!state.startupTicketsHeld || state.startupBarrierPending) {
                continue;
            }
            released += LevelStartupBarrier.releaseTickets(level);
        }
        if (released > 0) {
            DebugLog.info("released {} startup loading ticket(s) at server tick {}", released, server.getTickCount());
        }
    }
}
