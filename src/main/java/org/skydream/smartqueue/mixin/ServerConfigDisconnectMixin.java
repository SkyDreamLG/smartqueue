package org.skydream.smartqueue.mixin;

import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.skydream.smartqueue.queue.QueueManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public class ServerConfigDisconnectMixin {

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void smartqueue$onDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        QueueManager.getInstance().onConnectionDisconnect((ServerConfigurationPacketListenerImpl) (Object) this);
    }
}
