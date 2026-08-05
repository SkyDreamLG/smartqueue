package org.skydream.smartqueue.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.skydream.smartqueue.queue.QueueManager;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public class ServerConfigDisconnectMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void smartqueue$onDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        LOGGER.debug("ServerConfigDisconnectMixin.onDisconnect: reason={}", details.reason());
        QueueManager.getInstance().onConnectionDisconnect((ServerConfigurationPacketListenerImpl) (Object) this);
    }
}
