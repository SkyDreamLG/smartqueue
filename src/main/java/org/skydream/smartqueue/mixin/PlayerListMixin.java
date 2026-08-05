package org.skydream.smartqueue.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.skydream.smartqueue.Config;
import org.skydream.smartqueue.queue.QueueManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void smartqueue$onCanPlayerLogin(SocketAddress address, GameProfile profile,
                                              CallbackInfoReturnable<Component> cir) {
        Component result = cir.getReturnValue();
        if (result != null && Config.ENABLED.get()) {
            if (result.getContents() instanceof TranslatableContents tc
                    && "multiplayer.disconnect.server_full".equals(tc.getKey())) {
                cir.setReturnValue(null);
            }
        }
    }

    @Inject(method = "placeNewPlayer", at = @At("HEAD"), cancellable = true)
    private void smartqueue$onPlaceNewPlayer(Connection connection, ServerPlayer player,
                                              CommonListenerCookie cookie, CallbackInfo ci) {
        if (!QueueManager.isAdmitting() && QueueManager.getInstance().shouldQueue(player.getGameProfile())) {
            QueueManager.getInstance().enqueue(connection, player, cookie);
            ci.cancel();
        }
    }
}
