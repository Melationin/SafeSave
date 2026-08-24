package com.carpet.safesave.safesave;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.rules.SafeSaveRules;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickContainerAccess;
import net.minecraft.world.ticks.TickPriority;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划刻（scheduled tick）的“安全保存”。
 *
 * <h2>原版重启时会丢失什么</h2>
 * 区块的刻以 {@code SavedTick(type, pos, int delay, priority)} 的形式存储。加载时，
 * {@code LevelChunk.unpackTicks(gameTime)} 会以 <em>该区块</em> 开始方块刻时的游戏时间重新锚定
 * {@code delay}，并 <em>按区块</em> 将 {@code subTickOrder} 重新编号为 {@code -N..-1}。由此产生的后果：
 * <ul>
 *   <li>任何未在启动时加载的区块，其绝对触发时间都会漂移 {@code T_unpack - T_save}，跨区块的相位关系被破坏；</li>
 *   <li>区块间全局的 {@code subTickOrder} 顺序被摧毁（大量并列，由哈希表迭代顺序决定）；</li>
 *   <li>{@code Level.subTickCount} 完全不被持久化；</li>
 *   <li>仅调度一个刻不会把区块标记为未保存，因此唯一变化只是计划刻的区块永远不会被重写，该刻会悄然丢失。</li>
 * </ul>
 *
 * <h2>本模组做什么</h2>
 * 为每个计划刻维护一个权威的旁置存储，记录 <strong>绝对</strong> 的 {@code triggerTick} 与
 * <strong>原始全局</strong> 的 {@code subTickOrder}，写入 {@code <world>/safesave.dat}。
 * 它独立于原版的区块 NBT，因此也绕开了 {@code markUnsaved} 丢失的问题。加载时它会覆盖原版重新锚定的结果。
 *
 * <p>当 (a) 区块卸载，或 (b) 区块加载期间发生世界保存时，存储会为该区块刷新。由于触发时间是绝对的，
 * 一个长期未加载的区块的条目始终有效——不会发生漂移。
 *
 * <p>{@code serverTickCount} 与每个世界的 {@code gameTime} 也会写入，但纯粹用于诊断：
 * 下方的恢复路径从不读取它们。
 */
public final class SafeSaveManager {

    private static final String FILE_NAME = "safesave.dat";
    /** 模组改名为 SafeSave 之前使用的文件名；仍可读取。 */
    private static final String LEGACY_FILE_NAME = "carpet-example-safesave.dat";

    /** 绝对时间存储；服务端加载前为 {@code null}。 */
    private static SafeSaveStore store;
    private static Path filePath;

    /** 在一次性“首刻前冻结”被处理之前为 {@code true}。 */
    private static boolean freezeArmed;
    /** 已执行过一次性首个世界刻恢复的维度。 */
    private static final Set<String> firstTickDone = new HashSet<>();
    /** 分配给每个新创建的 PistonMovingBlockEntity 的单调递增创建计数器（#4）。 */
    private static final java.util.concurrent.atomic.AtomicLong pistonOrder = new java.util.concurrent.atomic.AtomicLong();
    /**
     * 每当一个移动中的活塞从 NBT 加载时递增，因为其刻循环器（ticker）槽位顺序需要重建（#4）。
     * 之所以用代数计数器而不是布尔值，是因为 {@code loadAdditional} 在方块实体获得所属世界之前运行，
     * 此时维度未知——因此改为由每个世界记住自己上次重建时的代数。
     */
    private static final java.util.concurrent.atomic.AtomicLong pistonOrderGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private static final Map<String, Long> pistonOrderRebuiltAt = new HashMap<>();
    /** 供 {@code /safesave status} 使用的诊断数据。 */
    private static int loadedTickCount;
    private static int restoredTickCount;
    private static int droppedTickCount;
    private static int loadedBlockEventCount;
    private static int restoredBlockEventCount;
    private static int droppedBlockEventCount;

    private SafeSaveManager() {
    }

    public static boolean enabled() {
        return SafeSaveRules.safeSave;
    }

    // ------------------------------------------------- 移动中的活塞顺序（#4）

    /** @return 新构建的移动活塞的下一个创建序号 */
    public static long nextPistonOrder() {
        return pistonOrder.getAndIncrement();
    }

    /** 确保新创建的活塞严格排在所有从磁盘恢复的顺序值之后。 */
    public static void observePistonOrder(final long restored) {
        pistonOrder.accumulateAndGet(restored + 1L, Math::max);
    }

    public static void markPistonTickOrderDirty() {
        pistonOrderGeneration.incrementAndGet();
    }

    /**
     * 恢复 {@code Level.blockEntityTickers} 中移动活塞之间的原始相对刻顺序。
     *
     * <p>只按创建顺序升序重写当前被移动活塞占据的槽位；其余刻循环器保持原索引不变。
     * 这样可以修复活塞之间的顺序而不扰动其他任何东西，这一点很重要，因为同一刻内完成推动的两个相邻活塞
     * 各自都会运行 {@code updateFromNeighbourShapes}，从而观察到对方的结果。
     *
     * <p>在 {@code ServerLevel.tick} 的 HEAD 处调用是安全的：此时 {@code tickingBlockEntities} 为
     * {@code false}，不会有正在进行的迭代。
     */
    private static void rebuildPistonTickOrder(final ServerLevel level) {
        List<TickingBlockEntity> tickers =level.blockEntityTickers;
        List<Integer> slots = new ArrayList<>();
        List<TickingBlockEntity> pistons = new ArrayList<>();

        // 先按方块状态过滤：调色板读取廉价且无副作用，而 Level.getBlockEntity 使用
        // EntityCreationType.IMMEDIATE，会把整个世界的待创建方块实体提前实例化，比原版更早。
        // （检查方块状态而不是刻循环器注册的类型，也能避开 26.1 与 26.2 之间的
        // BlockEntityType/BlockEntityTypes 类改名问题。）
        for (int i = 0; i < tickers.size(); i++) {
            TickingBlockEntity ticker = tickers.get(i);
            if (ticker.isRemoved() || !level.getBlockState(ticker.getPos()).is(Blocks.MOVING_PISTON)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(ticker.getPos());
            if (blockEntity instanceof PistonOrderHolder holder && holder.SS$pistonOrder() != Long.MIN_VALUE) {
                slots.add(i);
                pistons.add(ticker);
            }
        }
        if (pistons.size() < 2) {
            return;
        }

        pistons.sort((a, b) -> {
            BlockEntity beA = level.getBlockEntity(a.getPos());
            BlockEntity beB = level.getBlockEntity(b.getPos());
            long orderA = beA instanceof PistonOrderHolder h ? h.SS$pistonOrder() : Long.MAX_VALUE;
            long orderB = beB instanceof PistonOrderHolder h ? h.SS$pistonOrder() : Long.MAX_VALUE;
            return Long.compare(orderA, orderB);
        });
        for (int k = 0; k < slots.size(); k++) {
            tickers.set(slots.get(k), pistons.get(k));
        }
        DebugLog.info("{}: rebuilt tick order of {} moving piston(s) by creation sequence",
                dimensionId(level), pistons.size());
    }

    public static SafeSaveStore store() {
        return store;
    }

    public static int loadedTickCount() {
        return loadedTickCount;
    }

    public static int restoredTickCount() {
        return restoredTickCount;
    }

    public static int droppedTickCount() {
        return droppedTickCount;
    }

    public static int loadedBlockEventCount() {
        return loadedBlockEventCount;
    }

    public static int restoredBlockEventCount() {
        return restoredBlockEventCount;
    }

    public static int droppedBlockEventCount() {
        return droppedBlockEventCount;
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
        filePath = server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
        freezeArmed = true;
        firstTickDone.clear();
        loadedTickCount = 0;
        restoredTickCount = 0;
        droppedTickCount = 0;
        loadedBlockEventCount = 0;
        restoredBlockEventCount = 0;
        droppedBlockEventCount = 0;
        staleWarned.clear();
        pistonOrderRebuiltAt.clear();

        if (!enabled()) {
            DebugLog.info("rule 'safeSave' is off; not reading {}", FILE_NAME);
            return;
        }

        // 优先使用当前文件名；回退到改名前的文件名，以免现有存档被悄然重置。
        // 写入始终使用 FILE_NAME，因此第一次保存即完成存档迁移。
        Path source = filePath;
        if (!Files.isRegularFile(source)) {
            Path legacy = server.getWorldPath(LevelResource.ROOT).resolve(LEGACY_FILE_NAME);
            if (Files.isRegularFile(legacy)) {
                source = legacy;
                DebugLog.info("reading legacy {}; it will be migrated to {} on the next save",
                        LEGACY_FILE_NAME, FILE_NAME);
            }
        }
        if (!Files.isRegularFile(source)) {
            DebugLog.info("no {} found; this session starts from vanilla chunk ticks", FILE_NAME);
            return;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(source, NbtAccounter.unlimitedHeap());
            store = SafeSaveStore.load(tag);
            loadedTickCount = store.totalTicks();
            loadedBlockEventCount = store.totalBlockEvents();
            DebugLog.info("loaded {} scheduled tick(s) + {} block event(s) across {} dimension(s) from {} "
                            + "(debug: serverTick={} gameTimes={})",
                    loadedTickCount, loadedBlockEventCount, store.dimensions().size(), source.getFileName(),
                    store.serverTickCount(), store.debugGameTimes());
        } catch (Exception e) {
            store = new SafeSaveStore();
            DebugLog.warn("failed to read {} - falling back to vanilla behaviour: {}", source.getFileName(), e.toString());
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
     * 但还没有任何区块被准备好用于刻。
     *
     * <p>在此绑定调试所属标签并恢复 {@code Level.subTickCount}，这是世界与存储同时可用的最早时机。
     * 在任何区块解包之前恢复计数器，可以保证新调度的刻不会与恢复的 {@code subTickOrder} 值冲突。
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
            if (data.subTickCount >= 0L) {

                long current = level.subTickCount;
                // 绝不让计数器倒退：已经发出的值必须保持唯一
                if (data.subTickCount > current) {
                    level.subTickCount = data.subTickCount;
                    DebugLog.info("{}: restored Level.subTickCount {} -> {}",
                            dimensionId(level), current, data.subTickCount);
                }
            }
            restoreBlockEvents(level, data);
        }
    }

    /**
     * 将保存的方块事件重新排入 {@code ServerLevel.blockEvents}。
     *
     * <p>这里顺序就是一切：原版的容器是 {@code ObjectLinkedOpenHashSet}，通过
     * {@code removeFirst()} 取出，因此插入顺序<em>即</em>执行顺序。此时已排队的事件（世界刚刚构建，
     * 但为保险起见仍然处理）会被重新追加到<em>恢复的事件之后</em>，因为恢复的事件严格更早。
     *
     * <p>注意 {@code runBlockEvents} 会在返回前把暂时无法执行的事件放回 {@code blockEvents}，
     * 因此 {@code blockEventsToReschedule} 不会跨刻边界保留状态，无需保存。
     */
    private static void restoreBlockEvents(final ServerLevel level, final SafeSaveStore.DimensionData data) {
        if (!data.blockEventsPendingRestore || data.blockEvents.isEmpty()) {
            return;
        }
        data.blockEventsPendingRestore = false;
        ObjectLinkedOpenHashSet<BlockEventData> queue = level.blockEvents;

        List<BlockEventData> existing = new ArrayList<>(queue);
        queue.clear();

        int restored = 0;
        for (SafeBlockEvent saved : data.blockEvents) {
            Identifier id = Identifier.tryParse(saved.blockId());
            // BLOCK 是 DefaultedRegistry：getValue() 遇到未知 id 会悄悄返回 AIR。
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                droppedBlockEventCount++;
                DebugLog.warn("dropping block event for unknown block '{}' at ({},{},{})",
                        saved.blockId(), saved.x(), saved.y(), saved.z());
                continue;
            }
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            queue.add(new BlockEventData(new BlockPos(saved.x(), saved.y(), saved.z()),
                    block, saved.paramA(), saved.paramB()));
            restored++;
        }
        // 把原本已排队的事件重新追加到恢复的（更早的）事件之后
        queue.addAll(existing);

        restoredBlockEventCount += restored;
        data.blockEvents.clear(); // 已消费
        DebugLog.info("{}: restored {} block event(s) in drain order ({} pre-existing kept behind them)",
                dimensionId(level), restored, existing.size());
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
     * 在 {@code ServerLevel.tick} 的 HEAD 处调用。执行每个维度的一次性恢复扫描。
     *
     * <p>在 {@code prepareLevels} 期间准备好的区块已由 {@code unpackTicks} 钩子处理；此扫描捕获的是
     * 已加载到 {@code FULL} 但尚未开始方块刻的区块，它们的刻仍停留在 {@code pendingTicks} 中。
     * 在这里应用绝对数据严格优于原版：刻以其真实的触发时间进入队列，只需等待区块变为可刻即可。
     */
    public static void onLevelTickStart(final ServerLevel level) {
        if (!enabled() || store == null) {
            return;
        }
        String dimension = dimensionId(level);

        // 每个刻都运行（包括冻结期间，因为 ServerLevel.tick 本身不受门控）。
        long generation = pistonOrderGeneration.get();
        if (pistonOrderRebuiltAt.getOrDefault(dimension, -1L) < generation) {
            pistonOrderRebuiltAt.put(dimension, generation);
            rebuildPistonTickOrder(level);
        }

        if (!firstTickDone.add(dimension)) {
            return;
        }
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimension);
        if (data == null || data.pendingRestore.isEmpty()) {
            return;
        }

        Long2ObjectMap<?> blockContainers = ((TickContainerHolder) level.getBlockTicks()).SS$containers();
        Long2ObjectMap<?> fluidContainers = ((TickContainerHolder) level.getFluidTicks()).SS$containers();
        int swept = 0;
        for (Long boxed : new ArrayList<>(data.pendingRestore)) {
            long key = boxed;
            Object block = blockContainers.get(key);
            Object fluid = fluidContainers.get(key);
            // 不在 allContainers 中 => 区块未加载到 FULL；稍后由 unpackTicks 钩子处理。
            if (!(block instanceof SafeTickContainer) || !(fluid instanceof SafeTickContainer)) {
                continue;
            }
            if (restoreInto(level, key, block, fluid,
                    ((SafeTickContainer) block).SS$snapshotQueue(),
                    ((SafeTickContainer) fluid).SS$snapshotQueue())) {
                swept++;
            }
        }
        DebugLog.info("{}: first world tick - swept {} already-loaded chunk(s); {} tick(s) restored so far, {} dropped",
                dimension, swept, restoredTickCount, droppedTickCount);
    }

    /** 当此世界仍有待处理（未应用）的恢复条目时为 {@code true}。 */
    public static int pendingChunkCount(final ServerLevel level) {
        if (store == null) {
            return 0;
        }
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimensionId(level));
        return data == null ? 0 : data.pendingRestore.size();
    }

    /** 世界刻日志行的调试辅助方法。 */
    public static int pendingBlockEventCount(final Level level) {
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.blockEvents.size();
        }
        return -1;
    }

    // ----------------------------------------------------------- 恢复路径

    /**
     * @return 当此区块仍有未应用的恢复条目时为 {@code true}，即其刻容器中的当前内容即将被丢弃。
     */
    public static boolean hasPendingRestore(final LevelChunk chunk) {
        if (!enabled() || store == null) {
            return false;
        }
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimensionId(level));
        return data != null && data.pendingRestore.contains(chunk.getPos().pack());
    }

    /**
     * 用保存的绝对刻替换区块的计划刻。从 {@code LevelChunk.unpackTicks} 的 TAIL 和首个世界刻扫描中调用。
     *
     * <p>存储条目会被<em>消费</em>，因此绝不会被应用两次；如果区块之后卸载，
     * {@link #snapshotChunk} 会放回一个新条目。
     *
     * @param keepBlockTicks 在 {@code unpackTicks} 运行<em>之前</em>就已排队的 {@code ScheduledTick}，
     *                       即本会话期间区块处于 {@code FULL} 时真正新调度的刻。它们会在恢复之后被重新加入，
     *                       使本功能绝不会丢失原版本会保留的刻。可为 {@code null}。
     * @return 当有内容被恢复时为 {@code true}
     */
    public static boolean restoreChunk(final LevelChunk chunk,
                                       final List<?> keepBlockTicks,
                                       final List<?> keepFluidTicks) {
        if (!enabled() || store == null) {
            return false;
        }
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        return restoreInto(level, chunk.getPos().pack(), chunk.getBlockTicks(), chunk.getFluidTicks(),
                keepBlockTicks, keepFluidTicks);
    }

    /**
     * @param blockContainer 该区块的 {@code LevelChunkTicks<Block>}
     * @param fluidContainer 该区块的 {@code LevelChunkTicks<Fluid>}
     */
    @SuppressWarnings("unchecked")
    private static boolean restoreInto(final ServerLevel level,
                                      final long packedChunkPos,
                                      final Object blockContainer,
                                      final Object fluidContainer,
                                      final List<?> keepBlockTicks,
                                      final List<?> keepFluidTicks) {
        String dimension = dimensionId(level);
        SafeSaveStore.ChunkSnapshot snapshot = store.take(dimension, packedChunkPos);
        if (snapshot == null) {
            return false;
        }
        warnIfStale(level);
        int keptBlock = applyTicks((TickContainerAccess<Block>) blockContainer, snapshot.blockTicks(),
                BuiltInRegistries.BLOCK, keepBlockTicks);
        int keptFluid = applyTicks((TickContainerAccess<Fluid>) fluidContainer, snapshot.fluidTicks(),
                BuiltInRegistries.FLUID, keepFluidTicks);
        DebugLog.info("{} {}: restored {} block + {} fluid tick(s) with absolute timing (kept {} pre-existing)",
                dimension, ChunkPos.unpack(packedChunkPos),
                snapshot.blockTicks().size(), snapshot.fluidTicks().size(), keptBlock + keptFluid);
        return true;
    }

    /** 已警告过的维度，确保消息每个会话只出现一次。 */
    private static final Set<String> staleWarned = new HashSet<>();

    /**
     * 纯诊断用途。记录的 {@code gameTime} <strong>从不</strong>用于重新锚定任何东西——
     * 触发时间按构造就是绝对的。但如果它与实时的 {@code gameTime} 不一致，说明旁置文件与
     * {@code level.dat} 脱节（典型情况：某个会话关闭了规则，世界继续推进而此文件没有），
     * 那么每个恢复的刻都会相应地过期。这一点值得明确指出。
     */
    private static void warnIfStale(final ServerLevel level) {
        String dimension = dimensionId(level);
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimension);
        if (data == null || data.gameTime == Long.MIN_VALUE) {
            return;
        }
        long live = level.getGameTime();
        if (data.gameTime != live && staleWarned.add(dimension)) {
            DebugLog.warn("{}: side file was written at gameTime={} but the world resumed at gameTime={} "
                            + "(difference {}). Restored ticks keep their absolute trigger times and will therefore "
                            + "fire immediately. This usually means 'safeSave' was off for a previous session.",
                    dimension, data.gameTime, live, live - data.gameTime);
        }
    }

    /**
     * @param keep 清空后需要重新加入的既有刻；可为 {@code null}
     * @return 重新加入的既有刻数量
     */
    @SuppressWarnings("unchecked")
    private static <T> int applyTicks(final TickContainerAccess<T> container,
                                      final List<SafeTick> saved,
                                      final Registry<T> registry,
                                      final List<?> keep) {
        List<ScheduledTick<T>> ticks = new ArrayList<>(saved.size());
        for (SafeTick entry : saved) {
            Identifier id = Identifier.tryParse(entry.typeId());
            // BLOCK/FLUID 是 DefaultedRegistry：getValue() 遇到未知 id 会悄悄返回 AIR/EMPTY，
            // 因此必须显式检查注册表成员资格。
            if (id == null || !registry.containsKey(id)) {
                droppedTickCount++;
                DebugLog.warn("dropping scheduled tick for unknown type '{}' at ({},{},{})",
                        entry.typeId(), entry.x(), entry.y(), entry.z());
                continue;
            }
            T type = registry.getValue(id);
            ticks.add(new ScheduledTick<>(
                    type,
                    new BlockPos(entry.x(), entry.y(), entry.z()),
                    entry.triggerTick(),
                    TickPriority.byValue(entry.priority()),
                    entry.subTickOrder()));
        }
        ((SafeTickContainer) container).SS$replaceAll(ticks);
        restoredTickCount += ticks.size();

        int kept = 0;
        if (keep != null) {
            for (Object raw : keep) {
                if (raw instanceof ScheduledTick<?> tick) {
                    // schedule() 会按 (type, pos) 去重，因此恢复已覆盖的既有刻会在这里被丢弃而非重复。
                    container.schedule((ScheduledTick<T>) tick);
                    kept++;
                }
            }
        }
        return kept;
    }

    // -------------------------------------------------------------- 保存路径

    /**
     * 将区块的刻捕获到存储中。从 {@code ServerLevel.unload} 的 HEAD 处调用，
     * 即刻容器刚从世界中注销之前。
     */
    public static void snapshotChunk(final ServerLevel level, final LevelChunk chunk) {
        if (!enabled() || store == null) {
            return;
        }
        // 保护这个强转：ChunkAccess.getBlockTicks() 只在真正的 LevelChunk 上才是 LevelChunkTicks。
        // ImposterProtoChunk 在写入被禁用时会返回 BlackholeTickAccess.emptyContainer()，
        // 它没有实现 SafeTickContainer，盲目强转会抛出 ClassCastException。
        // snapshotLevel() 已经这样防护；这条路径之前没有。
        if (!(chunk.getBlockTicks() instanceof SafeTickContainer)
                || !(chunk.getFluidTicks() instanceof SafeTickContainer)) {
            return;
        }
        snapshot(level, chunk.getPos().pack(), chunk.getBlockTicks(), chunk.getFluidTicks());
    }

    private static void snapshot(final ServerLevel level,
                                 final long packedChunkPos,
                                 final TickContainerAccess<Block> blockTicks,
                                 final TickContainerAccess<Fluid> fluidTicks) {
        SafeTickContainer blockContainer = (SafeTickContainer) blockTicks;
        SafeTickContainer fluidContainer = (SafeTickContainer) fluidTicks;

        // 仍持有 pendingTicks 的容器从未被解包，因此没有可捕获的绝对时间。
        // 存储中已有的该区块条目原样保留：它来自该区块*确实*在刻的会话，而绝对时间永不漂移。
        if (blockContainer.SS$hasPendingTicks() || fluidContainer.SS$hasPendingTicks()) {
            return;
        }

        // 仍在恢复队列中 => 容器当前持有的是原版重新锚定的刻，正是我们打算丢弃的数据。
        // 用它们覆盖条目会悄悄让整个功能失效。这在实践中很重要：
        // MC 在启动后不久就会执行一次 flush 保存，可能赶在恢复之前。
        SafeSaveStore.DimensionData data = store.dimensionOrNull(dimensionId(level));
        if (data != null && data.pendingRestore.contains(packedChunkPos)) {
            return;
        }

        List<SafeTick> block = toSafeTicks(blockContainer.SS$snapshotQueue());
        List<SafeTick> fluid = toSafeTicks(fluidContainer.SS$snapshotQueue());
        store.put(dimensionId(level), packedChunkPos, new SafeSaveStore.ChunkSnapshot(block, fluid));
    }

    private static List<SafeTick> toSafeTicks(final List<?> scheduledTicks) {
        List<SafeTick> out = new ArrayList<>(scheduledTicks.size());
        for (Object raw : scheduledTicks) {
            if (!(raw instanceof ScheduledTick<?> tick)) {
                continue;
            }
            out.add(new SafeTick(
                    DebugLog.typeId(tick.type()),
                    tick.pos().getX(),
                    tick.pos().getY(),
                    tick.pos().getZ(),
                    tick.triggerTick(),
                    tick.priority().getValue(),
                    tick.subTickOrder()));
        }
        // 按取出顺序排序，纯粹为了让文件便于检查；恢复使用存储的字段。
        out.sort((a, b) -> {
            int cmp = Long.compare(a.triggerTick(), b.triggerTick());
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(a.priority(), b.priority());
            return cmp != 0 ? cmp : Long.compare(a.subTickOrder(), b.subTickOrder());
        });
        return out;
    }

    /**
     * 快照每个维度的每个已加载区块并写入旁置文件。
     *
     * <p>挂在 {@code MinecraftServer.saveAllChunks} 的 HEAD 而非 RETURN：当 {@code flush=true} 时，
     * 原版会在保存期间运行 {@code processUnloads}，注销刻容器，因此到 RETURN 时
     * 世界的一部分已经从 {@code LevelTicks.allContainers} 中消失。
     */
    public static void saveAll(final MinecraftServer server) {
        if (!enabled() || store == null || filePath == null) {
            return;
        }
        int chunks = 0;
        for (ServerLevel level : server.getAllLevels()) {
            chunks += snapshotLevel(level);
            SafeSaveStore.DimensionData data = store.dimension(dimensionId(level));
            data.subTickCount = level.subTickCount;
            data.gameTime = level.getGameTime(); // 仅调试用
            snapshotBlockEvents(level, data);
        }
        store.setServerTickCount(server.getTickCount()); // 仅调试用
        write();
        DebugLog.info("saved {} scheduled tick(s) over {} loaded chunk(s) + {} block event(s) to {}",
                store.totalTicks(), chunks, store.totalBlockEvents(), FILE_NAME);
    }

    /**
     * 原样捕获世界级的方块事件队列，保持取出顺序。
     *
     * <p>与计划刻不同，这里不需要按区块记账：队列位于 {@code ServerLevel} 上，完全在内存中，
     * 每次保存时直接覆盖即可。
     */
    private static void snapshotBlockEvents(final ServerLevel level, final SafeSaveStore.DimensionData data) {
        data.blockEvents.clear();
        for (BlockEventData event : level.blockEvents) {
            data.blockEvents.add(new SafeBlockEvent(
                    BuiltInRegistries.BLOCK.getKey(event.block()).toString(),
                    event.pos().getX(),
                    event.pos().getY(),
                    event.pos().getZ(),
                    event.paramA(),
                    event.paramB()));
        }
    }

    private static int snapshotLevel(final ServerLevel level) {
        Long2ObjectMap<?> blockContainers = ((TickContainerHolder) level.getBlockTicks()).SS$containers();
        Long2ObjectMap<?> fluidContainers = ((TickContainerHolder) level.getFluidTicks()).SS$containers();

        Set<Long> keys = new HashSet<>();
        LongIterator blockKeys = blockContainers.keySet().iterator();
        while (blockKeys.hasNext()) {
            keys.add(blockKeys.nextLong());
        }
        LongIterator fluidKeys = fluidContainers.keySet().iterator();
        while (fluidKeys.hasNext()) {
            keys.add(fluidKeys.nextLong());
        }

        int count = 0;
        for (Long boxed : keys) {
            long key = boxed;
            Object block = blockContainers.get(key);
            Object fluid = fluidContainers.get(key);
            if (!(block instanceof SafeTickContainer) || !(fluid instanceof SafeTickContainer)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            TickContainerAccess<Block> blockAccess = (TickContainerAccess<Block>) block;
            @SuppressWarnings("unchecked")
            TickContainerAccess<Fluid> fluidAccess = (TickContainerAccess<Fluid>) fluid;
            snapshot(level, key, blockAccess, fluidAccess);
            count++;
        }
        return count;
    }

    private static void write() {
        Path tmp = filePath.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(filePath.getParent());
            NbtIo.writeCompressed(store.save(), tmp);
            try {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            DebugLog.warn("failed to write {}: {}", FILE_NAME, e.toString());
        }
    }
}
