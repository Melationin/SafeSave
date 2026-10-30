package com.carpet.safesave.safesave.startup;

import com.carpet.safesave.config.SafeSaveConfig;
import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.SectionPos;
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


public final class LevelStartupBarrier {

    private static final long UNION_WINDOW_TICKS = 10;

    private LevelStartupBarrier() {}

    public record LoadStatus(int total, int loaded) {
        public boolean isReady() {
            return this.total == this.loaded;
        }
    }

    // 返回该维度需要等待的区块数；>0 时置位并挂票。冻结由服务器负责。
    public static int arm(final ServerLevel level) {
        SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
        int total = 0;
        for (Long2ByteMap.Entry entry : state.savedDimension.tickingChunks.long2ByteEntrySet()) {
            if (entry.getByteValue() == 31 || entry.getByteValue() == 32) total++;
        }
        if (total == 0) {
            return 0;
        }
        state.startupBarrierPending = true;
        int duration = Math.max(1, SafeSaveConfig.of(level.getServer()).ticketDuration);
        //? if <1.21.5 {
        /*TicketType<Unit> ticketType = TicketType.create("safesave_startup_load", (first, second) -> 0, duration);
        *///?} else if <1.21.9 {
        /*TicketType ticketType = new TicketType(duration, false, TicketType.TicketUse.LOADING_AND_SIMULATION);
        *///?} else {
        TicketType ticketType = new TicketType(duration,
                TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        //?}
        for (Long2ByteMap.Entry entry : state.savedDimension.tickingChunks.long2ByteEntrySet()) {
            byte ticketLevel = entry.getByteValue();
            if (ticketLevel != 31 && ticketLevel != 32) continue;
            long key = entry.getLongKey();
            state.startupTickets.put(key, ticketLevel);
            addStartupTicket(level, key, ticketLevel, ticketType);
        }
        level.getChunkSource().runDistanceManagerUpdates();
        return total;
    }

    public static LoadStatus status(final ServerLevel level) {
        var source = level.getChunkSource();
        int total = 0;
        int loaded = 0;
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
        return new LoadStatus(total, loaded);
    }


    public static void captureTicking(final ServerLevel level) {
        SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
        if (state.startupBarrierPending) return;
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
        /*
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
        long now = level.getGameTime();
        Long2ByteOpenHashMap levels = fresh;
        if (state.tickingSnapshotAvailable
                && now - state.lastTickingCaptureGameTime <= UNION_WINDOW_TICKS) {
            levels = union(state.tickingChunksAtTickEnd, fresh);
            if (levels.size() != state.tickingChunksAtTickEnd.size()) {
                DebugLog.debug("{}: ticking list changed within {} tick(s) of the previous capture ({} -> {}); "
                                + "kept the union of {} chunk(s)",
                        dimensionId(level), UNION_WINDOW_TICKS,
                        state.tickingChunksAtTickEnd.size(), fresh.size(), levels.size());
            }
        }
        state.tickingChunksAtTickEnd = levels;
        state.lastTickingCaptureGameTime = now;
        state.tickingSnapshotAvailable = true;
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

    private static void addStartupTicket(final ServerLevel level, final long chunkPos, final byte ticketLevel,
                                        final TicketType ticketType) {
        //? if <1.21.5 {
        /*level.getChunkSource().chunkMap.getDistanceManager()
                .addTicket((TicketType<Unit>) ticketType,
                        new ChunkPos(chunkPos), ticketLevel, Unit.INSTANCE);
        *///?} else {
        level.getChunkSource().ticketStorage.addTicket(chunkPos,
                new Ticket(ticketType, ticketLevel));
        //?}
    }

}
