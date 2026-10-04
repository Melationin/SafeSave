package com.carpet.safesave.config;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveServerAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SafeSaveConfig {

    public static final List<String> NAMES = List.of(
            "safeSave", "rebuildStartupOnly", "ticketDuration", "unfreezeTimeout");

    public boolean safeSave = true;
    public boolean rebuildStartupOnly = true;
    public int ticketDuration = 1200;
    public int unfreezeTimeout = 2400;

    private Path file;

    public SafeSaveConfig() {
    }

    public static SafeSaveConfig of(final MinecraftServer server) {
        return SafeSaveServerAccess.config(server);
    }

    // 供命令补全使用；非布尔项返回空表。
    public static List<String> values(final String name) {
        return switch (name) {
            case "safeSave", "rebuildStartupOnly" -> List.of("true", "false");
            default -> List.of();
        };
    }

    public void load(final MinecraftServer server) {
        this.file = server.getWorldPath(LevelResource.ROOT).resolve("safesave.conf");
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(this.file)) {
                // 注释行与空行不足两段，自然被跳过。
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length == 2) {
                    this.apply(parts[0], parts[1]);
                }
            }
        } catch (IOException | RuntimeException e) {
            DebugLog.warn("failed to read {}: {}", this.file.getFileName(), e.toString());
        }
    }

    public void save() {
        if (this.file == null) {
            return;
        }
        try {
            Files.write(this.file, List.of(
                    "safeSave " + this.safeSave,
                    "rebuildStartupOnly " + this.rebuildStartupOnly,
                    "ticketDuration " + this.ticketDuration,
                    "unfreezeTimeout " + this.unfreezeTimeout));
        } catch (IOException e) {
            DebugLog.warn("failed to write {}: {}", this.file.getFileName(), e.toString());
        }
    }

    // 返回 null 表示成功，否则是给命令反馈的说明。
    public String apply(final String name, final String value) {
        try {
            switch (name) {
                case "safeSave" -> this.safeSave = booleanValue(value);
                case "rebuildStartupOnly" -> this.rebuildStartupOnly = booleanValue(value);
                case "ticketDuration" -> this.ticketDuration = Integer.parseInt(value);
                case "unfreezeTimeout" -> this.unfreezeTimeout = Integer.parseInt(value);
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
