package com.example.carpet.safesave;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * In-memory authoritative store of absolute scheduled-tick data, plus its NBT form.
 *
 * <p>Keyed {@code dimension id -> packed chunk pos -> } {@link ChunkSnapshot}. An entry for a chunk
 * means "this is the exact tick state of that chunk as of the last time we observed it", where
 * "observed" is either <em>the chunk was unloaded</em> or <em>a world save happened while it was
 * loaded</em>. Because {@link SafeTick#triggerTick()} is absolute, a stale entry for a chunk that
 * has been unloaded the whole time is still perfectly valid — nothing drifts.
 *
 * <p>The {@code debug} block ({@link #serverTickCount}, {@link DimensionData#gameTime}) is written
 * for diagnostics only and is deliberately <strong>never</strong> consulted while restoring ticks.
 */
public final class SafeSaveStore {

    /**
     * Current on-disk layout. v1 = scheduled ticks only; v2 adds the ordered block-event queue;
     * v3 adds the set of chunks holding a moving piston.
     * Older versions are still readable ({@link #MIN_READABLE_VERSION}), they simply carry no
     * block events.
     */
    public static final int FORMAT_VERSION = 3;

    /** Oldest layout this build can still read. */
    public static final int MIN_READABLE_VERSION = 1;

    private static final String KEY_VERSION = "version";
    private static final String KEY_LEVELS = "levels";
    private static final String KEY_DEBUG = "debug";
    private static final String KEY_DEBUG_SERVER_TICK = "serverTickCount";
    private static final String KEY_DEBUG_GAME_TIME = "gameTime";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_SUB_TICK_COUNT = "subTickCount";
    private static final String KEY_CHUNKS = "chunks";
    private static final String KEY_CHUNK_X = "x";
    private static final String KEY_CHUNK_Z = "z";
    private static final String KEY_BLOCK_TICKS = "block";
    private static final String KEY_FLUID_TICKS = "fluid";
    /** ordered queue of pending block events (v2+) */
    private static final String KEY_BLOCK_EVENTS = "block_events";
    /** packed chunk positions that held a PistonMovingBlockEntity at save time (v3+) */
    private static final String KEY_PISTON_CHUNKS = "piston_chunks";

    /** Per-chunk absolute tick lists. Lists are stored in drain order purely for readability. */
    public record ChunkSnapshot(List<SafeTick> blockTicks, List<SafeTick> fluidTicks) {
        public boolean isEmpty() {
            return this.blockTicks.isEmpty() && this.fluidTicks.isEmpty();
        }

        public int total() {
            return this.blockTicks.size() + this.fluidTicks.size();
        }
    }

    /** Per-dimension state. */
    public static final class DimensionData {
        /** packed {@link ChunkPos} -> snapshot. The live snapshot store; rewritten on every save. */
        public final Map<Long, ChunkSnapshot> chunks = new LinkedHashMap<>();
        /**
         * Chunk keys that were read <em>from disk this session</em> and have not been applied yet —
         * the restore queue, deliberately kept separate from {@link #chunks}.
         *
         * <p>Without this separation a save landing between two restore paths would re-populate
         * {@link #chunks} from the already-restored containers, and the later path would then apply
         * the same chunk a second time. Not harmful (the data is identical and idempotent) but it
         * double-counts and muddies the semantics. Never persisted.
         */
        public final Set<Long> pendingRestore = new LinkedHashSet<>();
        /** {@code true} while {@link #blockEvents} still holds un-applied on-disk data. */
        public boolean blockEventsPendingRestore;
        /**
         * Packed chunk positions that held a {@code PistonMovingBlockEntity} when last observed.
         * Recorded so that on load we know which chunks a mid-flight push spans <em>before</em> any of
         * them are loaded - a set that cannot be discovered by scanning, since the chunks are not in
         * memory yet (#3).
         */
        public final Set<Long> pistonChunks = new LinkedHashSet<>();
        /** Subset of {@link #pistonChunks} read from disk and not yet confirmed tickable. Transient. */
        public final Set<Long> pistonChunksAwaitingTicking = new LinkedHashSet<>();
        /** {@code Level.subTickCount} at save time; {@code -1} = unknown */
        public long subTickCount = -1L;
        /**
         * Pending block events, <strong>in drain order</strong>. Level-wide, not per-chunk, because
         * {@code ServerLevel.blockEvents} is a single level-wide queue.
         */
        public final List<SafeBlockEvent> blockEvents = new ArrayList<>();
        /** debug only — never used to restore ticks */
        public long gameTime = Long.MIN_VALUE;

        public int totalTicks() {
            int total = 0;
            for (ChunkSnapshot snapshot : this.chunks.values()) {
                total += snapshot.total();
            }
            return total;
        }
    }

    private final Map<String, DimensionData> dimensions = new LinkedHashMap<>();
    /** debug only — never used to restore ticks */
    private int serverTickCount = -1;

    // -------------------------------------------------------------- accessors

    public DimensionData dimension(final String dimensionId) {
        return this.dimensions.computeIfAbsent(dimensionId, k -> new DimensionData());
    }

    public DimensionData dimensionOrNull(final String dimensionId) {
        return this.dimensions.get(dimensionId);
    }

    public Map<String, DimensionData> dimensions() {
        return this.dimensions;
    }

    public int serverTickCount() {
        return this.serverTickCount;
    }

    public void setServerTickCount(final int serverTickCount) {
        this.serverTickCount = serverTickCount;
    }

    public boolean isEmpty() {
        return this.dimensions.isEmpty();
    }

    public int totalBlockEvents() {
        int total = 0;
        for (DimensionData data : this.dimensions.values()) {
            total += data.blockEvents.size();
        }
        return total;
    }

    public int totalTicks() {
        int total = 0;
        for (DimensionData data : this.dimensions.values()) {
            total += data.totalTicks();
        }
        return total;
    }

    /**
     * Replaces (or removes, when {@code snapshot} is empty) the entry for one chunk.
     */
    public void put(final String dimensionId, final long packedChunkPos, final ChunkSnapshot snapshot) {
        DimensionData data = this.dimension(dimensionId);
        if (snapshot == null || snapshot.isEmpty()) {
            data.chunks.remove(packedChunkPos);
        } else {
            data.chunks.put(packedChunkPos, snapshot);
        }
    }

    /**
     * Dequeues one chunk from the restore queue and returns its snapshot, so it cannot be applied
     * twice even if a save re-populates {@link DimensionData#chunks} in the meantime.
     */
    public ChunkSnapshot take(final String dimensionId, final long packedChunkPos) {
        DimensionData data = this.dimensions.get(dimensionId);
        if (data == null || !data.pendingRestore.remove(packedChunkPos)) {
            return null;
        }
        return data.chunks.remove(packedChunkPos);
    }

    // ------------------------------------------------------------------- NBT

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putInt(KEY_VERSION, FORMAT_VERSION);

        CompoundTag debug = new CompoundTag();
        debug.putInt(KEY_DEBUG_SERVER_TICK, this.serverTickCount);

        ListTag levels = new ListTag();
        for (Map.Entry<String, DimensionData> entry : this.dimensions.entrySet()) {
            DimensionData data = entry.getValue();
            CompoundTag levelTag = new CompoundTag();
            levelTag.putString(KEY_DIMENSION, entry.getKey());
            levelTag.putLong(KEY_SUB_TICK_COUNT, data.subTickCount);
            if (data.gameTime != Long.MIN_VALUE) {
                // debug only
                levelTag.putLong(KEY_DEBUG_GAME_TIME, data.gameTime);
            }

            ListTag chunks = new ListTag();
            for (Map.Entry<Long, ChunkSnapshot> chunkEntry : data.chunks.entrySet()) {
                ChunkSnapshot snapshot = chunkEntry.getValue();
                if (snapshot.isEmpty()) {
                    continue;
                }
                ChunkPos pos = ChunkPos.unpack(chunkEntry.getKey());
                CompoundTag chunkTag = new CompoundTag();
                chunkTag.putInt(KEY_CHUNK_X, pos.x());
                chunkTag.putInt(KEY_CHUNK_Z, pos.z());
                if (!snapshot.blockTicks().isEmpty()) {
                    chunkTag.put(KEY_BLOCK_TICKS, saveTicks(snapshot.blockTicks()));
                }
                if (!snapshot.fluidTicks().isEmpty()) {
                    chunkTag.put(KEY_FLUID_TICKS, saveTicks(snapshot.fluidTicks()));
                }
                chunks.add(chunkTag);
            }
            levelTag.put(KEY_CHUNKS, chunks);

            if (!data.pistonChunks.isEmpty()) {
                long[] packed = new long[data.pistonChunks.size()];
                int i = 0;
                for (Long key : data.pistonChunks) {
                    packed[i++] = key;
                }
                levelTag.putLongArray(KEY_PISTON_CHUNKS, packed);
            }

            if (!data.blockEvents.isEmpty()) {
                ListTag events = new ListTag();
                for (SafeBlockEvent event : data.blockEvents) {
                    events.add(event.save());
                }
                levelTag.put(KEY_BLOCK_EVENTS, events);
            }

            levels.add(levelTag);
        }

        root.put(KEY_DEBUG, debug);
        root.put(KEY_LEVELS, levels);
        return root;
    }

    private static ListTag saveTicks(final List<SafeTick> ticks) {
        ListTag list = new ListTag();
        for (SafeTick tick : ticks) {
            list.add(tick.save());
        }
        return list;
    }

    public static SafeSaveStore load(final CompoundTag root) {
        SafeSaveStore store = new SafeSaveStore();
        int version = root.getIntOr(KEY_VERSION, 0);
        if (version < MIN_READABLE_VERSION || version > FORMAT_VERSION) {
            // Unknown layout: refuse rather than silently mis-restoring timings.
            throw new IllegalStateException("unsupported safe-save format version " + version
                    + " (readable range " + MIN_READABLE_VERSION + ".." + FORMAT_VERSION + ")");
        }
        root.getCompound(KEY_DEBUG).ifPresent(
                debug -> store.setServerTickCount(debug.getIntOr(KEY_DEBUG_SERVER_TICK, -1)));

        ListTag levels = root.getListOrEmpty(KEY_LEVELS);
        for (int i = 0; i < levels.size(); i++) {
            final int index = i;
            levels.getCompound(index).ifPresent(levelTag -> {
                String dimensionId = levelTag.getStringOr(KEY_DIMENSION, "");
                if (dimensionId.isEmpty()) {
                    return;
                }
                DimensionData data = store.dimension(dimensionId);
                data.subTickCount = levelTag.getLongOr(KEY_SUB_TICK_COUNT, -1L);
                data.gameTime = levelTag.getLongOr(KEY_DEBUG_GAME_TIME, Long.MIN_VALUE);

                levelTag.getLongArray(KEY_PISTON_CHUNKS).ifPresent(packed -> {
                    for (long key : packed) {
                        data.pistonChunks.add(key);
                        data.pistonChunksAwaitingTicking.add(key);
                    }
                });

                ListTag events = levelTag.getListOrEmpty(KEY_BLOCK_EVENTS);
                for (int e = 0; e < events.size(); e++) {
                    events.getCompound(e).ifPresent(eventTag -> {
                        SafeBlockEvent event = SafeBlockEvent.load(eventTag);
                        if (event != null) {
                            data.blockEvents.add(event);
                            data.blockEventsPendingRestore = true;
                        }
                    });
                }

                ListTag chunks = levelTag.getListOrEmpty(KEY_CHUNKS);
                for (int c = 0; c < chunks.size(); c++) {
                    chunks.getCompound(c).ifPresent(chunkTag -> {
                        long packed = ChunkPos.pack(
                                chunkTag.getIntOr(KEY_CHUNK_X, 0),
                                chunkTag.getIntOr(KEY_CHUNK_Z, 0));
                        List<SafeTick> blockTicks = loadTicks(chunkTag.getListOrEmpty(KEY_BLOCK_TICKS));
                        List<SafeTick> fluidTicks = loadTicks(chunkTag.getListOrEmpty(KEY_FLUID_TICKS));
                        if (!blockTicks.isEmpty() || !fluidTicks.isEmpty()) {
                            data.chunks.put(packed, new ChunkSnapshot(blockTicks, fluidTicks));
                            data.pendingRestore.add(packed);
                        }
                    });
                }
            });
        }
        return store;
    }

    private static List<SafeTick> loadTicks(final ListTag list) {
        List<SafeTick> ticks = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            list.getCompound(index).ifPresent(tag -> {
                SafeTick tick = SafeTick.load(tag);
                if (tick != null) {
                    ticks.add(tick);
                }
            });
        }
        return ticks;
    }

    /** Debug snapshot of per-dimension game times, for {@code /safesave status}. */
    public Map<String, Long> debugGameTimes() {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, DimensionData> entry : this.dimensions.entrySet()) {
            if (entry.getValue().gameTime != Long.MIN_VALUE) {
                out.put(entry.getKey(), entry.getValue().gameTime);
            }
        }
        return out;
    }
}
