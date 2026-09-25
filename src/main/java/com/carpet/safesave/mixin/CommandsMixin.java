package com.carpet.safesave.mixin;

import com.carpet.safesave.command.SafeSaveCommand;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandsMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void SS$registerCommands(final Commands.CommandSelection commandSelection,
                                     final CommandBuildContext context,
                                     final CallbackInfo ci) {
        SafeSaveCommand.register(((Commands) (Object) this).getDispatcher());
    }
}
