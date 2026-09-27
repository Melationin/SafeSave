package com.carpet.safesave.safesave.blockentity;

import net.minecraft.server.level.ServerLevel;

public interface PistonOrderHolder {

    long SS$pistonOrder();

    void SS$rebaseTime(long gameTime);

    void SS$onLevelAttached(ServerLevel level);
}
