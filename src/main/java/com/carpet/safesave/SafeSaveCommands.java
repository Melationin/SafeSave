package com.carpet.safesave;

import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.SafeSaveFiles;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.region.ProtectedRegion;
import com.carpet.safesave.safesave.region.ProtectedRegionManager;
import com.carpet.safesave.safesave.region.ProtectedRegionState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;


public final class SafeSaveCommands {

    private SafeSaveCommands() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher,
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
                                                                                .executes(SafeSaveCommands::addRegion)))))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(SafeSaveCommands::removeRegion)))
                                .then(Commands.literal("addChunk")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("cx", IntegerArgumentType.integer())
                                                        .then(Commands.argument("cz", IntegerArgumentType.integer())
                                                                .executes(SafeSaveCommands::addChunk)))))
                                .then(Commands.literal("removeChunk")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("cx", IntegerArgumentType.integer())
                                                        .then(Commands.argument("cz", IntegerArgumentType.integer())
                                                                .executes(SafeSaveCommands::removeChunk)))))
                                .then(Commands.literal("list")
                                        .executes(SafeSaveCommands::listRegions))
                                .then(Commands.literal("info")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(SafeSaveCommands::regionInfo)))
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
                region.chunks.add(ChunkPos.pack(x, z));
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
        if (!regions.addChunk(name, ChunkPos.pack(cx, cz))) {
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
        if (!regions.removeChunk(name, ChunkPos.pack(cx, cz))) {
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
                        .append(region.requiredAtStartup ? " [startup target]" : "");
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
        boolean loaded = ProtectedRegionManager.isFullyLoaded(ctx.getSource().getLevel(), region);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "ProtectedRegion '" + name + "': " + region.chunks.size() + " chunk(s), "
                        + "fullyLoadedNow=" + loaded + ", startupTarget=" + region.requiredAtStartup
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
}
