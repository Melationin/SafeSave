package com.example.carpet.safesave;

import com.example.carpet.debug.DebugLog;
import com.example.carpet.debug.TickOwnerAware;
import com.example.carpet.mixin.LevelSubTickCountAccessor;
import com.example.carpet.mixin.ServerLevelBlockEventsAccessor;
import com.example.carpet.rules.SafeSaveRules;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickContainerAccess;
import net.minecraft.world.ticks.TickPriority;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Safe save" for scheduled ticks.
 *
 * <h2>What vanilla loses on restart</h2>
 * A chunk's ticks are stored as {@code SavedTick(type, pos, int delay, priority)}. On load,
 * {@code LevelChunk.unpackTicks(gameTime)} re-anchors {@code delay} against the game time at which
 * <em>that chunk</em> starts block-ticking, and re-numbers {@code subTickOrder} as {@code -N..-1}
 * <em>per chunk</em>. Consequences:
 * <ul>
 *   <li>absolute trigger times drift by {@code T_unpack - T_save} for any chunk not loaded at
 *       startup, so cross-chunk phase relationships break;</li>
 *   <li>the global {@code subTickOrder} ordering between chunks is destroyed (mass ties, resolved by
 *       hash-map iteration order);</li>
 *   <li>{@code Level.subTickCount} is not persisted at all;</li>
 *   <li>merely scheduling a tick does not mark the chunk unsaved, so a chunk whose only change was a
 *       scheduled tick is never rewritten and the tick is silently lost.</li>
 * </ul>
 *
 * <h2>What this does</h2>
 * Keeps an authoritative side store of every scheduled tick with <strong>absolute</strong>
 * {@code triggerTick} and the <strong>original global</strong> {@code subTickOrder}, written to
 * {@code <world>/safesave.dat}. It is independent of vanilla's chunk NBT, so it also
 * sidesteps the {@code markUnsaved} loss. On load it overwrites whatever vanilla re-anchored.
 *
 * <p>The store is refreshed for a chunk when (a) that chunk unloads, or (b) a world save happens
 * while it is loaded. Because trigger times are absolute, an entry for a long-unloaded chunk stays
 * valid indefinitely — nothing drifts.
 *
 * <p>{@code serverTickCount} and per-level {@code gameTime} are also written, but purely for
 * diagnostics: the restore path below never reads them.
 */
public final class SafeSaveManager {

    private static final String FILE_NAME = "safesave.dat";
    /** Name used before the mod was renamed to SafeSave; still readable. */
    private static final String LEGACY_FILE_NAME = "carpet-example-safesave.dat";

    /** Absolute-timing store; {@code null} until a server is loaded. */
    private static SafeSaveStore store;
    private static Path filePath;

    /** {@code true} until the one-shot pre-first-tick freeze has been considered. */
    private static boolean freezeArmed;
    /** Dimensions whose one-shot first-world-tick restore has already run. */
    private static final Set<String> firstTickDone = new HashSet<>();
    /** Diagnostics for {@code /safesave status}. */
    private static int loadedTickCount;
    private static int restoredTickCount;
    private static int droppedTickCount;
    private static int loadedBlockEventCount;
    private static int restoredBlockEventCount;
    private static int droppedBlockEventCount;

    private SafeSaveManager() {
    }

    public static boolean enabled() {
        return SafeSaveRules.safeSave;
    }

    public static SafeSaveStore store() {
        return store;
    }

    public static int loadedTickCount() {
        return loadedTickCount;
    }

    public static int restoredTickCount() {
        return restoredTickCount;
    }

    public static int droppedTickCount() {
        return droppedTickCount;
    }

    public static int loadedBlockEventCount() {
        return loadedBlockEventCount;
    }

    public static int restoredBlockEventCount() {
        return restoredBlockEventCount;
    }

    public static int droppedBlockEventCount() {
        return droppedBlockEventCount;
    }

    private static String dimensionId(final ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    // ------------------------------------------------------------ server hooks

    /**
     * Called from Carpet's {@code onServerLoaded}, which fires at {@code MinecraftServer.loadLevel}
     * HEAD — i.e. before {@code createLevels}/{@code prepareLevels}. That matters: the store must be
     * populated <em>before</em> the first chunk unpacks its ticks.
     */
    public static void onServerLoaded(final MinecraftServer server) {
        store = new SafeSaveStore();
        filePath = server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
        freezeArmed = true;
        firstTickDone.clear();
        loadedTickCount = 0;
        restoredTickCount = 0;
        droppedTickCount = 0;
        loadedBlockEventCount = 0;
        restoredBlockEventCount = 0;
        droppedBlockEventCount = 0;
        staleWarned.clear();

        if (!enabled()) {
            DebugLog.info("rule 'safeSave' is off; not reading {}", FILE_NAME);
            return;
        }

        // Prefer the current name; fall back to the pre-rename one so an existing world is not
        // silently reset. Writes always go to FILE_NAME, so the first save migrates the world.
        Path source = filePath;
        if (!Files.isRegularFile(source)) {
            Path legacy = server.getWorldPath(LevelResource.ROOT).resolve(LEGACY_FILE_NAME);
            if (Files.isRegularFile(legacy)) {
                source = legacy;
                DebugLog.info("reading legacy {}; it will be migrated to {} on the next save",
                        LEGACY_FILE_NAME, FILE_NAME);
            }
        }
        if (!Files.isRegularFile(source)) {
            DebugLog.info("no {} found; this session starts from vanilla chunk ticks", FILE_NAME);
            return;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(source, NbtAccounter.unlimitedHeap());
            store = SafeSaveStore.load(tag);
            loadedTickCount = store.totalTicks();
            loadedBlockEventCount = store.totalBlockEvents();
            DebugLog.info("loaded {} scheduled tick(s) + {} block event(s) across {} dimension(s) from {} "
                            + "(debug: serverTick={} gameTimes={})",
                    loadedTickCount, loadedBlockEventCount, store.dimensions().size(), source.getFileName(),
                    store.serverTickCount(), store.debugGameTimes());
        } catch (Exception e) {
            store = new SafeSaveStore();
            DebugLog.warn("failed to read {} - falling back to vanilla behaviour: {}", source.getFileName(), e.toString());
        }
    }

    /**
     * Called from Carpet's {@code onServerClosed}, which fires at {@code MinecraftServer.stopServer}
     * <em>HEAD</em>.
     *
     * <p>Deliberately does <strong>not</strong> drop the store: the shutdown sequence still has to run
     * its chunk-unload loop and then {@code saveAllChunks(false, true, false)} further down
     * {@code stopServer}. Clearing state here would silently skip the single most important save of
     * the whole feature. All per-session state is re-initialised by {@link #onServerLoaded} instead,
     * so nothing leaks into a subsequent (singleplayer) world.
     */
    public static void onServerClosed() {
        if (enabled() && store != null) {
            DebugLog.info("server closing; the shutdown save later in stopServer will flush {} ({} tick(s) currently held)",
                    FILE_NAME, store.totalTicks());
        }
    }

    /**
     * Called at {@code MinecraftServer.prepareLevels} HEAD: every {@code ServerLevel} exists but no
     * chunk has been prepared for ticking yet.
     *
     * <p>Binds the debug owner labels and restores {@code Level.subTickCount} here, which is the
     * earliest point at which both the levels and the store are available. Restoring the counter
     * before any chunk unpacks guarantees newly scheduled ticks cannot collide with restored
     * {@code subTickOrder} values.
     */
    public static void onLevelsCreated(final MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            bindDebugOwners(level);
            if (!enabled() || store == null) {
                continue;
            }
            SafeSaveStore.DimensionData data = store.dimensionOrNull(dimensionId(level));
            if (data == null) {
                continue;
            }
            if (data.subTickCount >= 0L) {
                LevelSubTickCountAccessor accessor = (LevelSubTickCountAccessor) level;
                long current = accessor.carpetExample$getSubTickCount();
                // never move the counter backwards: anything already handed out must stay unique
                if (data.subTickCount > current) {
                    accessor.carpetExample$setSubTickCount(data.subTickCount);
                    DebugLog.info("{}: restored Level.subTickCount {} -> {}",
                            dimensionId(level), current, data.subTickCount);
                }
            }
            restoreBlockEvents(level, data);
        }
    }

    /**
     * Re-queues the saved block events into {@code ServerLevel.blockEvents}.
     *
     * <p>Order is the whole game here: the vanilla container is an {@code ObjectLinkedOpenHashSet}
     * drained with {@code removeFirst()}, so insertion order <em>is</em> execution order. Anything
     * already queued at this point (levels were only just constructed, but be defensive) is
     * re-appended <em>after</em> the restored events, because the restored ones are strictly older.
     *
     * <p>Note {@code runBlockEvents} puts events it cannot run yet back into {@code blockEvents}
     * before returning, so {@code blockEventsToReschedule} never holds state across a tick boundary
     * and does not need saving.
     */
    private static void restoreBlockEvents(final ServerLevel level, final SafeSaveStore.DimensionData data) {
        if (!data.blockEventsPendingRestore || data.blockEvents.isEmpty()) {
            return;
        }
        data.blockEventsPendingRestore = false;
        ObjectLinkedOpenHashSet<BlockEventData> queue =
                ((ServerLevelBlockEventsAccessor) level).carpetExample$blockEvents();

        List<BlockEventData> existing = new ArrayList<>(queue);
        queue.clear();

        int restored = 0;
        for (SafeBlockEvent saved : data.blockEvents) {
            Identifier id = Identifier.tryParse(saved.blockId());
            // BLOCK is a DefaultedRegistry: getValue() would silently return AIR for an unknown id.
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                droppedBlockEventCount++;
                DebugLog.warn("dropping block event for unknown block '{}' at ({},{},{})",
                        saved.blockId(), saved.x(), saved.y(), saved.z());
                continue;
            }
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            queue.add(new BlockEventData(new BlockPos(saved.x(), saved.y(), saved.z()),
                    block, saved.paramA(), saved.paramB()));
            restored++;
        }
        // re-append anything that was already pending, after the restored (older) events
        queue.addAll(existing);

        restoredBlockEventCount += restored;
        data.blockEvents.clear(); // consumed
        DebugLog.info("{}: restored {} block event(s) in drain order ({} pre-existing kept behind them)",
                dimensionId(level), restored, existing.size());
    }

    private static void bindDebugOwners(final ServerLevel level) {
        Object blockTicks = level.getBlockTicks();
        if (blockTicks instanceof TickOwnerAware aware) {
            aware.carpetExample$bindOwner(level, "block");
        }
        Object fluidTicks = level.getFluidTicks();
        if (fluidTicks instanceof TickOwnerAware aware) {
            aware.carpetExample$bindOwner(level, "fluid");
        }
    }

    /**
     * Called at {@code MinecraftServer.tickServer} HEAD, once.
     *
     * <p>Freezes the server so that nothing advances until the operator has verified the restored
     * state. While frozen {@code TickRateManager.runsNormally()} is false, so
     * {@code ServerLevel.tick} skips the {@code blockTicks}/{@code fluidTicks} phases entirely and
     * {@code gameTime} does not move — the restored ticks sit untouched.
     */
    public static void onFirstServerTick(final MinecraftServer server) {
        if (!freezeArmed) {
            return;
        }
        freezeArmed = false;
        // Freeze only when there is actually restore data. Note this must test the count read from
        // disk, not store.isEmpty(): the startup flush save creates an (empty) DimensionData for every
        // level, which would make an otherwise-empty store look non-empty and freeze a fresh world.
        if (!enabled() || (loadedTickCount <= 0 && loadedBlockEventCount <= 0)) {
            return;
        }
        server.tickRateManager().setFrozen(true);
        DebugLog.info("froze the server before its first tick ({} scheduled tick(s) + {} block event(s) restored). "
                        + "Run '/tick unfreeze' once you are happy with the restored state.",
                loadedTickCount, loadedBlockEventCount);
    }

    /**
     * Called at {@code ServerLevel.tick} HEAD. Performs the one-shot per-dimension restore sweep.
     *
     * <p>Chunks that were prepared during {@code prepareLevels} are already handled by the
     * {@code unpackTicks} hook; this sweep catches chunks that are loaded to {@code FULL} but have
     * not (yet) started block-ticking, whose ticks are still sitting in {@code pendingTicks}.
     * Applying absolute data there is strictly better than vanilla: the ticks land in the queue with
     * their true trigger time and simply wait for the chunk to become tickable.
     */
    public static void onLevelTickStart(final ServerLevel level) {
        if (!enabled() || store == null) {
            return;
        }
        String dimension = dimensionId(level);
        if (!firstTickDone.add(dimension)) {
            return;
        }
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimension);
        if (data == null || data.pendingRestore.isEmpty()) {
            return;
        }

        Long2ObjectMap<?> blockContainers = ((TickContainerHolder) level.getBlockTicks()).carpetExample$containers();
        Long2ObjectMap<?> fluidContainers = ((TickContainerHolder) level.getFluidTicks()).carpetExample$containers();
        int swept = 0;
        for (Long boxed : new ArrayList<>(data.pendingRestore)) {
            long key = boxed;
            Object block = blockContainers.get(key);
            Object fluid = fluidContainers.get(key);
            // Absent from allContainers => chunk not loaded to FULL; the unpackTicks hook covers it later.
            if (!(block instanceof SafeTickContainer) || !(fluid instanceof SafeTickContainer)) {
                continue;
            }
            if (restoreInto(level, key, block, fluid,
                    ((SafeTickContainer) block).carpetExample$snapshotQueue(),
                    ((SafeTickContainer) fluid).carpetExample$snapshotQueue())) {
                swept++;
            }
        }
        DebugLog.info("{}: first world tick - swept {} already-loaded chunk(s); {} tick(s) restored so far, {} dropped",
                dimension, swept, restoredTickCount, droppedTickCount);
    }

    /** {@code true} when this level still has pending (un-applied) restore entries. */
    public static int pendingChunkCount(final ServerLevel level) {
        if (store == null) {
            return 0;
        }
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimensionId(level));
        return data == null ? 0 : data.pendingRestore.size();
    }

    /** Debug helper for the world-tick log line. */
    public static int pendingBlockEventCount(final Level level) {
        if (level instanceof ServerLevel serverLevel) {
            return ((ServerLevelBlockEventsAccessor) serverLevel).carpetExample$blockEvents().size();
        }
        return -1;
    }

    // ----------------------------------------------------------- restore path

    /**
     * @return {@code true} when this chunk still has an un-applied restore entry, i.e. whatever is
     *         currently in its tick containers is about to be discarded.
     */
    public static boolean hasPendingRestore(final LevelChunk chunk) {
        if (!enabled() || store == null) {
            return false;
        }
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimensionId(level));
        return data != null && data.pendingRestore.contains(chunk.getPos().pack());
    }

    /**
     * Replaces a chunk's scheduled ticks with the saved absolute ones. Called from
     * {@code LevelChunk.unpackTicks} TAIL and from the first-world-tick sweep.
     *
     * <p>The store entry is <em>consumed</em>, so it can never be applied twice; if the chunk later
     * unloads, {@link #snapshotChunk} puts a fresh entry back.
     *
     * @param keepBlockTicks {@code ScheduledTick}s that were already queued <em>before</em>
     *                       {@code unpackTicks} ran, i.e. genuinely new ticks scheduled during this
     *                       session while the chunk sat at {@code FULL}. They are re-added after the
     *                       restore so this feature never loses a tick vanilla would have kept.
     *                       May be {@code null}.
     * @return {@code true} when something was restored
     */
    public static boolean restoreChunk(final LevelChunk chunk,
                                       final List<?> keepBlockTicks,
                                       final List<?> keepFluidTicks) {
        if (!enabled() || store == null) {
            return false;
        }
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        return restoreInto(level, chunk.getPos().pack(), chunk.getBlockTicks(), chunk.getFluidTicks(),
                keepBlockTicks, keepFluidTicks);
    }

    /**
     * @param blockContainer the chunk's {@code LevelChunkTicks<Block>}
     * @param fluidContainer the chunk's {@code LevelChunkTicks<Fluid>}
     */
    @SuppressWarnings("unchecked")
    private static boolean restoreInto(final ServerLevel level,
                                      final long packedChunkPos,
                                      final Object blockContainer,
                                      final Object fluidContainer,
                                      final List<?> keepBlockTicks,
                                      final List<?> keepFluidTicks) {
        String dimension = dimensionId(level);
        SafeSaveStore.ChunkSnapshot snapshot = store.take(dimension, packedChunkPos);
        if (snapshot == null) {
            return false;
        }
        warnIfStale(level);
        int keptBlock = applyTicks((TickContainerAccess<Block>) blockContainer, snapshot.blockTicks(),
                BuiltInRegistries.BLOCK, keepBlockTicks);
        int keptFluid = applyTicks((TickContainerAccess<Fluid>) fluidContainer, snapshot.fluidTicks(),
                BuiltInRegistries.FLUID, keepFluidTicks);
        DebugLog.info("{} {}: restored {} block + {} fluid tick(s) with absolute timing (kept {} pre-existing)",
                dimension, ChunkPos.unpack(packedChunkPos),
                snapshot.blockTicks().size(), snapshot.fluidTicks().size(), keptBlock + keptFluid);
        return true;
    }

    /** Dimensions already warned about, so the message appears once per session. */
    private static final Set<String> staleWarned = new HashSet<>();

    /**
     * Purely diagnostic. The recorded {@code gameTime} is <strong>never</strong> used to re-anchor
     * anything — trigger times are absolute by construction. But if it disagrees with the live
     * {@code gameTime}, the side file is out of step with {@code level.dat} (typically: the rule was
     * switched off for a session, so the world advanced while this file did not), and every restored
     * tick will be correspondingly overdue. Worth saying out loud.
     */
    private static void warnIfStale(final ServerLevel level) {
        String dimension = dimensionId(level);
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimension);
        if (data == null || data.gameTime == Long.MIN_VALUE) {
            return;
        }
        long live = level.getGameTime();
        if (data.gameTime != live && staleWarned.add(dimension)) {
            DebugLog.warn("{}: side file was written at gameTime={} but the world resumed at gameTime={} "
                            + "(difference {}). Restored ticks keep their absolute trigger times and will therefore "
                            + "fire immediately. This usually means 'safeSave' was off for a previous session.",
                    dimension, data.gameTime, live, live - data.gameTime);
        }
    }

    /**
     * @param keep pre-existing ticks to re-add after the wipe; may be {@code null}
     * @return how many pre-existing ticks were re-added
     */
    @SuppressWarnings("unchecked")
    private static <T> int applyTicks(final TickContainerAccess<T> container,
                                      final List<SafeTick> saved,
                                      final Registry<T> registry,
                                      final List<?> keep) {
        List<ScheduledTick<T>> ticks = new ArrayList<>(saved.size());
        for (SafeTick entry : saved) {
            Identifier id = Identifier.tryParse(entry.typeId());
            // BLOCK/FLUID are DefaultedRegistry: getValue() would silently hand back AIR/EMPTY for an
            // unknown id, so membership must be checked explicitly.
            if (id == null || !registry.containsKey(id)) {
                droppedTickCount++;
                DebugLog.warn("dropping scheduled tick for unknown type '{}' at ({},{},{})",
                        entry.typeId(), entry.x(), entry.y(), entry.z());
                continue;
            }
            T type = registry.getValue(id);
            ticks.add(new ScheduledTick<>(
                    type,
                    new BlockPos(entry.x(), entry.y(), entry.z()),
                    entry.triggerTick(),
                    TickPriority.byValue(entry.priority()),
                    entry.subTickOrder()));
        }
        ((SafeTickContainer) container).carpetExample$replaceAll(ticks);
        restoredTickCount += ticks.size();

        int kept = 0;
        if (keep != null) {
            for (Object raw : keep) {
                if (raw instanceof ScheduledTick<?> tick) {
                    // schedule() de-duplicates on (type, pos), so a pre-existing tick that the restore
                    // already covers is dropped here rather than duplicated.
                    container.schedule((ScheduledTick<T>) tick);
                    kept++;
                }
            }
        }
        return kept;
    }

    // -------------------------------------------------------------- save path

    /**
     * Captures a chunk's ticks into the store. Called from {@code ServerLevel.unload} HEAD, i.e.
     * right before the tick containers are unregistered from the level.
     */
    public static void snapshotChunk(final ServerLevel level, final LevelChunk chunk) {
        if (!enabled() || store == null) {
            return;
        }
        snapshot(level, chunk.getPos().pack(), chunk.getBlockTicks(), chunk.getFluidTicks());
    }

    private static void snapshot(final ServerLevel level,
                                 final long packedChunkPos,
                                 final TickContainerAccess<Block> blockTicks,
                                 final TickContainerAccess<Fluid> fluidTicks) {
        SafeTickContainer blockContainer = (SafeTickContainer) blockTicks;
        SafeTickContainer fluidContainer = (SafeTickContainer) fluidTicks;

        // A container still holding pendingTicks has never been unpacked, so it has no absolute
        // timing to capture. Leave whatever the store already holds for this chunk alone: that entry
        // came from a session in which the chunk *was* ticking, and absolute times never drift.
        if (blockContainer.carpetExample$hasPendingTicks() || fluidContainer.carpetExample$hasPendingTicks()) {
            return;
        }

        // Still queued for restore => the containers currently hold vanilla's re-anchored ticks,
        // exactly the data we intend to throw away. Overwriting the entry with them would silently
        // defeat the whole feature. This matters in practice: MC performs a flush save right after
        // startup, which can land before the restore.
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimensionId(level));
        if (data != null && data.pendingRestore.contains(packedChunkPos)) {
            return;
        }

        List<SafeTick> block = toSafeTicks(blockContainer.carpetExample$snapshotQueue());
        List<SafeTick> fluid = toSafeTicks(fluidContainer.carpetExample$snapshotQueue());
        store.put(dimensionId(level), packedChunkPos, new SafeSaveStore.ChunkSnapshot(block, fluid));
    }

    private static List<SafeTick> toSafeTicks(final List<?> scheduledTicks) {
        List<SafeTick> out = new ArrayList<>(scheduledTicks.size());
        for (Object raw : scheduledTicks) {
            if (!(raw instanceof ScheduledTick<?> tick)) {
                continue;
            }
            out.add(new SafeTick(
                    DebugLog.typeId(tick.type()),
                    tick.pos().getX(),
                    tick.pos().getY(),
                    tick.pos().getZ(),
                    tick.triggerTick(),
                    tick.priority().getValue(),
                    tick.subTickOrder()));
        }
        // Drain order, purely so the file is pleasant to inspect; restore uses the stored fields.
        out.sort((a, b) -> {
            int cmp = Long.compare(a.triggerTick(), b.triggerTick());
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(a.priority(), b.priority());
            return cmp != 0 ? cmp : Long.compare(a.subTickOrder(), b.subTickOrder());
        });
        return out;
    }

    /**
     * Snapshots every loaded chunk of every dimension and writes the side file.
     *
     * <p>Hooked at {@code MinecraftServer.saveAllChunks} HEAD rather than RETURN: with
     * {@code flush=true} vanilla runs {@code processUnloads} during the save, which unregisters tick
     * containers, so by RETURN part of the world would already be gone from
     * {@code LevelTicks.allContainers}.
     */
    public static void saveAll(final MinecraftServer server) {
        if (!enabled() || store == null || filePath == null) {
            return;
        }
        int chunks = 0;
        for (ServerLevel level : server.getAllLevels()) {
            chunks += snapshotLevel(level);
            SafeSaveStore.DimensionData data = store.dimension(dimensionId(level));
            data.subTickCount = ((LevelSubTickCountAccessor) level).carpetExample$getSubTickCount();
            data.gameTime = level.getGameTime(); // debug only
            snapshotBlockEvents(level, data);
        }
        store.setServerTickCount(server.getTickCount()); // debug only
        write();
        DebugLog.info("saved {} scheduled tick(s) over {} loaded chunk(s) + {} block event(s) to {}",
                store.totalTicks(), chunks, store.totalBlockEvents(), FILE_NAME);
    }

    /**
     * Captures the level-wide block-event queue verbatim, preserving drain order.
     *
     * <p>Unlike scheduled ticks there is no per-chunk bookkeeping to do: the queue lives on the
     * {@code ServerLevel}, is fully in memory, and is simply overwritten on every save.
     */
    private static void snapshotBlockEvents(final ServerLevel level, final SafeSaveStore.DimensionData data) {
        data.blockEvents.clear();
        for (BlockEventData event : ((ServerLevelBlockEventsAccessor) level).carpetExample$blockEvents()) {
            data.blockEvents.add(new SafeBlockEvent(
                    BuiltInRegistries.BLOCK.getKey(event.block()).toString(),
                    event.pos().getX(),
                    event.pos().getY(),
                    event.pos().getZ(),
                    event.paramA(),
                    event.paramB()));
        }
    }

    private static int snapshotLevel(final ServerLevel level) {
        Long2ObjectMap<?> blockContainers = ((TickContainerHolder) level.getBlockTicks()).carpetExample$containers();
        Long2ObjectMap<?> fluidContainers = ((TickContainerHolder) level.getFluidTicks()).carpetExample$containers();

        Set<Long> keys = new HashSet<>();
        LongIterator blockKeys = blockContainers.keySet().iterator();
        while (blockKeys.hasNext()) {
            keys.add(blockKeys.nextLong());
        }
        LongIterator fluidKeys = fluidContainers.keySet().iterator();
        while (fluidKeys.hasNext()) {
            keys.add(fluidKeys.nextLong());
        }

        int count = 0;
        for (Long boxed : keys) {
            long key = boxed;
            Object block = blockContainers.get(key);
            Object fluid = fluidContainers.get(key);
            if (!(block instanceof SafeTickContainer) || !(fluid instanceof SafeTickContainer)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            TickContainerAccess<Block> blockAccess = (TickContainerAccess<Block>) block;
            @SuppressWarnings("unchecked")
            TickContainerAccess<Fluid> fluidAccess = (TickContainerAccess<Fluid>) fluid;
            snapshot(level, key, blockAccess, fluidAccess);
            count++;
        }
        return count;
    }

    private static void write() {
        Path tmp = filePath.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(filePath.getParent());
            NbtIo.writeCompressed(store.save(), tmp);
            try {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            DebugLog.warn("failed to write {}: {}", FILE_NAME, e.toString());
        }
    }
}
