package com.carpet.safesave.safesave;

import com.carpet.safesave.util.OrderSequence;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public final class SafeSaveSession {

    private static volatile SafeSaveSession current;

    public SafeSaveStore store;

    public boolean freezeArmed = true;//在首刻前冻结被处理之前为true
    public boolean startupRecoveryWaiting;
    public boolean startupTicketsHeld;
    public int firstRealPlayerTick = -1;
    public int unfreezeTick = -1;
    public int startupLastLogTick = -1;
    public int finalizedServerTick = Integer.MIN_VALUE;
    public boolean serverTickRunning;
    public boolean deferredSaveEverything;
    public boolean deferredSaveAllChunks;
    public boolean deferredSilent = true;
    public boolean deferredFlush;
    public boolean deferredForce;

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
