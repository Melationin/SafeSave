package com.example.carpet;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import com.example.carpet.safesave.SafeSaveManager;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.Map;

public class ExampleCarpetServer implements CarpetExtension, ModInitializer {
    public static void loadExtension() {
        CarpetServer.manageExtension(new ExampleCarpetServer());
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
        ExampleSettings.loadClasses();
        for (Class<?> ruleClass : ExampleSettings.ruleClasses) {
            CarpetServer.settingsManager.parseSettingsClass(ruleClass);
        }
    }

    @Override
    public void onServerLoaded(MinecraftServer server) {
        // Fires at MinecraftServer.loadLevel HEAD, i.e. before createLevels/prepareLevels, so the
        // safe-save data is in memory before the first chunk unpacks its scheduled ticks.
        SafeSaveManager.onServerLoaded(server);
    }

    @Override
    public void onServerClosed(MinecraftServer server) {
        SafeSaveManager.onServerClosed();
    }

    @Override
    public Map<String, String> canHasTranslations(String lang) {
        return Translations.getTranslationFromResourcePath(lang);
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
                                 CommandBuildContext commandBuildContext) {
        ExampleCommands.register(dispatcher);
    }

    @Override
    public void registerLoggers() {
        ExampleLoggers.registerLoggers();
    }
}
