package com.carpet.safesave.safesave;

import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.blockevent.SafeBlockEvent;
import com.carpet.safesave.safesave.scheduled.SafeTick;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SafeSaveStore {

    public static final int FORMAT_VERSION = 5;

    private static final String KEY_VERSION = "version";
    private static final String KEY_LEVELS = "levels";
    private static final String KEY_DEBUG = "debug";
    private static final String KEY_DEBUG_SERVER_TICK = "serverTickCount";
    private static final String KEY_DEBUG_GAME_TIME = "gameTime";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_SUB_TICK_COUNT = "subTickCount";
    private static final String KEY_REGIONS = "regions";

    private static final String KEY_BLOCK_TICKS = "block";
    private static final String KEY_FLUID_TICKS = "fluid";
    private static final String KEY_CHUNK_BLOCK_EVENTS = "block_events";
    private static final String KEY_SNAPSHOT_GAME_TIME = "snapshot_game_time";//保存快照时的 gameTime；空值为Long.MIN_VALUE

    /**
     * {@code snapshotGameTime} 只用于计划刻的顺延重锚定（见 {@code ScheduledTickManager}）：
     * 区块卸载期间游戏时间继续走，重新加载时已过期的绝对触发时刻需要按保存时的剩余间隔顺延，
     * 语义等价于原版 {@code SavedTick.delay} 的重新锚定。{@code Long.MIN_VALUE} 表示缺失
     * （旧区块数据），恢复时保持绝对触发时刻不变。
     */
    public record ChunkSnapshot(List<SafeTick> blockTicks,
                                List<SafeTick> fluidTicks,
                                List<SafeBlockEvent> blockEvents,
                                long snapshotGameTime) {
        public ChunkSnapshot {
            blockTicks = List.copyOf(blockTicks);
            fluidTicks = List.copyOf(fluidTicks);
            blockEvents = List.copyOf(blockEvents);
        }

        public boolean isEmpty() {
            return this.blockTicks.isEmpty() && this.fluidTicks.isEmpty()
                    && this.blockEvents.isEmpty();
        }

        public int total() {
            return this.blockTicks.size() + this.fluidTicks.size() + this.blockEvents.size();
        }
    }

    public static final class DimensionData {
        public long subTickCount = -1L; //保存时的subTickCount，-1异常值
        public long gameTime = Long.MIN_VALUE;
        public ListTag regions;
    }

    private final Map<String, DimensionData> dimensions = new LinkedHashMap<>();
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

    public Map<String, Long> debugGameTimes() {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, DimensionData> entry : this.dimensions.entrySet()) {
            if (entry.getValue().gameTime != Long.MIN_VALUE) {
                out.put(entry.getKey(), entry.getValue().gameTime);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------- NBT

    public static CompoundTag saveChunkData(final ChunkSnapshot snapshot) {
        CompoundTag tag = new CompoundTag();
        if (snapshot == null || snapshot.isEmpty()) {
            return tag;
        }
        if (!snapshot.blockTicks().isEmpty()) {
            tag.put(KEY_BLOCK_TICKS, saveTicks(snapshot.blockTicks()));
        }
        if (!snapshot.fluidTicks().isEmpty()) {
            tag.put(KEY_FLUID_TICKS, saveTicks(snapshot.fluidTicks()));
        }
        if (!snapshot.blockEvents().isEmpty()) {
            tag.put(KEY_CHUNK_BLOCK_EVENTS, saveBlockEvents(snapshot.blockEvents()));
        }
        if (snapshot.snapshotGameTime() != Long.MIN_VALUE) {
            tag.putLong(KEY_SNAPSHOT_GAME_TIME, snapshot.snapshotGameTime());
        }
        return tag;
    }

    public static ChunkSnapshot loadChunkData(final CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        List<SafeTick> blockTicks = loadTicks(tag.getListOrEmpty(KEY_BLOCK_TICKS));
        List<SafeTick> fluidTicks = loadTicks(tag.getListOrEmpty(KEY_FLUID_TICKS));
        List<SafeBlockEvent> chunkEvents = loadBlockEvents(tag.getListOrEmpty(KEY_CHUNK_BLOCK_EVENTS));
        long snapshotGameTime = tag.getLongOr(KEY_SNAPSHOT_GAME_TIME, Long.MIN_VALUE);
        if (blockTicks.isEmpty() && fluidTicks.isEmpty() && chunkEvents.isEmpty()) {
            return null;
        }
        return new ChunkSnapshot(blockTicks, fluidTicks, chunkEvents, snapshotGameTime);
    }

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
        if (data.regions != null && !data.regions.isEmpty()) {
            levelTag.put(KEY_REGIONS, data.regions);
        }

        ListTag levels = new ListTag();
        levels.add(levelTag);
        root.put(KEY_DEBUG, debug);
        root.put(KEY_LEVELS, levels);
        return root;
    }

    public static SafeSaveStore load(final CompoundTag root) {
        SafeSaveStore store = new SafeSaveStore();
        int version = root.getIntOr(KEY_VERSION, 0);
        if (version != FORMAT_VERSION) {

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
                ListTag regions = levelTag.getListOrEmpty(KEY_REGIONS);
                data.regions = regions.isEmpty() ? null : regions;
            });
        }
        return store;
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
}
