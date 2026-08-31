package com.carpet.safesave;

import carpet.CarpetExtension;
import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.SafeSaveManager;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
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
        // MinecraftServer.loadLevel 的 HEAD：在 createLevels/prepareLevels 之前，
        // 让 safe-save 数据在第一个区块解包其计划刻之前进入内存。
        SafeSaveManager.onServerLoaded(server);
    }

    @Override
    public void onServerClosed(MinecraftServer server) {
        // 在 stopServer 的 HEAD 处触发，与原版 saveAllChunks HEAD 一样写世界级旁置元数据；
        // 关闭后会话刻意保留（不得 clear），因为原版之后还会保存一次（见 SafeSaveManager.saveAll）。
        SafeSaveManager.saveAll(server);
    }

    @Override
    public void registerCommands(final CommandDispatcher<CommandSourceStack> dispatcher,
                                 final CommandBuildContext context) {
        SafeSaveCommands.register(dispatcher, context);
    }

    @Override
    public Map<String, String> canHasTranslations(String lang) {
        return Translations.getTranslationFromResourcePath(lang);
    }
}
