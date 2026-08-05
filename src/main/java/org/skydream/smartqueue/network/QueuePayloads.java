package org.skydream.smartqueue.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.skydream.smartqueue.Smartqueue;

public final class QueuePayloads {

    private QueuePayloads() {}

    public record QueueStatusPayload(int position, int total, int ahead, boolean admitted, boolean paused,
                                     int etaSeconds, boolean rejected)
            implements CustomPacketPayload {

        public static final Type<QueueStatusPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Smartqueue.MODID, "queue_status"));

        public static final StreamCodec<FriendlyByteBuf, QueueStatusPayload> STREAM_CODEC =
                StreamCodec.of(
                        (buf, payload) -> {
                            buf.writeVarInt(payload.position);
                            buf.writeVarInt(payload.total);
                            buf.writeVarInt(payload.ahead);
                            buf.writeBoolean(payload.admitted);
                            buf.writeBoolean(payload.paused);
                            buf.writeVarInt(payload.etaSeconds);
                            buf.writeBoolean(payload.rejected);
                        },
                        buf -> new QueueStatusPayload(
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readBoolean(),
                                buf.readBoolean(),
                                buf.readVarInt(),
                                buf.readBoolean()
                        )
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public enum QueueAction {
        LEAVE_QUEUE
    }

    public record QueueActionPayload(QueueAction action) implements CustomPacketPayload {

        public static final Type<QueueActionPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Smartqueue.MODID, "queue_action"));

        public static final StreamCodec<FriendlyByteBuf, QueueActionPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT.map(
                                i -> QueueAction.values()[i],
                                a -> a.ordinal()
                        ),
                        QueueActionPayload::action,
                        QueueActionPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
