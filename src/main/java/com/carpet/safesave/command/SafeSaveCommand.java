package com.carpet.safesave.command;

import com.carpet.safesave.config.SafeSaveConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

public final class SafeSaveCommand {

    private static final SuggestionProvider<CommandSourceStack> NAME_SUGGESTION =
            (context, builder) -> SharedSuggestionProvider.suggest(SafeSaveConfig.NAMES, builder);

    private static final SuggestionProvider<CommandSourceStack> VALUE_SUGGESTION =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    SafeSaveConfig.values(StringArgumentType.getString(context, "name")), builder);

    private SafeSaveCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("safesave")
                // 1.21.11 turned Commands.LEVEL_* into PermissionCheck values and added
                // Commands#hasPermission; before that they were plain ints checked on the source.
                //? if <1.21.11 {
                /*.requires(source -> source.hasPermission(Commands.LEVEL_ADMINS))
                *///?} else {
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                //?}
                .then(Commands.literal("setting")
                        .executes(context -> list(context.getSource()))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(NAME_SUGGESTION)
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .suggests(VALUE_SUGGESTION)
                                        .executes(context -> set(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "value")))))));
    }

    private static int list(final CommandSourceStack source) {
        show(source, "safeSave", SafeSaveConfig.safeSave);
        show(source, "ticketDuration", SafeSaveConfig.ticketDuration);
        show(source, "unfreezeTimeout", SafeSaveConfig.unfreezeTimeout);
        show(source, "timerFromFirstPlayer", SafeSaveConfig.timerFromFirstPlayer);
        return 1;
    }

    private static int set(final CommandSourceStack source, final String name, final String value) {
        String error = SafeSaveConfig.apply(name, value);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        SafeSaveConfig.save();
        show(source, name, value);
        return 1;
    }

    private static void show(final CommandSourceStack source, final String name, final Object value) {
        source.sendSuccess(() -> Component.literal(name + " = " + value), false);
    }
}
