package com.carpet.safesave.safesave;

import com.carpet.safesave.util.OrderSequence;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.TicketType;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public final class SafeSaveSession {

    public final SafeSaveStore store = new SafeSaveStore();

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

    public final AtomicInteger restoredTickCount = new AtomicInteger();
    public final AtomicInteger droppedTickCount = new AtomicInteger();

    public final OrderSequence pistonOrder = new OrderSequence();
    public final AtomicLong pistonOrderGeneration = new AtomicLong();

    public TicketType startupLoadTicketType;

    public SafeSaveSession() {
    }

    public static SafeSaveSession of(final MinecraftServer server) {
        return SafeSaveServerAccess.session(server);
    }
}
