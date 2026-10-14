package com.carpet.safesave.safesave;

import net.minecraft.server.MinecraftServer;


public final class SafeSaveSession {

    public int serverTickCount = -1;
    // 本轮主世界推进 gameTime 前的时间，各维度恢复共用。
    public long serverGameTime = Long.MIN_VALUE;

    public boolean freezeArmed = true;//在首刻前冻结被处理之前为true
    public int firstRealPlayerTick = -1;
    public int unfreezeTick = -1;
    public boolean manualStartupUnfreeze;
    public int startupLastLogTick = -1;
    public int finalizedServerTick = Integer.MIN_VALUE;
    public boolean serverTickRunning;
    public boolean deferredSaveEverything;
    public boolean deferredSaveAllChunks;
    public boolean deferredSilent = true;
    public boolean deferredFlush;
    public boolean deferredForce;


    public SafeSaveSession() {
    }

    public static SafeSaveSession of(final MinecraftServer server) {
        return SafeSaveServerAccess.session(server);
    }
}
