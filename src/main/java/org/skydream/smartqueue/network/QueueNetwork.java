package org.skydream.smartqueue.network;

import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.skydream.smartqueue.Smartqueue;
import org.skydream.smartqueue.client.ClientQueueState;
import org.skydream.smartqueue.queue.QueueManager;

@EventBusSubscriber(modid = Smartqueue.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class QueueNetwork {

    private QueueNetwork() {}

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        // Config phase: server→client queue status
        registrar.configurationToClient(
                QueuePayloads.QueueStatusPayload.TYPE,
                QueuePayloads.QueueStatusPayload.STREAM_CODEC,
                QueueNetwork::handleQueueStatus
        );
        // Play phase: server→client (for admitted notification)
        registrar.playToClient(
                QueuePayloads.QueueStatusPayload.TYPE,
                QueuePayloads.QueueStatusPayload.STREAM_CODEC,
                QueueNetwork::handleQueueStatus
        );

        // Config phase: client→server leave queue
        registrar.configurationToServer(
                QueuePayloads.QueueActionPayload.TYPE,
                QueuePayloads.QueueActionPayload.STREAM_CODEC,
                QueueNetwork::handleQueueAction
        );
        // Play phase: client→server leave queue (if needed)
        registrar.playToServer(
                QueuePayloads.QueueActionPayload.TYPE,
                QueuePayloads.QueueActionPayload.STREAM_CODEC,
                QueueNetwork::handleQueueAction
        );
    }

    private static void handleQueueStatus(QueuePayloads.QueueStatusPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientQueueState.update(payload));
    }

    private static void handleQueueAction(QueuePayloads.QueueActionPayload payload, IPayloadContext context) {
        if (payload.action() != QueuePayloads.QueueAction.LEAVE_QUEUE) return;

        context.enqueueWork(() -> {
            if (context.connection().getPacketListener() instanceof ServerConfigurationPacketListenerImpl listener) {
                QueueManager.getInstance().leaveQueue(listener);
            }
        });
    }
}
