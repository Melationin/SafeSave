package com.carpet.safesave.safesave;

import com.carpet.safesave.util.OrderSequence;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public final class SafeSaveSession {

    private static volatile SafeSaveSession current;

    public SafeSaveStore store;

    public boolean freezeArmed = true;//在首刻前冻结被处理之前为true
    public boolean startupRegionBarrierActive;

    /** 首个玩家进服时的 tickCount；-1 = 未进服，超时不计时。 */
    public int startupRegionBarrierStartedAt = -1;

    public int startupRegionBarrierLastLogAt = -1;

    public final AtomicInteger loadedTickCount = new AtomicInteger();
    public final AtomicInteger loadedBlockEventCount = new AtomicInteger();

    public final AtomicInteger restoredTickCount = new AtomicInteger();
    public final AtomicInteger droppedTickCount = new AtomicInteger();
    public final AtomicInteger restoredBlockEventCount = new AtomicInteger();
    public final AtomicInteger droppedBlockEventCount = new AtomicInteger();

    public final OrderSequence pistonOrder = new OrderSequence();
    public final AtomicLong pistonOrderGeneration = new AtomicLong();

    private SafeSaveSession() {
    }

    public static SafeSaveSession current() {
        return current;
    }

    public static SafeSaveSession begin() {
        SafeSaveSession session = new SafeSaveSession();
        session.store = new SafeSaveStore();
        current = session;
        return session;
    }
}
