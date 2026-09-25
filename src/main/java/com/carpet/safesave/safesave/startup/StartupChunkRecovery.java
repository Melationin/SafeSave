package com.carpet.safesave.safesave.startup;

import com.carpet.safesave.config.SafeSaveConfig;
import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.SafeSaveStore;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
//? if <1.21.5 {
/*import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.util.Unit;
*///?}

import static com.carpet.safesave.util.Util.dimensionId;


public final class StartupChunkRecovery {
    // 不注册进 BuiltInRegistries.TICKET_TYPE（该表在 main 入口点之前就已冻结）：本类型无 FLAG_PERSIST，
    // TicketStorage 从不查注册表，Ticket.CODEC 与 toString() 都用不到未注册类型。
    //? if <1.21.5 {
    /*private static final TicketType<Unit> STARTUP_LOAD =
            TicketType.create("safesave_startup_load", (first, second) -> 0);
    *///?} else {
    // 1.21.9 把 TicketType 从 (timeout, persist, TicketUse) 记录改成了位标志；
    // FLAG_KEEP_DIMENSION_ACTIVE 在旧模型里没有对应项，LOADING 就是最接近的语义。
    //? if <1.21.9 {
    /*private static final TicketType STARTUP_LOAD =
            new TicketType(0L, false, TicketType.TicketUse.LOADING);
    *///?} else {
    private static final TicketType STARTUP_LOAD = new TicketType(0,
            TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    //?}
    //?}

    private StartupChunkRecovery() {}

    // 两次采集间世界时间增量不超过此值时，区块集合的收缩只可能是卸载造成的假象，而非世界真的变小，
    // 此时对上一次的清单取并集；超过才整体替换，否则清单会跨会话无限累积。
    // （暂停中 getGameTime() 不推进，所以"暂停保存 -> 关服"的间隔天然是 0。）
    private static final long UNION_WINDOW_TICKS = 10;

    public static void arm(MinecraftServer server, SafeSaveSession session) {
        // 超时为 0 时完全不设屏障：不冻结，也不挂载入票。
        if (SafeSaveConfig.unfreezeTimeout <= 0) {
            DebugLog.info("startup loading barrier disabled (unfreezeTimeout <= 0)");
            return;
        }
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveStore.DimensionData saved = session.store.dimensionOrNull(dimensionId(level));
            if (saved == null) continue;
            // 只数 31/32：挂票时同一批条目会被再过滤一次，两处口径必须一致，否则第一个 tick 就会
            // 判定"全部加载完"而立刻解冻。
            for (Long2ByteMap.Entry entry : saved.tickingChunks.long2ByteEntrySet()) {
                if (entry.getByteValue() == 31 || entry.getByteValue() == 32) total++;
            }
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
                addStartupTicket(level, key, ticketLevel);
            }
            level.getChunkSource().runDistanceManagerUpdates();
        }
        DebugLog.info("startup frozen; loading {} previously ticking chunk(s), forced release after {} server ticks from the first real player",
                total, Math.max(0, SafeSaveConfig.unfreezeTimeout));
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
                    && now - session.firstRealPlayerTick >= Math.max(0, SafeSaveConfig.unfreezeTimeout)) {
                DebugLog.warn("startup chunk wait timed out: {}/{} loaded", status.loaded, status.total);
                finish(server, session, "after the loading timeout");
            } else if (now - session.startupLastLogTick >= 100) {
                session.startupLastLogTick = now;
                DebugLog.info("startup chunk wait: {}/{} loaded", status.loaded, status.total);
            }
        }
        if (session.startupTicketsHeld && !session.startupRecoveryWaiting) {
            int origin = SafeSaveConfig.timerFromFirstPlayer
                    ? session.firstRealPlayerTick : session.unfreezeTick;
            if (origin >= 0 && now - origin >= Math.max(0, SafeSaveConfig.ticketDuration)) {
                releaseTickets(server, session);
            }
        }
    }

    public static void onPlayerJoined(ServerPlayer player, SafeSaveSession session) {
        // 原版只注册 ServerPlayer 一种实现，子类（假人等）不算真人。
        if (player.getClass() != ServerPlayer.class) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        if (session.firstRealPlayerTick < 0) session.firstRealPlayerTick = server.getTickCount();
        if (session.startupRecoveryWaiting) {
            player.sendSystemMessage(Component.literal(
                    "[SafeSave] Loading chunks active at the last save. Game ticks are frozen; the wait lasts at most "
                            + Math.max(0, SafeSaveConfig.unfreezeTimeout) + " server ticks after the first real player joins.")
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
            Long2ByteOpenHashMap fresh = new Long2ByteOpenHashMap();
            //? if <1.21.5 {
            /*// 层级 31 = 实体刻、32 = 仅方块刻，实体刻是方块刻的子集，所以必须先判实体刻。
            DistanceManager distanceManager = source.chunkMap.getDistanceManager();
            for (Long2ObjectMap.Entry<ChunkHolder> holder : source.chunkMap.visibleChunkMap.long2ObjectEntrySet()) {
                long key = holder.getLongKey();
                if (distanceManager.inEntityTickingRange(key)) {
                    fresh.put(key, (byte) 31);
                } else if (distanceManager.inBlockTickingRange(key)) {
                    fresh.put(key, (byte) 32);
                }
            }
            *///?} else {
            for (Long2ByteMap.Entry entry : source.chunkMap.getDistanceManager()
                    .simulationChunkTracker.chunks.long2ByteEntrySet()) {
                byte simulationLevel = entry.getByteValue();
                if (simulationLevel <= 32) {
                    fresh.put(entry.getLongKey(), simulationLevel <= 31 ? (byte)31 : (byte)32);
                }
            }
            //?}
            SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
            long now = level.getGameTime();
            Long2ByteOpenHashMap levels = fresh;
            if (state.tickingSnapshotAvailable
                    && now - state.lastTickingCaptureGameTime <= UNION_WINDOW_TICKS) {
                levels = union(state.tickingChunksAtTickEnd, fresh);
                if (levels.size() != state.tickingChunksAtTickEnd.size()) {
                    DebugLog.info("{}: ticking list changed within {} tick(s) of the previous capture ({} -> {}); "
                                    + "kept the union of {} chunk(s)",
                            dimensionId(level), UNION_WINDOW_TICKS,
                            state.tickingChunksAtTickEnd.size(), fresh.size(), levels.size());
                }
            }
            state.tickingChunksAtTickEnd = levels;
            state.lastTickingCaptureGameTime = now;
            state.tickingSnapshotAvailable = true;
        }
    }

    // 同一区块两次分类不同时取更小的那个：31（实体刻）比 32（仅方块刻）更强。
    private static Long2ByteOpenHashMap union(final Long2ByteOpenHashMap previous,
                                              final Long2ByteOpenHashMap fresh) {
        Long2ByteOpenHashMap merged = new Long2ByteOpenHashMap(previous);
        for (Long2ByteMap.Entry entry : fresh.long2ByteEntrySet()) {
            long key = entry.getLongKey();
            byte value = entry.getByteValue();
            if (merged.containsKey(key)) {
                merged.put(key, (byte) Math.min(merged.get(key), value));
            } else {
                merged.put(key, value);
            }
        }
        return merged;
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
        Component message = Component.literal("[SafeSave] Startup loading wait ended; game ticks have resumed.")
                .withStyle(ChatFormatting.GREEN);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) player.sendSystemMessage(message);
    }

    private static void releaseTickets(MinecraftServer server, SafeSaveSession session) {
        int released = 0;
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
            for (Long2ByteMap.Entry entry : state.startupTickets.long2ByteEntrySet()) {
                removeStartupTicket(level, entry.getLongKey(), entry.getByteValue());
                released++;
            }
            state.startupTickets.clear();
            level.getChunkSource().runDistanceManagerUpdates();
        }
        session.startupTicketsHeld = false;
        DebugLog.info("released {} startup loading ticket(s) at server tick {}", released, server.getTickCount());
    }

    private record LoadStatus(int total, int loaded) {}

    private static void addStartupTicket(ServerLevel level, long chunkPos, byte ticketLevel) {
        //? if <1.21.5 {
        /*level.getChunkSource().chunkMap.getDistanceManager()
                .addTicket(STARTUP_LOAD, new ChunkPos(chunkPos), ticketLevel, Unit.INSTANCE);
        *///?} else {
        level.getChunkSource().ticketStorage.addTicket(chunkPos, new Ticket(STARTUP_LOAD, ticketLevel));
        //?}
    }

    private static void removeStartupTicket(ServerLevel level, long chunkPos, byte ticketLevel) {
        //? if <1.21.5 {
        /*level.getChunkSource().chunkMap.getDistanceManager()
                .removeTicket(STARTUP_LOAD, new ChunkPos(chunkPos), ticketLevel, Unit.INSTANCE);
        *///?} else {
        level.getChunkSource().ticketStorage.removeTicket(chunkPos, new Ticket(STARTUP_LOAD, ticketLevel));
        //?}
    }
}
