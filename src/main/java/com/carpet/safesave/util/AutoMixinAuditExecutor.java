package com.carpet.safesave.util;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.MixinEnvironment;

public final class AutoMixinAuditExecutor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String KEYWORD_PROPERTY = "safesave.mixin_audit";

    private AutoMixinAuditExecutor() {
    }

    public static boolean isEnabled() {
        return FabricLoader.getInstance().isDevelopmentEnvironment()
                && "true".equals(System.getProperty(KEYWORD_PROPERTY));
    }

    public static void run() {
        if (!isEnabled()) {
            return;
        }

        LOGGER.info("Triggered auto mixin audit");
        boolean ok;
        try {
            MixinEnvironment.getCurrentEnvironment().audit();
            ok = true;
        } catch (Exception e) {
            LOGGER.error("Error when auditing mixin", e);
            ok = false;
        }

        LOGGER.info("Mixin audit result: " + (ok ? "successful" : "failed"));
        System.exit(ok ? 0 : 1);
    }
}
