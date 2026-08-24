package com.carpet.safesave.safesave;

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
 * 绝对计划刻数据的内存权威存储，及其 NBT 形式。
 *
 * <p>键结构为 {@code 维度 id -> 打包的区块坐标 -> } {@link ChunkSnapshot}。区块的一个条目表示
 * “这是我们最后一次观察该区块时的确切刻状态”，“观察”指 <em>区块被卸载</em> 或
 * <em>区块加载期间发生了一次世界保存</em>。由于 {@link SafeTick#triggerTick()} 是绝对的，
 * 一个一直未加载区块的陈旧条目仍然完全有效——不会漂移。
 *
 * <p>{@code debug} 块（{@link #serverTickCount}、{@link DimensionData#gameTime}）仅用于诊断写入，
 * 并刻意<strong>从不</strong>在恢复刻时被查阅。
 */
public final class SafeSaveStore {

    /**
     * 当前磁盘布局。v1 = 仅计划刻；v2 增加有序方块事件队列；
     * v3 增加持有移动活塞的区块集合。
     * 旧版本仍可读取（{@link #MIN_READABLE_VERSION}），只是不包含方块事件。
     */
    public static final int FORMAT_VERSION = 3;

    /** 此构建仍可读取的最旧布局。 */
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
    /** 待处理方块事件的有序队列（v2+） */
    private static final String KEY_BLOCK_EVENTS = "block_events";

    /** 每区块的绝对刻列表。列表按取出顺序存储，纯粹为了可读性。 */
    public record ChunkSnapshot(List<SafeTick> blockTicks, List<SafeTick> fluidTicks) {
        public boolean isEmpty() {
            return this.blockTicks.isEmpty() && this.fluidTicks.isEmpty();
        }

        public int total() {
            return this.blockTicks.size() + this.fluidTicks.size();
        }
    }

    /** 每维度状态。 */
    public static final class DimensionData {
        /** 打包的 {@link ChunkPos} -> 快照。实时快照存储；每次保存时重写。 */
        public final Map<Long, ChunkSnapshot> chunks = new LinkedHashMap<>();
        /**
         * <em>本会话从磁盘读取</em>且尚未应用的区块键——恢复队列，刻意与 {@link #chunks} 分开。
         *
         * <p>如果没有这个分离，落在两条恢复路径之间的保存会用已恢复的容器重新填充
         * {@link #chunks}，后一条路径就会把同一个区块应用第二次。虽然无害（数据相同且幂等），
         * 但会重复计数并模糊语义。永不持久化。
         */
        public final Set<Long> pendingRestore = new LinkedHashSet<>();
        /** 当 {@link #blockEvents} 仍持有未应用的磁盘数据时为 {@code true}。 */
        public boolean blockEventsPendingRestore;
        /** 保存时的 {@code Level.subTickCount}；{@code -1} = 未知 */
        public long subTickCount = -1L;
        /**
         * 待处理的方块事件，<strong>按取出顺序</strong>。世界级而非区块级，因为
         * {@code ServerLevel.blockEvents} 是单一的世界级队列。
         */
        public final List<SafeBlockEvent> blockEvents = new ArrayList<>();
        /** 仅调试用——从不用于恢复刻 */
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
    /** 仅调试用——从不用于恢复刻 */
    private int serverTickCount = -1;

    // -------------------------------------------------------------- 访问器

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
     * 替换（当 {@code snapshot} 为空时则移除）某个区块的条目。
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
     * 从恢复队列中取出一个区块并返回其快照，因此即使期间一次保存重新填充了
     * {@link DimensionData#chunks}，它也不可能被应用两次。
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
                // 仅调试用
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
            // 未知布局：拒绝而不是悄然错误地恢复时间。
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

    /** 各维度游戏时间的调试快照，供 {@code /safesave status} 使用。 */
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
