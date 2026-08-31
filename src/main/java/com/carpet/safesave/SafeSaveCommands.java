package com.carpet.safesave;

import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.SafeSaveFiles;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveSession;
import com.carpet.safesave.safesave.region.ProtectedRegion;
import com.carpet.safesave.safesave.region.ProtectedRegionManager;
import com.carpet.safesave.safesave.region.ProtectedRegionState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;


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
                                                .then(Commands.argument("from", ColumnPosArgument.columnPos())
                                                        .suggests(SafeSaveCommands::suggestChunkPos)
                                                        .then(Commands.argument("to", ColumnPosArgument.columnPos())
                                                                .suggests(SafeSaveCommands::suggestChunkPos)
                                                                .executes(SafeSaveCommands::addRegion)))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(SafeSaveCommands::removeRegion)))
                                .then(Commands.literal("addChunk")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("pos", ColumnPosArgument.columnPos())
                                                        .suggests(SafeSaveCommands::suggestChunkPos)
                                                        .executes(SafeSaveCommands::addChunk))))
                                .then(Commands.literal("removeChunk")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("pos", ColumnPosArgument.columnPos())
                                                        .suggests(SafeSaveCommands::suggestChunkPos)
                                                        .executes(SafeSaveCommands::removeChunk))))
                                .then(Commands.literal("list")
                                        .executes(SafeSaveCommands::listRegions))
                                .then(Commands.literal("info")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(SafeSaveCommands::regionInfo)))
                        )
        );
    }

    // ------------------------------------------------------------------ 命令

    /** 区块坐标 tab 建议：执行者所在区块的绝对坐标与 {@code ~} 相对形式。 */
    private static CompletableFuture<Suggestions> suggestChunkPos(final CommandContext<CommandSourceStack> ctx,
                                                                  final SuggestionsBuilder builder) {
        Vec3 pos = ctx.getSource().getPosition();
        if (pos != null) {
            ChunkPos chunk = ChunkPos.containing(BlockPos.containing(pos));
            builder.suggest(chunk.x() + " " + chunk.z(),
                    Component.literal("chunk " + chunk.x() + ", " + chunk.z()));
        }
        builder.suggest("~ ~");
        return builder.buildFuture();
    }

    private static int addRegion(final CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ColumnPos from = ColumnPosArgument.getColumnPos(ctx, "from");
        ColumnPos to = ColumnPosArgument.getColumnPos(ctx, "to");
        ProtectedRegionState regions = regions(ctx);
        if (regions == null) {
            return 0;
        }
        int minX = Math.min(from.x(), to.x());
        int maxX = Math.max(from.x(), to.x());
        int minZ = Math.min(from.z(), to.z());
        int maxZ = Math.max(from.z(), to.z());
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
        ColumnPos pos = ColumnPosArgument.getColumnPos(ctx, "pos");
        ProtectedRegionState regions = regions(ctx);
        if (regions == null) {
            return 0;
        }
        if (!regions.addChunk(name, ChunkPos.pack(pos.x(), pos.z()))) {
            ctx.getSource().sendFailure(Component.literal(
                    "Region '" + name + "' does not exist, or it already contains chunk (" + pos.x() + ", " + pos.z() + ")."));
            return 0;
        }
        persist(ctx);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Added chunk (" + pos.x() + ", " + pos.z() + ") to ProtectedRegion '" + name + "'."), true);
        return 1;
    }

    private static int removeChunk(final CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ColumnPos pos = ColumnPosArgument.getColumnPos(ctx, "pos");
        ProtectedRegionState regions = regions(ctx);
        if (regions == null) {
            return 0;
        }
        if (!regions.removeChunk(name, ChunkPos.pack(pos.x(), pos.z()))) {
            ctx.getSource().sendFailure(Component.literal(
                    "Region '" + name + "' does not exist, or chunk (" + pos.x() + ", " + pos.z() + ") is not in it."));
            return 0;
        }
        persist(ctx);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Removed chunk (" + pos.x() + ", " + pos.z() + ") from ProtectedRegion '" + name + "'."), true);
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
