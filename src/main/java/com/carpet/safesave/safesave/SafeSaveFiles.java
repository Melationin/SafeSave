package com.carpet.safesave.safesave;


import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.region.ProtectedRegionCodec;
import com.carpet.safesave.safesave.region.ProtectedRegionManager;
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

import static com.carpet.safesave.util.Util.dimensionId;

public final class SafeSaveFiles {

    public static final String FILE_NAME = "safesave.dat";

    private SafeSaveFiles() {
    }

    public static Path dimensionDataDir(final ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(level.dimension(), root).resolve("data");
    }

    public static void loadAll(final MinecraftServer server, final SafeSaveSession session) {
        Path root = server.getWorldPath(LevelResource.ROOT);

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

    /**
     * <p>挂在 {@code MinecraftServer.saveAllChunks} 的 HEAD 而非 RETURN：当 {@code flush=true} 时，
     * 原版会在保存期间运行 {@code processUnloads} 并触发区块 NBT 写入，因此这里只写世界级元数据；
     * 区块数据由 {@code SerializableChunkDataMixin} 在随后的每个区块保存中写入。
     *
     * <p>首刻之前（{@code session.freezeArmed} 仍为 true）的保存——如
     * {@code IntegratedServer.initServer} / {@code DedicatedServer.initServer} 里的
     * {@code saveEverything(false, true, true)}——直接跳过：世界尚未开始 tick，旁置元数据没有
     * 新内容，且 Region 可能尚未从文件恢复，写盘只会清空 regions 或把启动目标重算为 false。
     * 服务器关闭流程（{@code server.isStopped()} 为 true）中则跳过 {@code requiredAtStartup}
     * 重算：关闭时原版先排干卸载全部区块，此刻"完整加载"判据必然失败，重算会把上次正常保存
     * 捕获的启动目标全部抹掉；关闭应保留上次正常保存时的标记。
     */
    public static void saveAll(final MinecraftServer server, final SafeSaveSession session) {
        if (session == null || session.store == null || session.freezeArmed) {
            return;
        }
        session.store.setServerTickCount(server.getTickCount()); // 仅调试用
        int startupRegionTargets = 0;
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
            startupRegionTargets += ProtectedRegionManager.captureSaveState(level, state, !server.isStopped());
            SafeSaveStore.DimensionData data = session.store.dimension(dimensionId(level));
            data.subTickCount = level.subTickCount;
            data.gameTime = level.getGameTime(); // 仅调试用
            data.regions = ProtectedRegionCodec.save(state.protectedRegions.byName);
            if (data.regions.isEmpty()) {
                data.regions = null;
            }
            Path file = dimensionDataDir(level).resolve(FILE_NAME);
            write(file, session.store.saveDimension(dimensionId(level), data));
        }

        int pending = 0;
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
            pending += state.pendingChunks.size();
        }
        DebugLog.info("saved safesave world metadata over {} dimension(s); {} chunk(s) still pending rebuild; "
                        + "{} fully loaded region(s) recorded for next startup",
                server.levelKeys().size(), pending, startupRegionTargets);
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
