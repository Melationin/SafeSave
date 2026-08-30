package com.carpet.safesave.safesave;

import com.carpet.safesave.safesave.region.ProtectedRegionState;
import com.carpet.safesave.util.OrderSequence;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.BlockEventData;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个 {@code ServerLevel} 内的 safe-save 维度级状态。
 *
 * <p>通过 {@link SafeSaveLevelAccess} 挂在 {@code ServerLevelMixin} 的 {@code @Unique} 字段上，
 * 随 {@code ServerLevel} 创建而创建、随其丢弃而丢弃——单人世界切换天然隔离，无需手动 reset。
 */
public final class SafeSaveLevelState {

    /**
     * 上次在<em>非冻结</em>世界刻开始时所观察到的已就绪区块集合。
     * 每个正常 tick 都会被替换为当刻的“已解包容器”集合。
     */
    public LongSet knownChunks = new LongOpenHashSet();

    /**
     * 已从区块 NBT 读出、但尚未在非冻结 tick 开头合并入队的区块快照。
     * parse 线程写入，主线程在 tick 开头消费；保存路径只读不删（窗口保护）。
     */
    public final Map<Long, SafeSaveStore.ChunkSnapshot> pendingChunks = new ConcurrentHashMap<>();

    /** 当前维度中仍存活于世界队列的方块事件 -> 全局序号。保存时会全量刷新。 */
    public Map<BlockEventData, Long> blockEventOrders = new HashMap<>();
    /** 当前维度下一个待分配的方块事件全局序号。 */
    public long nextBlockEventOrder;

    /** 本维度最近一次活塞刻顺序重建完成时的会话代数。 */
    public long pistonOrderRebuiltAt = -1L;

    /** 旁置文件 gameTime 与实时值不一致的警告已在本维度发出过。 */
    public boolean staleWarned;

    /** 实体 tick 序号（per-Level：实体的 {@code level()} 始终可用）。 */
    public final OrderSequence entityOrder = new OrderSequence();

    /** ProtectedRegion 定义与上次保存时的启动目标标记（per-Level）。 */
    public final ProtectedRegionState protectedRegions = new ProtectedRegionState();
}
