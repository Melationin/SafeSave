package com.carpet.safesave.safesave;

import com.carpet.safesave.config.SafeSaveConfig;
import net.minecraft.server.MinecraftServer;

public interface SafeSaveServerAccess {
    SafeSaveSession SS$session();

    SafeSaveConfig SS$config();

    static SafeSaveSession session(MinecraftServer server) {
        return ((SafeSaveServerAccess) server).SS$session();
    }

    static SafeSaveConfig config(MinecraftServer server) {
        return ((SafeSaveServerAccess) server).SS$config();
    }
}
