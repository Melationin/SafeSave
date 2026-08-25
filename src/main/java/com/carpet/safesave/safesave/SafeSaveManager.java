package com.carpet.safesave.safesave;

import static com.carpet.safesave.util.DimensionIds.dimensionId;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.blockevent.SafeBlockEvent;
import com.carpet.safesave.safesave.blockentity.PistonManager;
import com.carpet.safesave.safesave.entity.EntityOrderManager;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import com.carpet.safesave.safesave.scheduled.SafeTickContainer;
import com.carpet.safesave.util.Util;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.ticks.TickContainerAccess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * SafeSave 的协调层：生命周期钩子、按维度的元数据文件读写，以及三个子管理器的编排。
 *
 * <p>职责分工：
 * <ul>
 *   <li>{@link ScheduledTickManager} —— 计划刻的快照/恢复；</li>
 *   <li>{@link BlockEventManager} —— 方块事件的快照/恢复；</li>
 *   <li>{@link PistonManager} —— 移动活塞（方块实体）的创建顺序恢复。</li>
 * </ul>
 *
 * <p>持久化布局：计划刻与方块事件直接存在各区块 NBT 的 {@code safeSave} 子节点中；
 * 世界级元数据（{@code Level.subTickCount} + 调试字段）存在每个维度的
 * {@code <维度目录>/data/safesave.dat} 旁置文件中。
 */
public final class SafeSaveManager {

    private static final String FILE_NAME = "safesave.dat";

    /** 世界级元数据存储；服务端加载前为 {@code null}。 */
    private static SafeSaveStore store;

    /** 在一次性“首刻前冻结”被处理之前为 {@code true}。 */
    private static boolean freezeArmed;
    /** 供 {@code /safesave status} 使用的诊断数据（从区块 NBT 读取的计数）。 */
    private static final AtomicInteger loadedTickCount = new AtomicInteger();
    private static final AtomicInteger loadedBlockEventCount = new AtomicInteger();

    /**
     * 每个维度上次在<em>非冻结</em>世界刻开始时所观察到的已就绪区块集合。
     *
     * <p>每个正常 tick 都会把它替换为当刻的“已解包容器”集合；下一次 tick 时，当前集合比上次
     * 多出的键就是新加载区块。冻结期间刻意不更新，解冻后会把冻结期间加载的区块一并重建。
     */
    private static final Map<String, LongSet> knownChunks = new HashMap<>();

    /**
     * 已从区块 NBT 读出、但尚未在非冻结 tick 开头合并入队的区块快照。
     *
     * <p>键：维度 id -> 打包区块坐标 -> 快照。由区块加载路径（{@link #onChunkTagRead}）填充，
     * 由 {@link #rebuildNewChunks} 消费；卸载/保存路径在写入前会优先使用这里的快照，
     * 以保护 load→rebuild 窗口。
     */
    private static final Map<String, Map<Long, SafeSaveStore.ChunkSnapshot>> pendingChunks =
            new ConcurrentHashMap<>();

    /**
     * {@code SerializableChunkData.copyOf} 与本线程异步的 {@code write()} 之间的交接表。
     * 用身份比较，因为 record 的 equals 是按字段比较的。
     */
    private static final Map<SerializableChunkData, CompoundTag> chunkWrites =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private SafeSaveManager() {
    }

    public static boolean enabled() {
        return SafeSaveRules.safeSave;
    }

    public static SafeSaveStore store() {
        return store;
    }

    public static int loadedTickCount() {
        return loadedTickCount.get();
    }

    public static int restoredTickCount() {
        return ScheduledTickManager.restoredCount();
    }

    public static int droppedTickCount() {
        return ScheduledTickManager.droppedCount();
    }

    public static int loadedBlockEventCount() {
        return loadedBlockEventCount.get();
    }

    public static int restoredBlockEventCount() {
        return BlockEventManager.restoredCount();
    }

    public static int droppedBlockEventCount() {
        return BlockEventManager.droppedCount();
    }

    /** 当此世界仍有待处理（未应用）的恢复条目时为 {@code true}。 */
    public static int pendingChunkCount(final ServerLevel level) {
        if (store == null) {
            return 0;
        }
        Map<Long, SafeSaveStore.ChunkSnapshot> pending = pendingChunks.get(dimensionId(level));
        return pending == null ? 0 : pending.size();
    }

    /** 世界刻日志行的调试辅助方法。 */
    public static int pendingBlockEventCount(final Level level) {
        return BlockEventManager.pendingCount(level);
    }


    // ------------------------------------------------------------ 服务端钩子

    /**
     * 由 Carpet 的 {@code onServerLoaded} 调用，它在 {@code MinecraftServer.loadLevel} 的 HEAD 处触发——
     * 即在 {@code createLevels}/{@code prepareLevels} 之前。这里读取每个维度的旁置元数据文件，
     * 恢复 {@code Level.subTickCount} 所需的 {@code subTickCount} 必须在第一个区块解包前就位。
     */
    public static void onServerLoaded(final MinecraftServer server) {
        store = new SafeSaveStore();
        ScheduledTickManager.init(store);
        BlockEventManager.init(store);
        PistonManager.reset();
        EntityOrderManager.reset();
        knownChunks.clear();
        pendingChunks.clear();
        chunkWrites.clear();
        freezeArmed = true;
        loadedTickCount.set(0);
        loadedBlockEventCount.set(0);

        if (!enabled()) {
            DebugLog.info("rule 'safeSave' is off; not reading {}", FILE_NAME);
            return;
        }

        Path root = server.getWorldPath(LevelResource.ROOT);

        // 每个维度一个旁置元数据文件，位于 <维度目录>/data/safesave.dat。
        // 维度目录结构：<world>/dimensions/<namespace>/<path>/，扫描两层。
        Path dimensionsDir = root.resolve("dimensions");
        int loadedFiles = 0;
        if (Files.isDirectory(dimensionsDir)) {
            try (Stream<Path> namespaces = Files.list(dimensionsDir)) {
                for (Path nsDir : namespaces.filter(Files::isDirectory).toList()) {
                    try (Stream<Path> dimensionDirs = Files.list(nsDir)) {
                        for (Path dimDir : dimensionDirs.filter(Files::isDirectory).toList()) {
                            Path file = dimDir.resolve("data").resolve(FILE_NAME);
                            if (Files.isRegularFile(file) && loadFile(file)) {
                                loadedFiles++;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                DebugLog.warn("failed to scan {}: {}", dimensionsDir, e.toString());
            }
        }

        if (loadedFiles == 0) {
            DebugLog.info("no {} found; this session starts from vanilla chunk ticks", FILE_NAME);
        } else {
            DebugLog.info("loaded world metadata from {} safesave file(s) (debug: serverTick={} gameTimes={})",
                    loadedFiles, store.serverTickCount(), store.debugGameTimes());
        }
    }

    /** 维度目录的 data/ 子目录（如 <world>/dimensions/minecraft/overworld/data）。 */
    private static Path dimensionDataDir(final ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(level.dimension(), root).resolve("data");
    }

    /**
     * 读取一个维度旁置元数据文件并合并进 {@link #store}。
     *
     * @return {@code true} 当文件读取成功且包含维度数据
     */
    private static boolean loadFile(final Path file) {
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            SafeSaveStore loaded = SafeSaveStore.load(tag);
            if (loaded.dimensions().isEmpty()) {
                DebugLog.warn("{} contains no dimension data - skipped", file.getFileName());
                return false;
            }
            // 文件内 dimension 字段即维度 id；debug 字段取第一个加载到的即可
            if (store.serverTickCount() < 0) {
                store.setServerTickCount(loaded.serverTickCount());
            }
            store.dimensions().putAll(loaded.dimensions());
            return true;
        } catch (Exception e) {
            DebugLog.warn("failed to read {} - skipping it: {}", file.getFileName(), e.toString());
            return false;
        }
    }

    /**
     * 由 Carpet 的 {@code onServerClosed} 调用，它在 {@code MinecraftServer.stopServer} 的
     * <em>HEAD</em> 处触发。
     *
     * <p>刻意<strong>不</strong>丢弃存储：关闭流程仍需执行其区块卸载循环，之后在
     * {@code stopServer} 更下方还有 {@code saveAllChunks(false, true, false)}。所有会话级状态改由
     * {@link #onServerLoaded} 重新初始化，因此不会有任何残留泄漏到后续的（单人）世界。
     */
    public static void onServerClosed() {
        if (enabled() && store != null) {
            DebugLog.info("server closing; chunk NBT data will be flushed by the shutdown save; {} chunk(s) still pending rebuild",
                    pendingChunks.values().stream().mapToInt(Map::size).sum());
        }
    }

    /**
     * 在 {@code MinecraftServer.prepareLevels} 的 HEAD 处调用：此时每个 {@code ServerLevel} 都已存在，
     * 但还没有任何区块被准备好用于刻。这是世界与存储同时可用的最早时机。
     */
    public static void onLevelsCreated(final MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!enabled() || store == null) {
                continue;
            }
            SafeSaveStore.DimensionData data = store.dimensionOrNull(dimensionId(level));
            if (data == null) {
                continue;
            }
            ScheduledTickManager.restoreSubTickCount(level, data);
        }
    }

    /**
     * 在 {@code MinecraftServer.tickServer} 的 HEAD 处调用一次。
     *
     * <p>只要规则开启就先冻结服务端，使一切不再推进，直到操作员确认了恢复的状态。冻结期间
     * {@code TickRateManager.runsNormally()} 为 {@code false}，因此 {@code ServerLevel.tick}
     * 会完全跳过 {@code blockTicks}/{@code fluidTicks} 阶段，{@code gameTime} 也不移动——
     * 恢复的刻原封不动地等待。
     */
    public static void onFirstServerTick(final MinecraftServer server) {
        if (!freezeArmed) {
            return;
        }
        freezeArmed = false;
        if (!enabled()) {
            return;
        }
        server.tickRateManager().setFrozen(true);
        DebugLog.info("froze the server before its first tick. "
                        + "Run '/tick unfreeze' once you are happy with the restored state.");
    }

    /**
     * 在 {@code ServerLevel.tick} 的 HEAD 处调用。编排活塞顺序重建、计划刻/方块事件的
     * 新加载区块统一重建，以及实体 tick 顺序重建。
     */
    public static void onLevelTickStart(final ServerLevel level) {
        if (!enabled() || store == null) {
            return;
        }
        PistonManager.onLevelTickStart(level);
        Set<Long> newChunks = rebuildNewChunks(level);
        EntityOrderManager.rebuildChunks(level, newChunks);
    }

    /**
     * 每个<em>非冻结</em> tick 开头统一重建新加载区块的计划刻与方块事件。
     *
     * <p>判断“新加载”的方式是对比 {@code LevelTicks.allContainers}：每个正常 tick 记录当时
     * 已就绪（已注册且已解包）的刻容器集合，下一次正常 tick 时，当前集合比上次多出的键就是
     * 本 tick 新加载的区块。实际消费集合是 {@code ready ∩ pendingChunks}，新加载但没有恢复数据的
     * 区块不会重建。
     *
     * <p>冻结期间刻意<em>不</em>更新 {@link #knownChunks}：启动冻结或 {@code /tick freeze} 期间
     * 加载的区块，会在解冻后的第一个正常 tick 被统一视为新加载并恢复。
     *
     * @return 本 tick 实际重建的候选区块集合（可能为空），供实体顺序协调使用
     */
    private static Set<Long> rebuildNewChunks(final ServerLevel level) {
        if (!level.tickRateManager().runsNormally()) {
            return Set.of();
        }
        String dimension = dimensionId(level);
        LongSet ready = TickContainers.collectReadyChunks(level);

        // 第一个正常 tick 没有“上一次”可比较：视作已知集合为空，这样 prepareLevels 期间已经
        // 加载好的区块也会在此时统一重建。
        LongSet previous = knownChunks.get(dimension);
        if (previous == null) {
            previous = new LongOpenHashSet();
        }
        // 诊断用：ready 相对 previous 多出的键（新加载）。
        LongOpenHashSet newKeys = new LongOpenHashSet(ready.size());
        newKeys.addAll(ready);
        newKeys.removeAll(previous);

        Map<Long, SafeSaveStore.ChunkSnapshot> pending = pendingChunks.get(dimension);
        LongOpenHashSet candidates = new LongOpenHashSet();
        if (pending != null) {
            // 只处理“已就绪且处于待恢复映射”的区块。newKeys 负责识别新加载，
            // 而 ready ∩ pending 额外兜底“卸载→重载发生在两个正常 tick 之间、未从 previous 消失”的边界。
            for (long boxed : ready) {
                if (pending.containsKey(boxed)) {
                    candidates.add(boxed);
                }
            }
        }

        Long2ObjectMap<?> blockContainers = TickContainers.blockContainers(level);
        Long2ObjectMap<?> fluidContainers = TickContainers.fluidContainers(level);
        List<SafeBlockEvent> blockEventsToRestore = new ArrayList<>();
        int rebuilt = 0;
        for (long key : candidates) {
            Object block = blockContainers.get(key);
            Object fluid = fluidContainers.get(key);
            if (!(block instanceof SafeTickContainer) || !(fluid instanceof SafeTickContainer)) {
                continue;
            }
            SafeSaveStore.ChunkSnapshot snapshot = pending.remove(key);
            if (snapshot == null) {
                continue;
            }
            ScheduledTickManager.restoreChunkTicks(level, key, snapshot, block, fluid);
            rebuilt++;
            blockEventsToRestore.addAll(snapshot.blockEvents());
        }
        // 同一个正常 tick 重建的所有区块，其方块事件一起按全局顺序合并回世界队列。
        if (!blockEventsToRestore.isEmpty()) {
            BlockEventManager.restoreChunkEvents(level, blockEventsToRestore);
        }

        // 只记录“就绪”的区块；尚未解包的区块会在下个正常 tick 重新进入 newKeys。
        knownChunks.put(dimension, ready);
        if (!candidates.isEmpty()) {
            DebugLog.info("{}: rebuild tick start - {} chunk(s) to rebuild ({} newly loaded); {} rebuilt, {} tick(s) restored so far, {} dropped",
                    dimension, candidates.size(), newKeys.size(), rebuilt, ScheduledTickManager.restoredCount(), ScheduledTickManager.droppedCount());
        }
        // 返回“与上一非冻结 tick 相比新加载的区块”，供实体顺序按区块统一重排。
        return newKeys;
    }

    // -------------------------------------------------------------- 区块 NBT 读取

    /**
     * 在 {@code SerializableChunkData.parse} 的 HEAD 处调用：读取区块 NBT 中的
     * {@code safeSave} 子节点，登记为待恢复快照。
     *
     * <p>此时区块数据已从磁盘读出但尚未解包；我们把快照暂存起来，由下一个非冻结 tick 开头的
     * {@link #rebuildNewChunks} 统一消费。
     */
    public static void onChunkTagRead(final ServerLevel level, final CompoundTag chunkData) {
        if (!enabled() || store == null) {
            return;
        }
        String dimension = dimensionId(level);
        long key = ChunkPos.pack(chunkData.getIntOr("xPos", 0), chunkData.getIntOr("zPos", 0));
        CompoundTag safeSave = chunkData.getCompound(Util.KEY_SAFE_SAVE).orElse(null);
        if (safeSave == null) {
            removePending(dimension, key);
            return;
        }
        SafeSaveStore.ChunkSnapshot snapshot = SafeSaveStore.loadChunkData(safeSave);
        Map<Long, SafeSaveStore.ChunkSnapshot> pending =
                pendingChunks.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        // 同一区块在同一会话内卸载→重载时，先冲销旧快照的计数，避免 /safesave status 重复累计。
        SafeSaveStore.ChunkSnapshot old = pending.get(key);
        if (old != null) {
            loadedTickCount.addAndGet(-(old.blockTicks().size() + old.fluidTicks().size()));
            loadedBlockEventCount.addAndGet(-old.blockEvents().size());
        }
        if (snapshot == null || snapshot.isEmpty()) {
            pending.remove(key);
            return;
        }
        pending.put(key, snapshot);
        loadedTickCount.addAndGet(snapshot.blockTicks().size() + snapshot.fluidTicks().size());
        loadedBlockEventCount.addAndGet(snapshot.blockEvents().size());
        DebugLog.info("{} {}: read {} block + {} fluid tick(s), {} block event(s) from chunk NBT",
                dimension, ChunkPos.unpack(key),
                snapshot.blockTicks().size(), snapshot.fluidTicks().size(), snapshot.blockEvents().size());
    }

    private static void removePending(final String dimension, final long key) {
        Map<Long, SafeSaveStore.ChunkSnapshot> pending = pendingChunks.get(dimension);
        if (pending == null) {
            return;
        }
        SafeSaveStore.ChunkSnapshot old = pending.remove(key);
        if (old != null) {
            loadedTickCount.addAndGet(-(old.blockTicks().size() + old.fluidTicks().size()));
            loadedBlockEventCount.addAndGet(-old.blockEvents().size());
        }
    }

    // -------------------------------------------------------------- 区块 NBT 写入

    /**
     * 在 {@code SerializableChunkData.copyOf} 的 RETURN 处调用：为即将序列化的区块计算
     * safe-save 子节点，并交给随后在后台线程运行的 {@code write()}。
     *
     * <p>load→rebuild 窗口保护：若该区块仍有待恢复快照（还没在非冻结 tick 开头重建），
     * 则把待恢复快照写回区块 NBT，而不是 vanilla 重新锚定后的临时容器内容。
     */
    public static void onChunkSerializing(final ServerLevel level, final ChunkAccess chunk,
                                          final SerializableChunkData data) {
        if (!enabled() || store == null) {
            return;
        }
        if (!(chunk instanceof LevelChunk)) {
            return;
        }
        long key = chunk.getPos().pack();
        String dimension = dimensionId(level);

        Map<Long, SafeSaveStore.ChunkSnapshot> pending = pendingChunks.get(dimension);
        // 窗口保护：待恢复快照只有在 rebuildNewChunks 消费后才会移除。这里只读取（peek），
        // 这样在 load→rebuild 窗口内被保存多少次，写回磁盘的都是原始绝对快照。
        SafeSaveStore.ChunkSnapshot snapshot = pending != null ? pending.get(key) : null;
        if (snapshot == null) {
            if (!(chunk.getBlockTicks() instanceof SafeTickContainer)
                    || !(chunk.getFluidTicks() instanceof SafeTickContainer)) {
                return;
            }
            @SuppressWarnings("unchecked")
            TickContainerAccess<Block> blockAccess =
                    (TickContainerAccess<Block>) chunk.getBlockTicks();
            @SuppressWarnings("unchecked")
            TickContainerAccess<Fluid> fluidAccess =
                    (TickContainerAccess<Fluid>) chunk.getFluidTicks();
            ScheduledTickManager.ChunkTickSnapshot ticks =
                    ScheduledTickManager.snapshotChunkTicks(level, key, blockAccess, fluidAccess);
            if (ticks == null) {
                return;
            }
            List<SafeBlockEvent> events = BlockEventManager.snapshotChunkEvents(level, key);
            if (ticks.isEmpty() && events.isEmpty()) {
                return;
            }
            snapshot = new SafeSaveStore.ChunkSnapshot(ticks.blockTicks(), ticks.fluidTicks(), events,
                    level.getGameTime());
        }

        CompoundTag tag = SafeSaveStore.saveChunkData(snapshot);
        if (!tag.isEmpty()) {
            chunkWrites.put(data, tag);
        }
    }

    /**
     * 在 {@code SerializableChunkData.write} 的 RETURN 处调用：把 safe-save 子节点挂到
     * 区块 NBT 根节点上。
     */
    public static CompoundTag injectChunkData(final SerializableChunkData data, final CompoundTag root) {
        if (!enabled() || store == null) {
            return root;
        }
        CompoundTag safeSave = chunkWrites.remove(data);
        if (safeSave == null || safeSave.isEmpty()) {
            return root;
        }
        root.put(Util.KEY_SAFE_SAVE, safeSave);
        return root;
    }

    // -------------------------------------------------------------- 保存路径

    /**
     * 写入每个维度的旁置元数据文件（{@code Level.subTickCount} + 调试字段）。
     *
     * <p>挂在 {@code MinecraftServer.saveAllChunks} 的 HEAD 而非 RETURN：当 {@code flush=true} 时，
     * 原版会在保存期间运行 {@code processUnloads} 并触发区块 NBT 写入，因此这里只写世界级元数据；
     * 区块数据由 {@code SerializableChunkDataMixin} 在随后的每个区块保存中写入。
     */
    public static void saveAll(final MinecraftServer server) {
        if (!enabled() || store == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveStore.DimensionData data = store.dimension(dimensionId(level));
            data.subTickCount = level.subTickCount;
            data.gameTime = level.getGameTime(); // 仅调试用
            Path file = dimensionDataDir(level).resolve(FILE_NAME);
            write(file, store.saveDimension(dimensionId(level), data));
        }
        store.setServerTickCount(server.getTickCount()); // 仅调试用
        DebugLog.info("saved safesave world metadata over {} dimension(s); {} chunk(s) still pending rebuild",
                server.levelKeys().size(),
                pendingChunks.values().stream().mapToInt(Map::size).sum());
    }

    /** 原子写入：先写临时文件再移动，崩溃不会留下半截文件。 */
    private static void write(final Path file, final CompoundTag tag) {
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(tag, tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            DebugLog.warn("failed to write {}: {}", file.getFileName(), e.toString());
        }
    }
}
