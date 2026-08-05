package org.skydream.smartqueue.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.skydream.smartqueue.Smartqueue;

@EventBusSubscriber(modid = Smartqueue.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class QueueClientEvents {

    private QueueClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientQueueState.ensureScreen();
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientQueueState.reset();
    }
}
