package com.carpet.safesave.safesave;

import static com.carpet.safesave.util.DimensionIds.dimensionId;
import static com.carpet.safesave.util.Util.KEY_SAFE_SAVE;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.chunk.SerializableChunkDataAccess;
import com.carpet.safesave.safesave.chunk.ChunkNbtBridge;
import com.carpet.safesave.safesave.chunk.ChunkRebuildCoordinator;
import com.carpet.safesave.safesave.blockentity.PistonManager;
import com.carpet.safesave.safesave.entity.EntityOrderManager;
import com.carpet.safesave.safesave.region.ProtectedRegionCodec;
import com.carpet.safesave.safesave.region.ProtectedRegionManager;
import com.carpet.safesave.safesave.scheduled.ScheduledTickManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Set;

/**
 * SafeSave 门面：规则状态、生命周期入口与外部调用点（mixin）的委托中枢。
 *
 * <p>会话级可变状态（store、freezeArmed、各类计数、活塞序号）在 {@link SafeSaveSession}；
 * 每个 ServerLevel 的维度级状态（knownChunks、pendingChunks、方块事件顺序、实体顺序）在
 * {@link SafeSaveLevelState}，由 {@link com.carpet.safesave.safesave.SafeSaveLevelAccess} 暴露。
 * 本类所有方法保留原签名，内部通过 {@link SafeSaveSession#current()} 解析会话状态。
 *
 * <p>计划刻与方块事件随区块 NBT 的 {@code safeSave} 子节点持久化；世界级
 * {@code Level.subTickCount} 在旁置元数据文件 {@code safesave.dat} 中。
 */
public final class SafeSaveManager {

    public static final String RULE_NAME = "safeSave";

    private SafeSaveManager() {
    }

    // -----------------------------------------------------------------------
    // 规则与状态访问
    // -----------------------------------------------------------------------

    public static boolean enabled() {
        return SafeSaveRules.safeSave;
    }

    /** safeSave 或 ProtectedRegion 任一开启时，会话与旁置文件都需要工作。 */
    public static boolean shouldRun() {
        return SafeSaveRules.safeSave || SafeSaveRules.safeSaveRegions;
    }

    public static SafeSaveStore store() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? null : session.store;
    }

    public static int restoredTickCount() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0 : session.restoredTickCount.get();
    }

    public static int droppedTickCount() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0 : session.droppedTickCount.get();
    }

    public static int restoredBlockEventCount() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0 : session.restoredBlockEventCount.get();
    }

    public static int droppedBlockEventCount() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0 : session.droppedBlockEventCount.get();
    }

    public static int loadedTickCount() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0 : session.loadedTickCount.get();
    }

    public static int loadedBlockEventCount() {
        SafeSaveSession session = SafeSaveSession.current();
        return session == null ? 0 : session.loadedBlockEventCount.get();
    }

    public static int pendingChunkCount(final ServerLevel level) {
        return SafeSaveLevelAccess.of(level).pendingChunks.size();
    }

    /** 待恢复区块键集合（仅供调试命令 / 协调层使用）。 */
    public static LongSet pendingChunkKeys(final ServerLevel level) {
        return new LongOpenHashSet(SafeSaveLevelAccess.of(level).pendingChunks.keySet());
    }

    // -----------------------------------------------------------------------
    // 生命周期：服务器启动
    // -----------------------------------------------------------------------

    /**
     * 在 Carpet 的 {@code onServerLoaded}（{@code MinecraftServer.loadLevel} 的 HEAD，早于
     * {@code createLevels}/{@code prepareLevels}）处调用：绑定会话并读取旁置元数据；冻结与自动解冻
     * 由 {@link #onFirstServerTick} 在服务端刻开头处理。
     */
    public static void onServerLoaded(final MinecraftServer server) {
        if (!shouldRun()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.begin();
        // 读取所有维度的旁置元数据文件（Level.subTickCount + 调试字段）。区块级快照在
        // 区块解析（SerializableChunkData.parse）时逐个登记到各 LevelState.pendingChunks。
        SafeSaveFiles.loadAll(server, session);
        // 冻结统一在 onFirstServerTick 执行（只冻一次）。这里只绑定会话并读旁置元数据。
    }

    /**
     * 在 {@code MinecraftServer.prepareLevels} 的 HEAD 处调用：对所有已创建的世界恢复
     * {@code Level.subTickCount}。此时 {@code createLevels} 已完成，但任何区块尚未准备好刻。
     */
    public static void onLevelsCreated(final MinecraftServer server) {
        if (!shouldRun()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            SafeSaveStore.DimensionData data = session.store.dimensionOrNull(dimensionId(level));
            if (data != null) {
                if (enabled()) {
                    ScheduledTickManager.restoreSubTickCount(level, data);
                }
                if (data.regions != null && !data.regions.isEmpty()) {
                    SafeSaveLevelAccess.of(level).protectedRegions.byName.putAll(
                            ProtectedRegionCodec.load(data.regions));
                }
            }
        }
    }

    /**
     * 在 {@code MinecraftServer.tickServer} 的 HEAD 处调用。首刻按配置决定是否全局冻结；region
     * 模式随后每个服务器刻检查上次保存时选中的 Region，全部加载或超时后自动解冻。
     */
    public static void onFirstServerTick(final MinecraftServer server) {
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null) {
            return;
        }
        if (session.freezeArmed) {
            session.freezeArmed = false;
            armStartupFreeze(server, session);
        }
        updateStartupRegionBarrier(server, session);
    }

    private static void armStartupFreeze(final MinecraftServer server, final SafeSaveSession session) {
        String mode = SafeSaveRules.safeSaveUnfreeze;
        if ("region".equals(mode)) {
            if (!SafeSaveRules.safeSaveRegions) {
                DebugLog.info("safeSave unfreeze mode = region, but safeSaveRegions is off; "
                        + "the server will not be frozen on startup.");
                return;
            }
            ProtectedRegionManager.StartupStatus status = ProtectedRegionManager.startupStatus(server);
            if (status.required() == 0) {
                DebugLog.info("safeSave unfreeze mode = region; no fully loaded region was recorded at the last save, "
                        + "so startup will not be frozen.");
                return;
            }
            server.tickRateManager().setFrozen(true);
            session.startupRegionBarrierActive = true;
            session.startupRegionBarrierStartedAt = server.getTickCount();
            session.startupRegionBarrierLastLogAt = 0;
            DebugLog.info("froze the server at serverTick={}, gameTime={}; waiting for {} region(s) recorded at "
                            + "the last save ({} already loaded, missing={}), timeout={} server tick(s)",
                    server.getTickCount(), server.overworld().getGameTime(),
                    status.required(), status.loaded(), status.missingDescription(),
                    Math.max(SafeSaveRules.safeSaveRegionTimeout, 0));
            broadcastStartupFrozen(server, session);
            return;
        }
        if (!enabled()) {
            return;
        }
        if ("manual".equals(mode)) {
            server.tickRateManager().setFrozen(true);
            DebugLog.info("froze the server before its first tick. "
                    + "Run '/tick unfreeze' once you are happy with the restored state.");
        } else if ("no_freeze".equals(mode)) {
            DebugLog.info("safeSave unfreeze mode = no_freeze; the server will not be frozen on startup.");
        }
    }

    private static void updateStartupRegionBarrier(final MinecraftServer server,
                                                   final SafeSaveSession session) {
        if (!session.startupRegionBarrierActive) {
            return;
        }
        if (!server.tickRateManager().isFrozen()) {
            session.startupRegionBarrierActive = false;
            DebugLog.warn("startup region wait was cancelled because the server was manually unfrozen");
            broadcast(server, Component.translatable("safesave.message.startup_unfrozen.manual")
                    .withStyle(ChatFormatting.GREEN));
            return;
        }
        int elapsed = Math.max(server.getTickCount() - session.startupRegionBarrierStartedAt, 0);
        ProtectedRegionManager.StartupStatus status = ProtectedRegionManager.startupStatus(server);
        if (status.complete()) {
            session.startupRegionBarrierActive = false;
            server.tickRateManager().setFrozen(false);
            DebugLog.info("all {} startup region(s) are fully loaded; automatically unfroze at serverTick={}, "
                            + "gameTime={} after {} tick(s)",
                    status.required(), server.getTickCount(), server.overworld().getGameTime(), elapsed);
            broadcast(server, Component.translatable("safesave.message.startup_unfrozen.complete")
                    .withStyle(ChatFormatting.GREEN));
            return;
        }
        int timeout = Math.max(SafeSaveRules.safeSaveRegionTimeout, 0);
        if (elapsed >= timeout) {
            session.startupRegionBarrierActive = false;
            server.tickRateManager().setFrozen(false);
            DebugLog.warn("startup region wait timed out at serverTick={}, gameTime={} after {} tick(s); "
                            + "automatically unfroze with {}/{} region(s) loaded, missing={}",
                    server.getTickCount(), server.overworld().getGameTime(), elapsed,
                    status.loaded(), status.required(),
                    status.missingDescription());
            broadcast(server, Component.translatable("safesave.message.startup_unfrozen.timeout",
                            status.loaded(), status.required())
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (elapsed - session.startupRegionBarrierLastLogAt >= 20) {
            session.startupRegionBarrierLastLogAt = elapsed;
            DebugLog.info("startup region wait: {}/{} loaded after {} server tick(s), missing={}",
                    status.loaded(), status.required(), elapsed, status.missingDescription());
        }
    }

    /** 玩家进入时，若仍由 SafeSave 启动屏障冻结，则告知最长剩余等待时间。 */
    public static void onPlayerJoined(final ServerPlayer player) {
        SafeSaveSession session = SafeSaveSession.current();
        MinecraftServer server = player.level().getServer();
        if (session == null || server == null || !session.startupRegionBarrierActive
                || !server.tickRateManager().isFrozen()) {
            return;
        }
        player.sendSystemMessage(startupFrozenMessage(server, session));
    }

    private static void broadcastStartupFrozen(final MinecraftServer server,
                                               final SafeSaveSession session) {
        broadcast(server, startupFrozenMessage(server, session));
    }

    private static Component startupFrozenMessage(final MinecraftServer server,
                                                  final SafeSaveSession session) {
        int elapsed = Math.max(server.getTickCount() - session.startupRegionBarrierStartedAt, 0);
        int remainingTicks = Math.max(Math.max(SafeSaveRules.safeSaveRegionTimeout, 0) - elapsed, 0);
        int remainingSeconds = (remainingTicks + 19) / 20;
        return Component.translatable("safesave.message.startup_frozen", remainingTicks, remainingSeconds)
                .withStyle(ChatFormatting.YELLOW);
    }

    private static void broadcast(final MinecraftServer server, final Component message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(message);
        }
    }

    // -----------------------------------------------------------------------
    // 生命周期：区块 NBT 解析与序列化
    // -----------------------------------------------------------------------

    /**
     * 在 {@code SerializableChunkData.parse} 的 HEAD 处调用：读取区块 NBT 的 {@code safeSave}
     * 子节点，登记为待恢复快照。
     */
    public static void onChunkTagRead(final ServerLevel level, final CompoundTag chunkData) {
        if (!enabled()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        ChunkNbtBridge.onChunkTagRead(level, chunkData, session, SafeSaveLevelAccess.of(level));
    }

    /**
     * 在 {@code SerializableChunkData.copyOf} 的 RETURN 处调用：为即将序列化的区块计算
     * safe-save 子节点，并通过 {@link SerializableChunkDataAccess} 暂存到 record 实例上，
     * 供后台写线程在 {@code write()} 时注入 NBT。
     */
    public static void onChunkSerializing(final ServerLevel level,
                                          final ChunkAccess chunk,
                                          final Object data) {
        if (!enabled()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        CompoundTag tag = ChunkNbtBridge.onChunkSerializing(level, chunk, session, SafeSaveLevelAccess.of(level));
        ((SerializableChunkDataAccess) data).SS$setSafeSaveTag(tag);
    }

    /**
     * 在 {@code SerializableChunkData.write} 的 RETURN 处调用：把 copyOf 阶段计算好的 tag
     * 注入区块 NBT。
     *
     * @return 注入后的 NBT；无 safe-save 数据时返回原 tag
     */
    public static CompoundTag injectChunkData(final Object data,
                                              final CompoundTag root) {
        CompoundTag tag = ((SerializableChunkDataAccess) data).SS$getSafeSaveTag();
        if (tag != null) {
            root.put(KEY_SAFE_SAVE, tag);
        }
        return root;
    }

    // -----------------------------------------------------------------------
    // 生命周期：tick 与保存
    // -----------------------------------------------------------------------

    /**
     * 在 {@code ServerLevel.tick} 的 HEAD 处调用：解冻后第一个正常 tick 统一重建
     * 新加载区块的计划刻/方块事件，并协调活塞代数与实体顺序。
     */
    public static void onLevelTickStart(final ServerLevel level) {
        if (!shouldRun()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        SafeSaveLevelState levelState = SafeSaveLevelAccess.of(level);
        if (enabled()) {
            // 活塞刻顺序重建必须在冻结期间也运行：ServerLevel.tick 本身不受 tickRateManager 门控，
            // 而 PME loadAdditional 发生在区块加载时（可能早于第一个非冻结 tick）。
            PistonManager.onLevelTickStart(level, session, levelState);
        }
        if (!level.tickRateManager().runsNormally()) {
            return;
        }
        if (enabled()) {
            Set<Long> newChunks = ChunkRebuildCoordinator.rebuildNewChunks(level, session, levelState);
            EntityOrderManager.rebuildChunks(level, newChunks);
        }
    }

    /**
     * 写世界级旁置元数据。在 {@code MinecraftServer.saveAllChunks} 的 HEAD 处调用（自动保存、
     * {@code /save-all}、关闭时的最终保存），也在 Carpet 的 {@code onServerClosed}
     * （{@code stopServer} 的 HEAD）处调用：关闭后会话刻意保留（不得 clear），因为原版在
     * onServerClosed 之后还会保存一次，此时区块序列化仍要读取会话里的 store。
     */
    public static void saveAll(final MinecraftServer server) {
        if (!shouldRun()) {
            return;
        }
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            return;
        }
        SafeSaveFiles.saveAll(server, session);
    }
}
