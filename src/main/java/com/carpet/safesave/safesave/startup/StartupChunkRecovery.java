package com.carpet.safesave.safesave.startup;

import com.carpet.safesave.config.SafeSaveConfig;
import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
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
            DebugLog.debug("startup loading barrier disabled");
            return;
        }
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            total += LevelStartupBarrier.arm(level);
        }
        if (total == 0) {
            DebugLog.debug("no level-31/32 chunks were recorded; startup needs no loading barrier");
            return;
        }
        server.tickRateManager().setFrozen(true);
        DebugLog.info("startup frozen: {} chunks, timeout={} ticks",
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
        SafeSaveConfig config = SafeSaveConfig.of(server);
        boolean canFinish = !config.autoUnfreezeWaitForPlayer || session.realPlayerJoined;
        if (pending) {
            if (!server.tickRateManager().isFrozen()) {
                server.tickRateManager().setFrozen(true);
                DebugLog.warn("startup loading barrier restored the server freeze before all targets were ready");
            }
            if (canFinish && allReady) {
                finish(server, "after all chunks loaded");
            } else if (canFinish && now >= Math.max(0, config.unfreezeTimeout)) {
                DebugLog.warn("startup chunk wait timed out: {}/{} loaded", loaded, total);
                finish(server, "after the loading timeout");
            } else if (now - session.startupLastLogTick >= 100) {
                session.startupLastLogTick = now;
                DebugLog.debug("startup chunk wait: {}/{} loaded{}", loaded, total,
                        canFinish ? "" : " (waiting for the first real player)");
            }
        }
    }

    public static void onPlayerJoined(final ServerPlayer player, final SafeSaveSession session) {
        if (player.getClass() != ServerPlayer.class) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        session.realPlayerJoined = true;
        if (anyBarrierPending(server)) {
            SafeSaveConfig config = SafeSaveConfig.of(server);
            player.sendSystemMessage(Component.literal(
                    "[SafeSave] Loading chunks active at the last save. Game ticks are frozen; the wait lasts at most "
                            + Math.max(0, config.unfreezeTimeout - server.getTickCount()) + " more server ticks."
                            + (config.autoUnfreeze ? "" : " Automatic unfreeze is disabled; use /tick unfreeze after the loading wait ends."))
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

    private static void finish(final MinecraftServer server, final String reason) {
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveLevelAccess.of(level).startupBarrierPending = false;
            SafeSaveLevelAccess.of(level).startupTickets.clear();
        }
        boolean autoUnfreeze = SafeSaveConfig.of(server).autoUnfreeze;
        Component message;
        if (autoUnfreeze) {
            server.tickRateManager().setFrozen(false);
            DebugLog.info("startup unfrozen {}", reason);
            message = Component.literal("[SafeSave] Startup loading wait ended; game ticks have resumed.")
                    .withStyle(ChatFormatting.GREEN);
        } else {
            DebugLog.info("startup ready {}; waiting for /tick unfreeze", reason);
            message = Component.literal("[SafeSave] Startup loading wait ended; game ticks remain frozen. Use /tick unfreeze to resume.")
                    .withStyle(ChatFormatting.YELLOW);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) player.sendSystemMessage(message);
    }

}
