package com.carpet.safesave.safesave;


import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.startup.LevelStartupBarrier;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
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

import static com.carpet.safesave.util.Util.dimensionId;

public final class SafeSaveFiles {

    public static final String FILE_NAME = "safesave.dat";

    private SafeSaveFiles() {
    }

    public static Path dimensionDataDir(final ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(level.dimension(), root).resolve("data");
    }

    public static boolean loadForLevel(final ServerLevel level, final SafeSaveSession session) {
        Path file = dimensionDataDir(level).resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            SafeSaveStore.ParsedFile parsed = SafeSaveStore.load(tag);
            SafeSaveStore.DimensionData data = parsed.dimensions().get(dimensionId(level));
            if (data == null) {
                DebugLog.warn("{} contains no dimension data - skipped", file.getFileName());
                return false;
            }
            SafeSaveLevelAccess.of(level).savedDimension = data;
            if (session.serverTickCount < 0) {
                session.serverTickCount = parsed.serverTickCount();
            }
            return true;
        } catch (Exception e) {
            DebugLog.warn("failed to read {} - skipping it: {}", file.getFileName(), e.toString());
            return false;
        }
    }


    public static void saveAll(final MinecraftServer server, final SafeSaveSession session,
                               final boolean captureTicking) {
        if (session == null || session.freezeArmed) {
            return;
        }
        session.serverTickCount = server.getTickCount();
        int startupChunkTargets = 0;
        int pending = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (captureTicking) {
                LevelStartupBarrier.captureTicking(level);
            }
            SafeSaveLevelState state = SafeSaveLevelAccess.of(level);
            SafeSaveStore.DimensionData data = state.savedDimension;
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
            write(file, SafeSaveStore.saveDimension(session.serverTickCount, dimension,
                    SafeSaveLevelAccess.of(level).savedDimension));
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
