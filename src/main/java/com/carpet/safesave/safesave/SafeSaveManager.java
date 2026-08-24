package com.carpet.safesave.safesave;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.blockevent.BlockEventManager;
import com.carpet.safesave.safesave.blockentity.PistonManager;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import com.carpet.safesave.rules.SafeSaveRules;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * SafeSave 的协调层：生命周期钩子、按维度的文件读写，以及三个子管理器的编排。
 *
 * <p>职责分工：
 * <ul>
 *   <li>{@link ScheduledTickManager} —— 计划刻的快照/恢复；</li>
 *   <li>{@link BlockEventManager} —— 方块事件的快照/恢复；</li>
 *   <li>{@link PistonManager} —— 移动活塞（方块实体）的创建顺序恢复。</li>
 * </ul>
 *
 * <p>本类负责：<em>绝对</em> {@code triggerTick} 与<em>原始全局</em> {@code subTickOrder} 的权威存储
 * （{@link SafeSaveStore}，每个维度一个文件 <维度目录>/data/safesave.dat），服务端生命周期钩子
 * （加载/关闭/首刻冻结/保存），以及诊断计数。
 */
public final class SafeSaveManager {

    private static final String FILE_NAME = "safesave.dat";

    /** 绝对时间存储；服务端加载前为 {@code null}。 */
    private static SafeSaveStore store;

    /** 在一次性“首刻前冻结”被处理之前为 {@code true}。 */
    private static boolean freezeArmed;
    /** 供 {@code /safesave status} 使用的诊断数据（从磁盘加载的计数）。 */
    private static int loadedTickCount;
    private static int loadedBlockEventCount;

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
        return ScheduledTickManager.restoredCount();
    }

    public static int droppedTickCount() {
        return ScheduledTickManager.droppedCount();
    }

    public static int loadedBlockEventCount() {
        return loadedBlockEventCount;
    }

    public static int restoredBlockEventCount() {
        return BlockEventManager.restoredCount();
    }

    public static int droppedBlockEventCount() {
        return BlockEventManager.droppedCount();
    }

    /** 当此世界仍有待处理（未应用）的恢复条目时为 {@code true}。 */
    public static int pendingChunkCount(final ServerLevel level) {
        return ScheduledTickManager.pendingChunkCount(level);
    }

    /** 世界刻日志行的调试辅助方法。 */
    public static int pendingBlockEventCount(final Level level) {
        return BlockEventManager.pendingCount(level);
    }

    private static String dimensionId(final ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    // ------------------------------------------------------------ 服务端钩子

    /**
     * 由 Carpet 的 {@code onServerLoaded} 调用，它在 {@code MinecraftServer.loadLevel} 的 HEAD 处触发——
     * 即在 {@code createLevels}/{@code prepareLevels} 之前。这一点很重要：存储必须在第一个区块解包其刻
     * 之前就完成填充。
     */
    public static void onServerLoaded(final MinecraftServer server) {
        store = new SafeSaveStore();
        ScheduledTickManager.init(store);
        BlockEventManager.init(store);
        PistonManager.reset();
        freezeArmed = true;
        loadedTickCount = 0;
        loadedBlockEventCount = 0;

        if (!enabled()) {
            DebugLog.info("rule 'safeSave' is off; not reading {}", FILE_NAME);
            return;
        }

        Path root = server.getWorldPath(LevelResource.ROOT);

        // 每个维度一个存档文件，位于 <维度目录>/data/safesave.dat。
        // 维度目录结构：<world>/dimensions/<namespace>/<path>/，扫描两层。
        Path dimensionsDir = root.resolve("dimensions");
        if (Files.isDirectory(dimensionsDir)) {
            try (Stream<Path> namespaces = Files.list(dimensionsDir)) {
                for (Path nsDir : namespaces.filter(Files::isDirectory).toList()) {
                    try (Stream<Path> dimensionDirs = Files.list(nsDir)) {
                        for (Path dimDir : dimensionDirs.filter(Files::isDirectory).toList()) {
                            Path file = dimDir.resolve("data").resolve(FILE_NAME);
                            if (Files.isRegularFile(file)) {
                                loadFile(file);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                DebugLog.warn("failed to scan {}: {}", dimensionsDir, e.toString());
            }
        }

        if (store.dimensions().isEmpty()) {
            DebugLog.info("no {} found; this session starts from vanilla chunk ticks", FILE_NAME);
            return;
        }
        loadedTickCount = store.totalTicks();
        loadedBlockEventCount = store.totalBlockEvents();
        DebugLog.info("loaded {} scheduled tick(s) + {} block event(s) across {} dimension(s) "
                        + "(debug: serverTick={} gameTimes={})",
                loadedTickCount, loadedBlockEventCount, store.dimensions().size(),
                store.serverTickCount(), store.debugGameTimes());
    }

    /** 维度目录的 data/ 子目录（如 <world>/dimensions/minecraft/overworld/data）。 */
    private static Path dimensionDataDir(final ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(level.dimension(), root).resolve("data");
    }

    /**
     * 读取一个维度存档文件并合并进 {@link #store}。
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
     * {@code stopServer} 更下方还有 {@code saveAllChunks(false, true, false)}。在这里清空状态
     * 会悄悄跳过整个功能最重要的一次保存。所有会话级状态改由 {@link #onServerLoaded} 重新初始化，
     * 因此不会有任何残留泄漏到后续的（单人）世界。
     */
    public static void onServerClosed() {
        if (enabled() && store != null) {
            DebugLog.info("server closing; the shutdown save later in stopServer will flush {} ({} tick(s) currently held)",
                    FILE_NAME, store.totalTicks());
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
            BlockEventManager.restore(level, data);
        }
    }

    /**
     * 在 {@code MinecraftServer.tickServer} 的 HEAD 处调用一次。
     *
     * <p>冻结服务端，使一切不再推进，直到操作员确认了恢复的状态。冻结期间
     * {@code TickRateManager.runsNormally()} 为 {@code false}，因此 {@code ServerLevel.tick}
     * 会完全跳过 {@code blockTicks}/{@code fluidTicks} 阶段，{@code gameTime} 也不移动——
     * 恢复的刻原封不动地等待。
     */
    public static void onFirstServerTick(final MinecraftServer server) {
        if (!freezeArmed) {
            return;
        }
        freezeArmed = false;
        // 仅在确实存在恢复数据时才冻结。注意这里必须检查从磁盘读到的计数，而不是 store.isEmpty()：
        // 启动时的 flush 保存会为每个世界创建一个（空的）DimensionData，会让本来为空的存储看起来非空，
        // 从而冻结一个全新世界。
        if (!enabled() || (loadedTickCount <= 0 && loadedBlockEventCount <= 0)) {
            return;
        }
        server.tickRateManager().setFrozen(true);
        DebugLog.info("froze the server before its first tick ({} scheduled tick(s) + {} block event(s) restored). "
                        + "Run '/tick unfreeze' once you are happy with the restored state.",
                loadedTickCount, loadedBlockEventCount);
    }

    /**
     * 在 {@code ServerLevel.tick} 的 HEAD 处调用。编排活塞顺序重建与计划刻的首刻恢复扫描。
     */
    public static void onLevelTickStart(final ServerLevel level) {
        if (!enabled() || store == null) {
            return;
        }
        PistonManager.onLevelTickStart(level);
        ScheduledTickManager.onLevelTickStart(level);
    }

    // -------------------------------------------------------------- 保存路径

    /**
     * 快照每个维度的每个已加载区块并写入旁置文件。
     *
     * <p>挂在 {@code MinecraftServer.saveAllChunks} 的 HEAD 而非 RETURN：当 {@code flush=true} 时，
     * 原版会在保存期间运行 {@code processUnloads}，注销刻容器，因此到 RETURN 时
     * 世界的一部分已经从 {@code LevelTicks.allContainers} 中消失。
     */
    public static void saveAll(final MinecraftServer server) {
        if (!enabled() || store == null) {
            return;
        }
        int chunks = 0;
        for (ServerLevel level : server.getAllLevels()) {
            chunks += ScheduledTickManager.snapshotLevel(level);
            SafeSaveStore.DimensionData data = store.dimension(dimensionId(level));
            data.subTickCount = level.subTickCount;
            data.gameTime = level.getGameTime(); // 仅调试用
            BlockEventManager.snapshot(level, data);
            Path file = dimensionDataDir(level).resolve(FILE_NAME);
            if (data.totalTicks() == 0 && data.blockEvents.isEmpty()) {
                // 维度变空：删除旧文件，否则下次启动会读到已执行过的旧刻并重复恢复
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    DebugLog.warn("failed to delete stale {}: {}", file.getFileName(), e.toString());
                }
                continue;
            }
            write(file, store.saveDimension(dimensionId(level), data));
        }
        store.setServerTickCount(server.getTickCount()); // 仅调试用
        DebugLog.info("saved {} scheduled tick(s) over {} loaded chunk(s) + {} block event(s) to per-dimension data/ files",
                store.totalTicks(), chunks, store.totalBlockEvents());
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
