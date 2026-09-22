package com.carpet.safesave.safesave.region;

import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveStore;
import com.carpet.safesave.safesave.blockentity.PistonOrderHolder;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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

    private RegionLifecycle() {}

    public static void initialize() {
        Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.parse("safesave:region"), REGION);
    }

    public static boolean isProtected(ServerLevel level, long key) {
        return SafeSaveRules.safeSaveRegions && SafeSaveLevelAccess.of(level).protectedRegions.byName.values()
                .stream().anyMatch(region -> region.contains(key));
    }

    public static boolean isSuspended(ServerLevel level, long key) {
        return SafeSaveLevelAccess.of(level).protectedRegions.suspendedAt.containsKey(key);
    }

    public static boolean maySimulate(ServerLevel level, long key) {
        return !isProtected(level, key)
                || SafeSaveLevelAccess.of(level).protectedRegions.ticketedChunks.contains(key);
    }

    public static boolean coveredByRegionTicket(ServerLevel level, long key) {
        return SafeSaveLevelAccess.of(level).protectedRegions.ticketedChunks.stream()
                .anyMatch(source -> RegionTicketPolicy.reaches(source, key, 2));
    }

    public static long snapshotTime(ServerLevel level, long key) {
        return SafeSaveLevelAccess.of(level).protectedRegions.suspendedAt.getOrDefault(key, level.getGameTime());
    }

    public static void beforeTick(ServerLevel level) {
        var state = SafeSaveLevelAccess.of(level);
        var regions = state.protectedRegions;
        if (regions.waiting || level.getServer().isStopped()) return;
        if (regions.ticketedChunks.isEmpty() && regions.suspendedAt.isEmpty()
                && (!SafeSaveRules.safeSaveRegions || regions.byName.isEmpty())) return;
        var source = level.getChunkSource();
        var storage = source.ticketStorage;
        List<RegionTicketPolicy.Demand> demands = new ArrayList<>();
        for (var entry : storage.tickets.long2ObjectEntrySet()) {
            for (Ticket ticket : entry.getValue()) {
                if (ticket.getType() != REGION && ticket.getType().doesLoad() && !ticket.isTimedOut()) {
                    demands.add(new RegionTicketPolicy.Demand(entry.getLongKey(), 33 - ticket.getTicketLevel()));
                }
            }
        }
        Set<Long> required = SafeSaveRules.safeSaveRegions
                ? RegionTicketPolicy.required(regions.byName.values().stream()
                    .map(region -> (java.util.Collection<Long>) region.chunks).toList(), demands)
                : Set.of();
        Set<Long> leaving = new HashSet<>(regions.ticketedChunks);
        leaving.removeAll(required);
        // Freeze every departing chunk at one world time BEFORE dropping any ticket.
        for (long key : leaving) suspend(level, key);
        for (long key : leaving) storage.removeTicket(key, new Ticket(REGION, 31));
        for (long key : required) {
            if (!regions.ticketedChunks.contains(key)) storage.addTicket(key, new Ticket(REGION, 31));
        }
        regions.ticketedChunks.clear();
        regions.ticketedChunks.addAll(required);

        regions.waiting = true;
        try {
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
                    if (!level.isPositionTickingWithEntitiesLoaded(key)
                            || !level.areEntitiesActuallyLoadedAndTicking(ChunkPos.unpack(key))
                            || !TickContainers.isReady(TickContainers.blockContainers(level).get(key),
                                TickContainers.fluidContainers(level).get(key))) return false;
                }
                return true;
            });
            for (long key : required) {
                var snapshot = regions.suspendedSnapshots.remove(key);
                if (snapshot != null && SafeSaveRules.safeSave) state.pendingChunks.put(key, snapshot);
                if (regions.suspendedAt.remove(key) != null) {
                    com.carpet.safesave.safesave.blockentity.PistonManager.markPistonTickOrderDirty();
                }
                ChunkPos pos = ChunkPos.unpack(key);
                source.getChunkNow(pos.x(), pos.z()).markUnsaved();
            }
            // Removed definitions / disabled rule must not leave stale suspension state behind.
            for (long key : new HashSet<>(regions.suspendedAt.keySet())) {
                if (!isProtected(level, key)) {
                    var snapshot = regions.suspendedSnapshots.remove(key);
                    if (snapshot != null && SafeSaveRules.safeSave) state.pendingChunks.put(key, snapshot);
                    regions.suspendedAt.remove(key);
                    com.carpet.safesave.safesave.blockentity.PistonManager.markPistonTickOrderDirty();
                }
            }
        } finally {
            regions.waiting = false;
        }
    }

    private static void suspend(ServerLevel level, long key) {
        var state = SafeSaveLevelAccess.of(level);
        ChunkPos pos = ChunkPos.unpack(key);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
        if (chunk == null) return;
        long time = level.getGameTime();
        state.protectedRegions.suspendedAt.put(key, time);
        state.knownChunks.remove(key);
        chunk.markUnsaved();
        if (!SafeSaveRules.safeSave) return;
        var snapshot = state.pendingChunks.get(key);
        if (snapshot == null) {
            var ticks = ScheduledTickManager.snapshotChunkTicks(level, key, chunk.getBlockTicks(), chunk.getFluidTicks());
            if (ticks != null) snapshot = new SafeSaveStore.ChunkSnapshot(ticks.blockTicks(), ticks.fluidTicks(),
                    BlockEventManager.snapshotChunkEvents(level, key, state), time);
        }
        if (snapshot != null) state.protectedRegions.suspendedSnapshots.put(key, snapshot);
        for (var blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity instanceof PistonOrderHolder piston) piston.SS$suspendAt(time);
        }
    }
}
