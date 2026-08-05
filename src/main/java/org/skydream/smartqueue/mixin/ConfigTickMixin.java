package org.skydream.smartqueue.mixin;

import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerCommonPacketListenerImpl.class)
public interface ConfigTickMixin {
    @Accessor("keepAlivePending")
    void smartqueue$setKeepAlivePending(boolean v);

    @Accessor("keepAliveTime")
    void smartqueue$setKeepAliveTime(long v);

    @Accessor("closedListenerTime")
    void smartqueue$setClosedListenerTime(long v);

    @Accessor("connection")
    net.minecraft.network.Connection smartqueue$getConnection();
}
