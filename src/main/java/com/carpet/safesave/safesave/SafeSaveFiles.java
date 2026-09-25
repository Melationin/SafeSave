package com.carpet.safesave.safesave;


import com.carpet.safesave.debug.DebugLog;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import static com.carpet.safesave.util.Util.dimensionId;

public final class SafeSaveFiles {

    public static final String FILE_NAME = "safesave.dat";

    // 路径由 DimensionType.getStorageFolder 决定，见 loadAll 的说明。
    private static final List<ResourceKey<Level>> VANILLA_DIMENSIONS =
            List.of(Level.OVERWORLD, Level.END, Level.NETHER);

    private SafeSaveFiles() {
    }

    public static Path dimensionDataDir(final ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(level.dimension(), root).resolve("data");
    }

    public static void loadAll(final MinecraftServer server, final SafeSaveSession session) {
        Path root = server.getWorldPath(LevelResource.ROOT);

        int loadedFiles = 0;

        /*
          读侧必须走与写侧同一个函数（dimensionDataDir -> DimensionType.getStorageFolder），
          否则文件写了也读不回来：该函数把原版三维度映射到 <root>（主世界）、<root>/DIM1（末地）、
          <root>/DIM-1（下界），只有自定义维度才落在 <root>/dimensions/<命名空间>/<路径> 下。
          这里曾经只扫 dimensions/，于是原版维度的 safesave.dat 永远找不到，启动屏障形同虚设。

          本方法在 onServerLoaded 里调用，此时 ServerLevel 尚未创建，所以只能用维度键而不是
          遍历 level 来还原路径。
         */
        for (ResourceKey<Level> dimension : VANILLA_DIMENSIONS) {
            Path file = DimensionType.getStorageFolder(dimension, root).resolve("data").resolve(FILE_NAME);
            if (Files.isRegularFile(file) && loadFile(file, session)) {
                loadedFiles++;
            }
        }

        Path dimensionsDir = root.resolve("dimensions");
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
            } catch (IOException | RuntimeException e) {
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

    private static boolean loadFile(final Path file, final SafeSaveSession session) {
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            SafeSaveStore loaded = SafeSaveStore.load(tag);
            if (loaded.dimensions().isEmpty()) {
                DebugLog.warn("{} contains no dimension data - skipped", file.getFileName());
                return false;
            }
            // serverTickCount 仅调试用，取第一个加载到的即可
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

    /*
     * 挂在 MinecraftServer.saveAllChunks 的 HEAD 而非 RETURN：当 flush=true 时，
     * 原版会在保存期间运行 processUnloads 并触发区块 NBT 写入，因此这里只写世界级元数据；
     * 区块数据由 SerializableChunkDataMixin 在随后的每个区块保存中写入。
     *
     * 首刻之前（session.freezeArmed 仍为 true）的保存——如
     * IntegratedServer.initServer / DedicatedServer.initServer 里的
     * saveEverything(false, true, true)——直接跳过：世界尚未开始 tick，旁置元数据没有
     * 新内容。模拟等级清单在保存时采集：关闭时原版会先卸载全部区块，
     * 因此绝不能在最终 flush 时扫描当时已空的区块表。
     */
    public static void saveAll(final MinecraftServer server, final SafeSaveSession session) {
        if (session == null || session.store == null || session.freezeArmed) {
            return;
        }
        session.store.setServerTickCount(server.getTickCount()); // 仅调试用
        int startupChunkTargets = 0;
        int pending = 0;
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
            SafeSaveStore.DimensionData data = session.store.dimension(dimensionId(level));
            data.subTickCount = level.subTickCount;
            data.gameTime = level.getGameTime(); // 仅调试用
            if (state.tickingSnapshotAvailable) {
                data.tickingChunks = new Long2ByteOpenHashMap(state.tickingChunksAtTickEnd);
            }
            startupChunkTargets += data.tickingChunks.size();
            pending += state.pendingChunks.size();
        }

        if (startupChunkTargets == 0) {
            DebugLog.info("skipped safesave world metadata write (0 ticking chunk(s) captured); "
                            + "{} chunk(s) still pending rebuild", pending);
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            String dimension = dimensionId(level);
            Path file = dimensionDataDir(level).resolve(FILE_NAME);
            write(file, session.store.saveDimension(dimension, session.store.dimension(dimension)));
        }

        DebugLog.info("saved safesave world metadata over {} dimension(s); {} chunk(s) still pending rebuild; "
                        + "{} ticking chunk(s) recorded for next startup",
                server.levelKeys().size(), pending, startupChunkTargets);
    }


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
