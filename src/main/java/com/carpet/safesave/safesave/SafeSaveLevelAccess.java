package com.carpet.safesave.safesave;

import net.minecraft.server.level.ServerLevel;

public interface SafeSaveLevelAccess {

    SafeSaveLevelState SS$safeSaveLevelState();

    static SafeSaveLevelState of(final ServerLevel level) {
        return ((SafeSaveLevelAccess) level).SS$safeSaveLevelState();
    }
}
