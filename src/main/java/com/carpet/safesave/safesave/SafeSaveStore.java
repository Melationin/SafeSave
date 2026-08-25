package com.carpet.safesave.safesave;
import com.carpet.safesave.safesave.blockentity.SafePiston;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.blockevent.SafeBlockEvent;
import com.carpet.safesave.safesave.scheduled.SafeTick;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * 当前磁盘布局：按区块保存计划刻、方块事件与移动活塞快照。
     */
    public static final int FORMAT_VERSION = 5;

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
    /** 每区块的方块事件列表，按全局 {@code order} 升序 */
    private static final String KEY_CHUNK_BLOCK_EVENTS = "block_events";
    /** 每区块的移动活塞状态列表 */
    private static final String KEY_CHUNK_PISTONS = "pistons";

    /** 每区块的绝对刻列表 + 方块事件列表 + 移动活塞列表。 */
    public record ChunkSnapshot(List<SafeTick> blockTicks,
                                List<SafeTick> fluidTicks,
                                List<SafeBlockEvent> blockEvents,
                                List<SafePiston> pistons) {
        public ChunkSnapshot {
            blockTicks = List.copyOf(blockTicks);
            fluidTicks = List.copyOf(fluidTicks);
            blockEvents = List.copyOf(blockEvents);
            pistons = List.copyOf(pistons);
        }

        public boolean isEmpty() {
            return this.blockTicks.isEmpty() && this.fluidTicks.isEmpty()
                    && this.blockEvents.isEmpty() && this.pistons.isEmpty();
        }

        public int total() {
            return this.blockTicks.size() + this.fluidTicks.size()
                    + this.blockEvents.size() + this.pistons.size();
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
        /** 保存时的 {@code Level.subTickCount}；{@code -1} = 未知 */
        public long subTickCount = -1L;
        /** 仅调试用——从不用于恢复刻 */
        public long gameTime = Long.MIN_VALUE;

        /** 仅计划刻/流体刻数量（不含方块事件）。 */
        public int totalScheduledTicks() {
            int total = 0;
            for (ChunkSnapshot snapshot : this.chunks.values()) {
                total += snapshot.blockTicks().size() + snapshot.fluidTicks().size();
            }
            return total;
        }

        /** 该维度所有条目数（含方块事件）。 */
        public int totalStoredEntries() {
            int total = 0;
            for (ChunkSnapshot snapshot : this.chunks.values()) {
                total += snapshot.total();
            }
            return total;
        }

        /** 该维度所有方块事件数量。 */
        public int totalBlockEvents() {
            int total = 0;
            for (ChunkSnapshot snapshot : this.chunks.values()) {
                total += snapshot.blockEvents().size();
            }
            return total;
        }

        /** 该维度所有移动活塞数量。 */
        public int totalPistons() {
            int total = 0;
            for (ChunkSnapshot snapshot : this.chunks.values()) {
                total += snapshot.pistons().size();
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
            total += data.totalBlockEvents();
        }
        return total;
    }

    public int totalTicks() {
        int total = 0;
        for (DimensionData data : this.dimensions.values()) {
            total += data.totalScheduledTicks();
        }
        return total;
    }

    /** 含方块事件的总数（诊断用）。 */
    public int totalStoredEntries() {
        int total = 0;
        for (DimensionData data : this.dimensions.values()) {
            total += data.totalStoredEntries();
        }
        return total;
    }

    /** 所有移动活塞数量。 */
    public int totalPistons() {
        int total = 0;
        for (DimensionData data : this.dimensions.values()) {
            total += data.totalPistons();
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

    /**
     * 序列化单个维度的数据，作为该维度独立的存档文件
     * （根节点仍含 {@code version}/{@code debug}/{@code levels}，但 {@code levels} 只有这一个维度）。
     */
    public CompoundTag saveDimension(final String dimensionId, final DimensionData data) {
        CompoundTag root = new CompoundTag();
        root.putInt(KEY_VERSION, FORMAT_VERSION);

        CompoundTag debug = new CompoundTag();
        debug.putInt(KEY_DEBUG_SERVER_TICK, this.serverTickCount);

        CompoundTag levelTag = new CompoundTag();
        levelTag.putString(KEY_DIMENSION, dimensionId);
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
            if (!snapshot.blockEvents().isEmpty()) {
                chunkTag.put(KEY_CHUNK_BLOCK_EVENTS, saveBlockEvents(snapshot.blockEvents()));
            }
            if (!snapshot.pistons().isEmpty()) {
                chunkTag.put(KEY_CHUNK_PISTONS, savePistons(snapshot.pistons()));
            }
            chunks.add(chunkTag);
        }
        levelTag.put(KEY_CHUNKS, chunks);

        ListTag levels = new ListTag();
        levels.add(levelTag);
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

    private static ListTag saveBlockEvents(final List<SafeBlockEvent> events) {
        ListTag list = new ListTag();
        for (SafeBlockEvent event : events) {
            list.add(event.save());
        }
        return list;
    }

    private static ListTag savePistons(final List<SafePiston> pistons) {
        ListTag list = new ListTag();
        for (SafePiston piston : pistons) {
            list.add(piston.save());
        }
        return list;
    }

    public static SafeSaveStore load(final CompoundTag root) {
        SafeSaveStore store = new SafeSaveStore();
        int version = root.getIntOr(KEY_VERSION, 0);
        if (version != FORMAT_VERSION) {
            // 未知布局：拒绝而不是悄然错误地恢复时间。
            throw new IllegalStateException("unsupported safe-save format version " + version
                    + " (expected " + FORMAT_VERSION + ")");
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


                ListTag chunks = levelTag.getListOrEmpty(KEY_CHUNKS);
                for (int c = 0; c < chunks.size(); c++) {
                    chunks.getCompound(c).ifPresent(chunkTag -> {
                        long packed = ChunkPos.pack(
                                chunkTag.getIntOr(KEY_CHUNK_X, 0),
                                chunkTag.getIntOr(KEY_CHUNK_Z, 0));
                        List<SafeTick> blockTicks = loadTicks(chunkTag.getListOrEmpty(KEY_BLOCK_TICKS));
                        List<SafeTick> fluidTicks = loadTicks(chunkTag.getListOrEmpty(KEY_FLUID_TICKS));
                        List<SafeBlockEvent> chunkEvents = loadBlockEvents(chunkTag.getListOrEmpty(KEY_CHUNK_BLOCK_EVENTS));
                        List<SafePiston> pistons = loadPistons(chunkTag.getListOrEmpty(KEY_CHUNK_PISTONS));
                        if (!blockTicks.isEmpty() || !fluidTicks.isEmpty() || !chunkEvents.isEmpty() || !pistons.isEmpty()) {
                            data.chunks.put(packed, new ChunkSnapshot(blockTicks, fluidTicks, chunkEvents, pistons));
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

    private static List<SafeBlockEvent> loadBlockEvents(final ListTag list) {
        List<SafeBlockEvent> events = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            list.getCompound(index).ifPresent(tag -> {
                SafeBlockEvent event = SafeBlockEvent.load(tag);
                if (event != null) {
                    events.add(event);
                }
            });
        }
        events.sort(BlockEventManager.COMPARE_BY_ORDER);
        return events;
    }

    private static List<SafePiston> loadPistons(final ListTag list) {
        List<SafePiston> pistons = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            list.getCompound(index).ifPresent(tag -> {
                SafePiston piston = SafePiston.load(tag);
                if (piston != null) {
                    pistons.add(piston);
                }
            });
        }
        return pistons;
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
