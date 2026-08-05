package org.skydream.smartqueue.mixin;

import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.skydream.smartqueue.queue.QueueManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public class ConfigTickHeadMixin {

    private boolean smartqueue$timeoutRemoved = false;

    @Inject(method = "tick", at = @At("HEAD"))
    private void smartqueue$hijackTick(CallbackInfo ci) {
        var accessor = (ConfigTickMixin) this;
        if (!QueueManager.getInstance().isQueuedConnection(accessor.smartqueue$getConnection())) {
            return;
        }

        long now = System.currentTimeMillis();

        // Reset vanilla timeout timers
        accessor.smartqueue$setKeepAlivePending(false);
        accessor.smartqueue$setKeepAliveTime(now);
        accessor.smartqueue$setClosedListenerTime(now);

        // Remove Netty ReadTimeoutHandler once (it's the root cause of 30s timeout)
        if (!smartqueue$timeoutRemoved) {
            var conn = accessor.smartqueue$getConnection();
            var channel = ((ConnectionAccessor) (Object) conn).smartqueue$getChannel();
            if (channel != null && channel.pipeline().get("timeout") != null) {
                channel.pipeline().remove("timeout");
                smartqueue$timeoutRemoved = true;
            }
        }
    }
}
