package com.carpet.safesave;

import com.carpet.safesave.util.AutoMixinAuditExecutor;
import net.fabricmc.api.ModInitializer;

public class SafeSaveMod implements ModInitializer {
    @Override
    public void onInitialize() {
        AutoMixinAuditExecutor.run();
    }
}
