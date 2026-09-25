package com.carpet.safesave.config;

import com.carpet.safesave.debug.DebugLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SafeSaveConfig {

    public static final List<String> NAMES = List.of(
            "safeSave", "ticketDuration", "unfreezeTimeout", "timerFromFirstPlayer");

    public static boolean safeSave = true;
    public static int ticketDuration = 1200;
    public static int unfreezeTimeout = 2400;
    public static boolean timerFromFirstPlayer = true;

    private static Path file;

    private SafeSaveConfig() {
    }

    // 供命令补全使用；非布尔项返回空表。
    public static List<String> values(final String name) {
        return switch (name) {
            case "safeSave", "timerFromFirstPlayer" -> List.of("true", "false");
            default -> List.of();
        };
    }

    public static void load(final MinecraftServer server) {
        file = server.getWorldPath(LevelResource.ROOT).resolve("safesave.conf");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                // 注释行与空行不足两段，自然被跳过。
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length == 2) {
                    apply(parts[0], parts[1]);
                }
            }
        } catch (IOException | RuntimeException e) {
            DebugLog.warn("failed to read {}: {}", file.getFileName(), e.toString());
        }
    }

    public static void save() {
        if (file == null) {
            return;
        }
        try {
            Files.write(file, List.of(
                    "safeSave " + safeSave,
                    "ticketDuration " + ticketDuration,
                    "unfreezeTimeout " + unfreezeTimeout,
                    "timerFromFirstPlayer " + timerFromFirstPlayer));
        } catch (IOException e) {
            DebugLog.warn("failed to write {}: {}", file.getFileName(), e.toString());
        }
    }

    // 返回 null 表示成功，否则是给命令反馈的说明。
    public static String apply(final String name, final String value) {
        try {
            switch (name) {
                case "safeSave" -> safeSave = booleanValue(value);
                case "ticketDuration" -> ticketDuration = Integer.parseInt(value);
                case "unfreezeTimeout" -> unfreezeTimeout = Integer.parseInt(value);
                case "timerFromFirstPlayer" -> timerFromFirstPlayer = booleanValue(value);
                default -> {
                    return "unknown setting: " + name;
                }
            }
        } catch (IllegalArgumentException e) {
            return "invalid value: " + name + " = " + value;
        }
        return null;
    }

    private static boolean booleanValue(final String value) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(value);
    }
}
