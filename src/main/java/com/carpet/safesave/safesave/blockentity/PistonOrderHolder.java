package com.carpet.safesave.safesave.blockentity;

import net.minecraft.server.level.ServerLevel;

public interface PistonOrderHolder extends BlockEntityOrderHolder {

    void SS$rebaseTime(long gameTime);

    void SS$onLevelAttached(ServerLevel level);
}
