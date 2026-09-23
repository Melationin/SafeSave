package com.carpet.safesave.safesave.region;

import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.blockentity.PistonOrderHolder;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.chunk.ChunkSnapshotManager;
import com.carpet.safesave.safesave.chunk.ChunkSnapshotHolder;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Main-thread barrier. It pumps chunk/entity loading, never advances world simulation. */
public final class RegionLifecycle {
    public static final TicketType REGION = new TicketType(0, TicketType.FLAG_LOADING
            | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private static final TicketType REGION_HOLD = new TicketType(0, TicketType.FLAG_LOADING
            | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    /** REGION 票的层级，也是整个 region 被推进到的目标级别：ENTITY_TICKING。 */
    public static final int REGION_TICKET_LEVEL = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);

    /**
     * 原版真正开始模拟方块刻的最低级别。低于此值只意味着"被加载"，原版不会模拟它 ——
     * 这也是"失活 region 的区块恒为 33 级、因此不需要门控"这条推理的依据。
     *
     * <p>三个级别的字段（{@code FULL_CHUNK_LEVEL} 等）在 {@code ChunkLevel} 里都是 private，
     * 但 {@code byStatus} 是 public，原版自己也这么用（{@code DistanceManager.PLAYER_TICKET_LEVEL}）。
     */
    private static final int SIMULATION_LEVEL = ChunkLevel.byStatus(FullChunkStatus.BLOCK_TICKING);

    /**
     * REGION 票自己撑出的、原版会真正模拟的那一圈（{@code 32 - 31 = 1}）。
     * 距离 2 的那圈是 {@code FULL}，原版惰性，不需要接管。
     */
    private static final int REGION_SIMULATION_RADIUS = SIMULATION_LEVEL - REGION_TICKET_LEVEL;

    private RegionLifecycle() {}

    public static void initialize() {
        Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.parse("safesave:region"), REGION);
        Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.parse("safesave:region_hold"), REGION_HOLD);
    }

    public static boolean isProtected(ServerLevel level, long key) {
        return SafeSaveRules.safeSaveRegions && SafeSaveLevelAccess.of(level).protectedRegions.byName.values()
                .stream().anyMatch(region -> region.contains(key));
    }

    public static boolean isSuspended(ServerLevel level, long key) {
        return SafeSaveLevelAccess.of(level).protectedRegions.suspendedAt.contains(key);
    }

    public static void beforeTick(ServerLevel level) {
        var state = SafeSaveLevelAccess.of(level);
        var regions = state.protectedRegions;
        if (regions.waiting || level.getServer().isStopped()) return;
        if (regions.ticketedChunks.isEmpty() && regions.holdingChunks.isEmpty() && regions.suspendedAt.isEmpty()
                && (!SafeSaveRules.safeSaveRegions || regions.byName.isEmpty())) return;
        var source = level.getChunkSource();
        var storage = source.ticketStorage;
        regions.waiting = true;
        try {
            while (true) {
                synchronizePlayerTickets(level);
                Set<Long> required = requiredChunks(level);
                Set<Long> leaving = new HashSet<>(regions.ticketedChunks);
                leaving.removeAll(required);
                beginDeactivation(level, leaving);
                for (long key : required) {
                    if (!regions.ticketedChunks.contains(key)) {
                        storage.addTicket(key, new Ticket(REGION, REGION_TICKET_LEVEL));
                    }
                    if (regions.holdingChunks.remove(key)) {
                        storage.removeTicket(key, new Ticket(REGION_HOLD, REGION_TICKET_LEVEL));
                    }
                }
                regions.ticketedChunks.addAll(required);

                if (required.isEmpty()) {
                    releaseOrphanSuspensions(level);
                    return;
                }
                source.runDistanceManagerUpdates();
                // Managed blocking must service the chunk task queue; joining futures here deadlocks.
                level.getServer().managedBlock(() -> {
                    source.pollTask();
                    for (long key : required) {
                        ChunkPos pos = ChunkPos.unpack(key);
                        if (source.getChunkNow(pos.x(), pos.z()) == null) return false;
                    }
                    return true;
                });
                for (long key : required) level.waitForEntities(ChunkPos.unpack(key), 0);
                level.getServer().managedBlock(() -> {
                    source.pollTask();
                    for (long key : required) {
                        if (!source.chunkMap.getDistanceManager().inEntityTickingRange(key)
                                || !level.isPositionTickingWithEntitiesLoaded(key)
                                || !level.areEntitiesActuallyLoadedAndTicking(ChunkPos.unpack(key))
                                || !TickContainers.isReady(TickContainers.blockContainers(level).get(key),
                                    TickContainers.fluidContainers(level).get(key))) return false;
                    }
                    return true;
                });
                // managedBlock may run a teleport or another ticket-changing server task.
                synchronizePlayerTickets(level);
                if (!required.equals(requiredChunks(level))) continue;
                for (long key : required) {
                    if (regions.suspendedAt.remove(key)) {
                        ChunkPos pos = ChunkPos.unpack(key);
                        LevelChunk chunk = source.getChunkNow(pos.x(), pos.z());
                        var snapshot = ChunkSnapshotManager.storedSnapshot(key, chunk, state);
                        if (snapshot != null) state.pendingChunks.put(key, snapshot);
                        com.carpet.safesave.safesave.blockentity.PistonManager.markPistonTickOrderDirty();
                    }
                    ChunkPos pos = ChunkPos.unpack(key);
                    source.getChunkNow(pos.x(), pos.z()).markUnsaved();
                }
                releaseOrphanSuspensions(level);
                return;
            }
        } finally {
            regions.waiting = false;
        }
    }

    /** Runs after all worlds and player packets, before vanilla autosave. */
    public static void afterServerTick(ServerLevel level) {
        var regions = SafeSaveLevelAccess.of(level).protectedRegions;
        if (regions.waiting || (regions.ticketedChunks.isEmpty() && regions.holdingChunks.isEmpty())
                || level.getServer().isStopped()) return;
        if (!SafeSaveRules.safeSaveRegions) {
            var storage = level.getChunkSource().ticketStorage;
            for (long key : regions.ticketedChunks) {
                storage.removeTicket(key, new Ticket(REGION, REGION_TICKET_LEVEL));
            }
            for (long key : regions.holdingChunks) {
                storage.removeTicket(key, new Ticket(REGION_HOLD, REGION_TICKET_LEVEL));
            }
            regions.ticketedChunks.clear();
            regions.holdingChunks.clear();
            level.getChunkSource().runDistanceManagerUpdates();
            releaseOrphanSuspensions(level);
            return;
        }
        synchronizePlayerTickets(level);
        Set<Long> required = requiredChunks(level);
        Set<Long> leaving = new HashSet<>(regions.ticketedChunks);
        leaving.removeAll(required);
        beginDeactivation(level, leaving);
        var storage = level.getChunkSource().ticketStorage;
        boolean hadHolding = !regions.holdingChunks.isEmpty();
        for (long key : new HashSet<>(regions.holdingChunks)) {
            if (required.contains(key)) {
                storage.addTicket(key, new Ticket(REGION, REGION_TICKET_LEVEL));
                regions.ticketedChunks.add(key);
            } else if (isProtected(level, key)) {
                suspend(level, key);
            }
            storage.removeTicket(key, new Ticket(REGION_HOLD, REGION_TICKET_LEVEL));
            regions.holdingChunks.remove(key);
        }
        if (hadHolding) {
            level.getChunkSource().runDistanceManagerUpdates();
        }
    }

    private static void synchronizePlayerTickets(ServerLevel level) {
        // A command teleport updates ServerPlayer's position immediately, but vanilla can defer
        // ChunkMap.move (and thus PLAYER_SIMULATION) until a later movement packet. Reconcile
        // the player's actual section before deciding whether the old region may tick again.
        for (var player : level.players()) {
            if (!player.isRemoved()
                    && player.getLastSectionPos().asLong() != SectionPos.of(player).asLong()) {
                level.getChunkSource().move(player);
            }
        }
    }

    private static Set<Long> requiredChunks(ServerLevel level) {
        var regions = SafeSaveLevelAccess.of(level).protectedRegions;
        if (!SafeSaveRules.safeSaveRegions || regions.byName.isEmpty()) return Set.of();
        List<RegionTicketPolicy.Demand> demands = new ArrayList<>();
        var storage = level.getChunkSource().ticketStorage;
        for (var entry : storage.tickets.long2ObjectEntrySet()) {
            for (Ticket ticket : entry.getValue()) {
                if (ticket.getType() != REGION && ticket.getType().doesSimulate() && !ticket.isTimedOut()) {
                    // 触发边界 = 原版的模拟边界：BLOCK_TICKING 及以上才接管整个 region。
                    // 用 FULL(33) 会多激活一格、让 region 比原版早一圈满速运行；
                    // 用 ENTITY_TICKING(31) 会漏掉 32 那一圈、把它冻住。
                    demands.add(new RegionTicketPolicy.Demand(entry.getLongKey(),
                            SIMULATION_LEVEL - ticket.getTicketLevel()));
                }
            }
        }
        return RegionTicketPolicy.required(regions.byName.values().stream()
                .map(region -> (java.util.Collection<Long>) region.chunks).toList(), demands,
                REGION_SIMULATION_RADIUS);
    }

    private static void beginDeactivation(ServerLevel level, Set<Long> leaving) {
        if (leaving.isEmpty()) return;
        var regions = SafeSaveLevelAccess.of(level).protectedRegions;
        var storage = level.getChunkSource().ticketStorage;
        for (long key : leaving) {
            storage.addTicket(key, new Ticket(REGION_HOLD, REGION_TICKET_LEVEL));
            storage.removeTicket(key, new Ticket(REGION, REGION_TICKET_LEVEL));
        }
        regions.ticketedChunks.removeAll(leaving);
        regions.holdingChunks.addAll(leaving);
        // Scheduled ticks run before ServerChunkCache.tick. Flush the simulation tracker now,
        // or it can still see our old level-31 ticket for one more world tick.
        level.getChunkSource().runDistanceManagerUpdates();
    }

    private static void releaseOrphanSuspensions(ServerLevel level) {
        var state = SafeSaveLevelAccess.of(level);
        var regions = state.protectedRegions;
        // Removed definitions / disabled rule must not leave stale suspension state behind.
        for (long key : new LongOpenHashSet(regions.suspendedAt)) {
            if (!isProtected(level, key)) {
                ChunkPos pos = ChunkPos.unpack(key);
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
                var snapshot = ChunkSnapshotManager.storedSnapshot(key, chunk, state);
                if (snapshot != null) state.pendingChunks.put(key, snapshot);
                regions.suspendedAt.remove(key);
                com.carpet.safesave.safesave.blockentity.PistonManager.markPistonTickOrderDirty();
            }
        }
    }

    private static void suspend(ServerLevel level, long key) {
        var state = SafeSaveLevelAccess.of(level);
        ChunkPos pos = ChunkPos.unpack(key);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
        if (chunk == null) {
            throw new IllegalStateException("Protected chunk " + pos
                    + " disappeared while its loading hold ticket was active");
        }
        long time = level.getGameTime();
        var snapshot = ChunkSnapshotManager.storedSnapshot(key, chunk, state);
        if (snapshot == null) {
            throw new IllegalStateException("Cannot suspend protected chunk " + pos
                    + " before its scheduled ticks are available for a snapshot");
        }
        state.protectedRegions.suspendedAt.add(key);
        state.knownChunks.remove(key);
        chunk.markUnsaved();
        // 快照已就位才清空活容器：失活期间遗留的绝对刻若被执行，复活时灌回快照会重复执行。
        ScheduledTickManager.clearChunkTicks(chunk);
        BlockEventManager.clearChunkEvents(level, key, state);
        for (var blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity instanceof PistonOrderHolder piston) piston.SS$suspendAt(time);
        }
    }
}
