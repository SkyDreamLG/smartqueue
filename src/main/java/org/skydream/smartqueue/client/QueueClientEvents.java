package org.skydream.smartqueue.client;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.skydream.smartqueue.Smartqueue;
import org.slf4j.Logger;

@EventBusSubscriber(modid = Smartqueue.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class QueueClientEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private QueueClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientQueueState.checkConnectionTimeout();
        ClientQueueState.ensureScreen();
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LOGGER.debug("onClientLogout: resetting state");
        ClientQueueState.reset();
    }
}
