package com.carpet.safesave.safesave.entity;

/**
 * 注入 {@code Entity} 的鸭子接口，暴露其 tick 序号（用于恢复实体 tick 顺序）。
 */
public interface EntityOrderHolder {

    /** 单调递增的 tick 序号；未知（本会话新生成）时为 {@link Long#MIN_VALUE}。 */
    long SS$entityOrder();

    /** 分配 tick 序号（由 {@code EntityTickList.add} 入口调用）。 */
    void SS$assignEntityOrder(long order);
}
