package com.carpet.safesave;

import carpet.CarpetExtension;
import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.SafeSaveFiles;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.region.ProtectedRegion;
import com.carpet.safesave.safesave.region.ProtectedRegionState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

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
    public void registerCommands(final CommandDispatcher<CommandSourceStack> dispatcher,
                                 final CommandBuildContext context) {
        dispatcher.register(
                Commands.literal("safesave")
                        .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.literal("region")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("cx1", IntegerArgumentType.integer())
                                                        .then(Commands.argument("cz1", IntegerArgumentType.integer())
                                                                .then(Commands.argument("cx2", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("cz2", IntegerArgumentType.integer())
                                                                                .executes(CarpetServer::addRegion)))))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(CarpetServer::removeRegion)))
                                .then(Commands.literal("addChunk")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("cx", IntegerArgumentType.integer())
                                                        .then(Commands.argument("cz", IntegerArgumentType.integer())
                                                                .executes(CarpetServer::addChunk)))))
                                .then(Commands.literal("removeChunk")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("cx", IntegerArgumentType.integer())
                                                        .then(Commands.argument("cz", IntegerArgumentType.integer())
                                                                .executes(CarpetServer::removeChunk)))))
                                .then(Commands.literal("list")
                                        .executes(CarpetServer::listRegions))
                                .then(Commands.literal("info")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(CarpetServer::regionInfo)))
                        )
        );
    }

    // ------------------------------------------------------------------ 命令

    private static int addRegion(final CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        int cx1 = IntegerArgumentType.getInteger(ctx, "cx1");
        int cz1 = IntegerArgumentType.getInteger(ctx, "cz1");
        int cx2 = IntegerArgumentType.getInteger(ctx, "cx2");
        int cz2 = IntegerArgumentType.getInteger(ctx, "cz2");
        ProtectedRegionState regions = regions(ctx);
        if (regions == null) {
            return 0;
        }
        int minX = Math.min(cx1, cx2);
        int maxX = Math.max(cx1, cx2);
        int minZ = Math.min(cz1, cz2);
        int maxZ = Math.max(cz1, cz2);
        ProtectedRegion region = new ProtectedRegion(name);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                region.chunks.add(net.minecraft.world.level.ChunkPos.pack(x, z));
            }
        }
        if (region.chunks.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Refusing to create an empty region."));
            return 0;
        }
        regions.addRegion(region);
        persist(ctx);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Created ProtectedRegion '" + name + "' with " + region.chunks.size() + " chunk(s)."), true);
        return 1;
    }

    private static int removeRegion(final CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ProtectedRegionState regions = regions(ctx);
        if (regions == null) {
            return 0;
        }
        if (!regions.removeRegion(name)) {
            ctx.getSource().sendFailure(Component.literal("Unknown ProtectedRegion '" + name + "'."));
            return 0;
        }
        persist(ctx);
        ctx.getSource().sendSuccess(() -> Component.literal("Removed ProtectedRegion '" + name + "'."), true);
        return 1;
    }

    private static int addChunk(final CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        int cx = IntegerArgumentType.getInteger(ctx, "cx");
        int cz = IntegerArgumentType.getInteger(ctx, "cz");
        ProtectedRegionState regions = regions(ctx);
        if (regions == null) {
            return 0;
        }
        if (!regions.addChunk(name, net.minecraft.world.level.ChunkPos.pack(cx, cz))) {
            ctx.getSource().sendFailure(Component.literal(
                    "Region '" + name + "' does not exist, or it already contains chunk (" + cx + ", " + cz + ")."));
            return 0;
        }
        persist(ctx);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Added chunk (" + cx + ", " + cz + ") to ProtectedRegion '" + name + "'."), true);
        return 1;
    }

    private static int removeChunk(final CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        int cx = IntegerArgumentType.getInteger(ctx, "cx");
        int cz = IntegerArgumentType.getInteger(ctx, "cz");
        ProtectedRegionState regions = regions(ctx);
        if (regions == null) {
            return 0;
        }
        if (!regions.removeChunk(name, net.minecraft.world.level.ChunkPos.pack(cx, cz))) {
            ctx.getSource().sendFailure(Component.literal(
                    "Region '" + name + "' does not exist, or chunk (" + cx + ", " + cz + ") is not in it."));
            return 0;
        }
        persist(ctx);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Removed chunk (" + cx + ", " + cz + ") from ProtectedRegion '" + name + "'."), true);
        return 1;
    }

    private static int listRegions(final CommandContext<CommandSourceStack> ctx) {
        ProtectedRegionState regions = regions(ctx);
        if (regions == null) {
            return 0;
        }
        if (regions.byName.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No ProtectedRegions defined."), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> {
            StringBuilder sb = new StringBuilder("ProtectedRegions:");
            for (ProtectedRegion region : regions.byName.values()) {
                sb.append("\n - ").append(region.name)
                        .append(": ").append(region.chunks.size()).append(" chunk(s)")
                        .append(region.frozen ? " [FROZEN]" : " [active]");
            }
            return Component.literal(sb.toString());
        }, false);
        return regions.byName.size();
    }

    private static int regionInfo(final CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ProtectedRegionState regions = regions(ctx);
        if (regions == null) {
            return 0;
        }
        ProtectedRegion region = regions.byName.get(name);
        if (region == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown ProtectedRegion '" + name + "'."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "ProtectedRegion '" + name + "': " + region.chunks.size() + " chunk(s), "
                        + (region.frozen ? "FROZEN (frozenAt=" + region.frozenAt + ")" : "active")
                        + (SafeSaveRules.safeSaveRegions ? "" : " [rule safeSaveRegions is OFF]")), false);
        return 1;
    }

    private static ProtectedRegionState regions(final CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        SafeSaveSession session = SafeSaveSession.current();
        if (session == null || session.store == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "safe-save session is not active; enable 'safeSave' or 'safeSaveRegions' first."));
            return null;
        }
        return SafeSaveLevelAccess.of(level).protectedRegions;
    }

    private static void persist(final CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        SafeSaveSession session = SafeSaveSession.current();
        if (session != null && session.store != null) {
            SafeSaveFiles.saveAll(server, session);
        }
    }

    @Override
    public Map<String, String> canHasTranslations(String lang) {
        return Translations.getTranslationFromResourcePath(lang);
    }
}
