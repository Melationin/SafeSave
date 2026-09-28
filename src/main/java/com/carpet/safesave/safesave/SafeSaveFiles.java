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

        for (ResourceKey<Level> dimension : VANILLA_DIMENSIONS) {
            Path file = DimensionType.getStorageFolder(dimension, root).resolve("data").resolve(FILE_NAME);
            if (Files.isRegularFile(file) && loadFile(file, session)) {
                loadedFiles++;
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


    public static void saveAll(final MinecraftServer server, final SafeSaveSession session) {
        if (session == null || session.store == null || session.freezeArmed) {
            return;
        }
        session.store.setServerTickCount(server.getTickCount());
        int startupChunkTargets = 0;
        int pending = 0;
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
            SafeSaveStore.DimensionData data = session.store.dimension(dimensionId(level));
            data.subTickCount = level.subTickCount;
            data.gameTime = level.getGameTime();
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
