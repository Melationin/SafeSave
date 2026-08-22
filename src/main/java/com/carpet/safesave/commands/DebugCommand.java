package com.carpet.safesave.commands;

import com.carpet.safesave.debug.DebugSwitches;
import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.SafeSaveManager;
import com.carpet.safesave.safesave.SafeSaveStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;


public class DebugCommand {

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!DebugSwitches.DEBUG) {
            // Compile-time constant: javac removes everything below in a release build.
            return;
        }

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("safesave")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                .executes(DebugCommand::status);

        for (DebugSwitches.Channel channel : DebugSwitches.Channel.values()) {
            root.then(Commands.literal(channel.id())
                    .executes(ctx -> reportChannel(ctx, channel))
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> setChannel(ctx, channel, BoolArgumentType.getBool(ctx, "enabled")))));
        }

        root.then(Commands.literal("all")
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> setAll(ctx, BoolArgumentType.getBool(ctx, "enabled")))));

        root.then(Commands.literal("status").executes(DebugCommand::safeSaveStatus));

        dispatcher.register(root);
    }

    private static int status(final CommandContext<CommandSourceStack> ctx) {
        reply(ctx, "DEBUG=" + DebugSwitches.DEBUG);
        for (DebugSwitches.Channel channel : DebugSwitches.Channel.values()) {
            reply(ctx, "  " + channel.id() + " = " + DebugSwitches.isEnabled(channel)
                    + "  (" + channel.description() + ")");
        }
        return 1;
    }

    private static int reportChannel(final CommandContext<CommandSourceStack> ctx,
                                     final DebugSwitches.Channel channel) {
        reply(ctx, channel.id() + " = " + DebugSwitches.isEnabled(channel));
        return 1;
    }

    private static int setChannel(final CommandContext<CommandSourceStack> ctx,
                                  final DebugSwitches.Channel channel,
                                  final boolean enabled) {
        DebugSwitches.set(channel, enabled);
        reply(ctx, channel.id() + " -> " + DebugSwitches.isEnabled(channel));
        return 1;
    }

    private static int setAll(final CommandContext<CommandSourceStack> ctx, final boolean enabled) {
        DebugSwitches.setAll(enabled);
        reply(ctx, "all channels -> " + enabled);
        return 1;
    }

    private static int safeSaveStatus(final CommandContext<CommandSourceStack> ctx) {
        reply(ctx, "safeSave rule = " + SafeSaveRules.safeSave);
        reply(ctx, "scheduled ticks: read=" + SafeSaveManager.loadedTickCount()
                + " restored=" + SafeSaveManager.restoredTickCount()
                + " dropped=" + SafeSaveManager.droppedTickCount());
        reply(ctx, "block events:    read=" + SafeSaveManager.loadedBlockEventCount()
                + " restored=" + SafeSaveManager.restoredBlockEventCount()
                + " dropped=" + SafeSaveManager.droppedBlockEventCount());

        SafeSaveStore store = SafeSaveManager.store();
        if (store == null) {
            reply(ctx, "store: <not initialised>");
            return 1;
        }
        reply(ctx, "store: " + store.totalTicks() + " tick(s) awaiting restore, "
                + store.totalBlockEvents() + " block event(s) held, across "
                + store.dimensions().size() + " dimension(s)");
        reply(ctx, "debug-only fields from file: serverTickCount=" + store.serverTickCount()
                + " gameTimes=" + store.debugGameTimes());

        for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
            reply(ctx, "  " + level.dimension().identifier()
                    + ": gameTime=" + level.getGameTime()
                    + " blockTicks=" + level.getBlockTicks().count()
                    + " fluidTicks=" + level.getFluidTicks().count()
                    + " blockEventsPending=" + SafeSaveManager.pendingBlockEventCount(level)
                    + " pendingRestoreChunks=" + SafeSaveManager.pendingChunkCount(level));
        }
        return 1;
    }

    private static void reply(final CommandContext<CommandSourceStack> ctx, final String message) {
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
    }
}
