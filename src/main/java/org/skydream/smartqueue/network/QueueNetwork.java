package org.skydream.smartqueue.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.skydream.smartqueue.Smartqueue;
import org.skydream.smartqueue.client.ClientQueueState;
import org.skydream.smartqueue.queue.QueueManager;
import org.slf4j.Logger;

@EventBusSubscriber(modid = Smartqueue.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class QueueNetwork {

    private static final Logger LOGGER = LogUtils.getLogger();

    private QueueNetwork() {}

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.configurationToClient(QueuePayloads.QueueStatusPayload.TYPE,
                QueuePayloads.QueueStatusPayload.STREAM_CODEC, QueueNetwork::handleQueueStatus);
        registrar.playToClient(QueuePayloads.QueueStatusPayload.TYPE,
                QueuePayloads.QueueStatusPayload.STREAM_CODEC, QueueNetwork::handleQueueStatus);

        registrar.configurationToServer(QueuePayloads.QueueActionPayload.TYPE,
                QueuePayloads.QueueActionPayload.STREAM_CODEC, QueueNetwork::handleQueueAction);
        registrar.playToServer(QueuePayloads.QueueActionPayload.TYPE,
                QueuePayloads.QueueActionPayload.STREAM_CODEC, QueueNetwork::handleQueueAction);
    }

    private static void handleQueueStatus(QueuePayloads.QueueStatusPayload payload, IPayloadContext context) {
        LOGGER.debug("handleQueueStatus: storing connection={}", context.connection());
        ClientQueueState.captureConnection(context.connection());
        context.enqueueWork(() -> ClientQueueState.update(payload));
    }

    private static void handleQueueAction(QueuePayloads.QueueActionPayload payload, IPayloadContext context) {
        if (payload.action() != QueuePayloads.QueueAction.LEAVE_QUEUE) return;
        context.enqueueWork(() -> {
            if (context.connection().getPacketListener() instanceof ServerConfigurationPacketListenerImpl listener) {
                LOGGER.debug("handleQueueAction: processing LEAVE_QUEUE");
                QueueManager.getInstance().leaveQueue(listener);
            }
        });
    }
}
