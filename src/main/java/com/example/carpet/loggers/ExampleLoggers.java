package com.example.carpet.loggers;

import carpet.logging.HUDController;
import carpet.logging.HUDLogger;
import carpet.logging.LoggerRegistry;
import com.example.carpet.annotation.LoggerRegister;
import net.minecraft.network.chat.Component;

@LoggerRegister
public class ExampleLoggers {
    public static boolean __example;
    private static boolean hudRegistered = false;

    public static void register() {
        try {
            LoggerRegistry.registerLogger("example",
                    new HUDLogger(
                            ExampleLoggers.class.getField("__example"),
                            "example",
                            "brief",
                            new String[]{"brief", "full"},
                            true
                    ));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Failed to create logger 'example'", e);
        }

        // HUDController runs this listener every tick while building the tab-list HUD.
        // Only register once, since Carpet may call registerLoggers() again on server reload.
        if (!hudRegistered) {
            hudRegistered = true;
            HUDController.register(server -> {
                if (ExampleLoggers.__example) {
                    LoggerRegistry.getLogger("example").log(() -> new Component[]{
                            Component.literal("Example HUD: tick " + server.getTickCount())
                    });
                }
            });
        }
    }
}
