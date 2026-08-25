package com.carpet.safesave.safesave;

import static com.carpet.safesave.util.DimensionIds.dimensionId;

import com.carpet.safesave.debug.DebugLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * safe-save 世界级元数据（sidecar）文件读写。
 *
 * <p>每个维度一个旁置元数据文件：{@code <维度目录>/data/safesave.dat}。内容只包含
 * {@code Level.subTickCount} 与调试字段；计划刻 / 方块事件 / 活塞 / 实体序号都随区块 NBT
 * 或方块实体 / 实体 NBT 保存。
 */
public final class SafeSaveFiles {

    public static final String FILE_NAME = "safesave.dat";

    private SafeSaveFiles() {
    }

    /** 维度目录的 data/ 子目录（如 <world>/dimensions/minecraft/overworld/data）。 */
    public static Path dimensionDataDir(final ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(level.dimension(), root).resolve("data");
    }

    /**
     * 扫描并读取所有维度的旁置元数据文件，合并进会话存储。
     */
    public static void loadAll(final MinecraftServer server, final SafeSaveSession session) {
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
                            if (Files.isRegularFile(file) && loadFile(file, session)) {
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
                    loadedFiles, session.store.serverTickCount(), session.store.debugGameTimes());
        }
    }

    /**
     * 读取一个维度旁置元数据文件并合并进 {@code session.store}。
     *
     * @return {@code true} 当文件读取成功且包含维度数据
     */
    private static boolean loadFile(final Path file, final SafeSaveSession session) {
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            SafeSaveStore loaded = SafeSaveStore.load(tag);
            if (loaded.dimensions().isEmpty()) {
                DebugLog.warn("{} contains no dimension data - skipped", file.getFileName());
                return false;
            }
            // 文件内 dimension 字段即维度 id；debug 字段取第一个加载到的即可
            if (session.store.serverTickCount() < 0) {
                session.store.setServerTickCount(loaded.serverTickCount());
            }
            session.store.dimensions().putAll(loaded.dimensions());
            return true;
        } catch (Exception e) {
            DebugLog.warn("failed to read {} - skipping it: {}", file.getFileName(), e.toString());
            return false;
        }
    }

    /**
     * 写入每个维度的旁置元数据文件（{@code Level.subTickCount} + 调试字段）。
     *
     * <p>挂在 {@code MinecraftServer.saveAllChunks} 的 HEAD 而非 RETURN：当 {@code flush=true} 时，
     * 原版会在保存期间运行 {@code processUnloads} 并触发区块 NBT 写入，因此这里只写世界级元数据；
     * 区块数据由 {@code SerializableChunkDataMixin} 在随后的每个区块保存中写入。
     */
    public static void saveAll(final MinecraftServer server, final SafeSaveSession session) {
        if (session == null || session.store == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveStore.DimensionData data = session.store.dimension(dimensionId(level));
            data.subTickCount = level.subTickCount;
            data.gameTime = level.getGameTime(); // 仅调试用
            Path file = dimensionDataDir(level).resolve(FILE_NAME);
            write(file, session.store.saveDimension(dimensionId(level), data));
        }
        session.store.setServerTickCount(server.getTickCount()); // 仅调试用

        int pending = 0;
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveLevelState state = ((SafeSaveLevelAccess) level).SS$safeSaveLevelState();
            pending += state.pendingChunks.size();
        }
        DebugLog.info("saved safesave world metadata over {} dimension(s); {} chunk(s) still pending rebuild",
                server.levelKeys().size(), pending);
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
