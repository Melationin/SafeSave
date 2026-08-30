package com.carpet.safesave.mixin;

import com.carpet.safesave.safesave.SafeSaveManager;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 玩家完成登录后发送 SafeSave 启动冻结状态。 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void SS$onPlayerJoined(final Connection connection,
                                   final ServerPlayer player,
                                   final CommonListenerCookie cookie,
                                   final CallbackInfo ci) {
        SafeSaveManager.onPlayerJoined(player);
    }
}
