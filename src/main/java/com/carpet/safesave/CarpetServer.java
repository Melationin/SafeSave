package com.carpet.safesave;

import carpet.CarpetExtension;
import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.SafeSaveManager;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;

import java.util.Map;

public class CarpetServer implements CarpetExtension, ModInitializer {
    public static void loadExtension() {
        carpet.CarpetServer.manageExtension(new CarpetServer());
    }

    @Override
    public String version() {
        return "safesave";
    }

    @Override
    public void onInitialize() {
        loadExtension();
    }

    @Override
    public void onGameStarted() {
        carpet.CarpetServer.settingsManager.parseSettingsClass(SafeSaveRules.class);
    }

    @Override
    public void onServerLoaded(MinecraftServer server) {
        // 在 MinecraftServer.loadLevel 的 HEAD 处触发，即在 createLevels/prepareLevels 之前，
        // 因此 safe-save 数据在第一个区块解包其计划刻之前就已进入内存。
        SafeSaveManager.onServerLoaded(server);
    }

    @Override
    public void onServerClosed(MinecraftServer server) {
        SafeSaveManager.onServerClosed(server);
    }

    @Override
    public Map<String, String> canHasTranslations(String lang) {
        return Translations.getTranslationFromResourcePath(lang);
    }


}
