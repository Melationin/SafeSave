package com.carpet.safesave.util;

import java.util.concurrent.atomic.AtomicLong;


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


    public void observe(final long restored) {
        if (restored != Long.MIN_VALUE) {                         //MIN_VALUE表示“未知/无序号”，直接忽略
            this.next.accumulateAndGet(restored + 1L, Math::max);
        }
    }
}
