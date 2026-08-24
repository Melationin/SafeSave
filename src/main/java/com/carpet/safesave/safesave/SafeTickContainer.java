package com.carpet.safesave.safesave;

import java.util.List;

/**
 * 注入 {@code LevelChunkTicks} 的鸭子接口，让 safe-save 能直接控制区块的刻容器。
 *
 * <p>刻意使用通配符类型：目标类是泛型（{@code LevelChunkTicks<T>}），而 JVM 描述符本来就会擦除泛型，
 * 因此保持接口非泛型可以避免泛型 mixin 的麻烦，同时运行时类型依然正确
 * （方块容器只会收到 {@code ScheduledTick<Block>} 实例）。
 */
public interface SafeTickContainer {

    /**
     * @return 当此容器仍持有未解包的 {@code pendingTicks}（即区块已从磁盘读取但从未达到
     *         {@code BLOCK_TICKING}）时为 {@code true}。此类区块没有绝对时间，
     *         safe-save 不得对其快照。
     */
    boolean SS$hasPendingTicks();

    /**
     * 清空队列、{@code (type,pos)} 去重集合以及任何 {@code pendingTicks}，然后重新调度恰好提供的
     * {@code ScheduledTick} 实例。
     *
     * <p>先清空是必须的：{@code LevelChunkTicks.schedule} 会静默丢弃 {@code (type,pos)} 已存在的刻，
     * 因此不清空的话原版重新锚定的刻会胜出，恢复将毫无效果。
     *
     * <p>通过正常的 {@code schedule} 路径重新调度，可借助 {@code onTickAdded} 回调保持父级
     * {@code LevelTicks.nextTickForContainer} 缓存的一致性。
     *
     * @param scheduledTicks 携带绝对 {@code triggerTick}/{@code subTickOrder} 的
     *                       {@code ScheduledTick} 列表
     */
    void SS$replaceAll(List<?> scheduledTicks);

    /** 当前已排队的实时 {@code ScheduledTick} 条目（绝对时间完好）。 */
    List<?> SS$snapshotQueue();
}
