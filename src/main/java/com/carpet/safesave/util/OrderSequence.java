package com.carpet.safesave.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单调递增序号序列，用于恢复旧数据后保证新分配序号仍严格更大。
 */
public final class OrderSequence {
    private final AtomicLong next;

    public OrderSequence() {
        this(0L);
    }

    public OrderSequence(final long start) {
        this.next = new AtomicLong(start);
    }

    public long next() {
        return this.next.getAndIncrement();
    }

    /**
     * 用已恢复的序号提升计数器，使后续 {@link #next()} 严格大于 {@code restored}。
     * {@code Long.MIN_VALUE} 表示“未知/无序号”，直接忽略。
     */
    public void observe(final long restored) {
        if (restored != Long.MIN_VALUE) {
            this.next.accumulateAndGet(restored + 1L, Math::max);
        }
    }
}
