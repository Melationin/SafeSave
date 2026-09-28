package com.carpet.safesave.safesave.startup;

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

    //? if <1.21.5 {
    /*private static final TicketType<Unit> SSTicketType =
        TicketType.create("safesave_startup_load", (first, second) -> 0);
    *///?} else if < 1.21.9{
    /*private static final TicketType SSTicketType =
        new TicketType(0L, false, TicketType.TicketUse.LOADING_AND_SIMULATION);
    *///?} else {
    private static final TicketType SSTicketType =
        new TicketType(0, TicketType.FLAG_LOADING|TicketType.FLAG_SIMULATION| TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    //?}

    // 两次采集间世界时间增量不超过此值时，区块集合的收缩只可能是卸载造成的假象，而非世界真的变小，
    // 此时对上一次的清单取并集；超过才整体替换，否则清单会跨会话无限累积。
    // （暂停中 getGameTime() 不推进，所以"暂停保存 -> 关服"的间隔天然是 0。）
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
        // 只数 31/32：挂票时同一批条目会被再过滤一次，两处口径必须一致，否则第一个 tick 就会
        // 判定"全部加载完"而立刻解冻。
        int total = 0;
        for (Long2ByteMap.Entry entry : state.savedDimension.tickingChunks.long2ByteEntrySet()) {
            if (entry.getByteValue() == 31 || entry.getByteValue() == 32) total++;
        }
        if (total == 0) {
            return 0;
        }
        state.startupBarrierPending = true;
        state.startupTicketsHeld = true;
        for (Long2ByteMap.Entry entry : state.savedDimension.tickingChunks.long2ByteEntrySet()) {
            byte ticketLevel = entry.getByteValue();
            if (ticketLevel != 31 && ticketLevel != 32) continue;
            long key = entry.getLongKey();
            state.startupTickets.put(key, ticketLevel);
            addStartupTicket(level, key, ticketLevel);
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

    // 释放本维度自己的启动票，返回释放数。
    public static int releaseTickets(final ServerLevel level) {
        SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
        int released = 0;
        for (Long2ByteMap.Entry entry : state.startupTickets.long2ByteEntrySet()) {
            removeStartupTicket(level, entry.getLongKey(), entry.getByteValue());
            released++;
        }
        state.startupTickets.clear();
        state.startupTicketsHeld = false;
        level.getChunkSource().runDistanceManagerUpdates();
        return released;
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

    private static void addStartupTicket(final ServerLevel level, final long chunkPos, final byte ticketLevel) {
        //? if <1.21.5 {
        /*level.getChunkSource().chunkMap.getDistanceManager()
                .addTicket((TicketType<Unit>) SSTicketType,
                        new ChunkPos(chunkPos), ticketLevel, Unit.INSTANCE);
        *///?} else {
        level.getChunkSource().ticketStorage.addTicket(chunkPos,
                new Ticket(SSTicketType, ticketLevel));
        //?}
    }

    private static void removeStartupTicket(final ServerLevel level, final long chunkPos, final byte ticketLevel) {
        //? if <1.21.5 {
        /*level.getChunkSource().chunkMap.getDistanceManager()
                .removeTicket((TicketType<Unit>) SSTicketType,
                        new ChunkPos(chunkPos), ticketLevel, Unit.INSTANCE);
        *///?} else {
        level.getChunkSource().ticketStorage.removeTicket(chunkPos,
                new Ticket(SSTicketType, ticketLevel));
        //?}
    }
}
