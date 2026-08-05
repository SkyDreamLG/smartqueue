package org.skydream.smartqueue.queue;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

public class QueueEntry {
    public final ServerPlayer serverPlayer;
    public final Connection connection;
    public final CommonListenerCookie cookie;
    public final GameProfile profile;
    public final boolean vip;
    public final boolean staff;
    public final long queuedAtTick;
    public QueueEntryState state;

    public QueueEntry(ServerPlayer serverPlayer, Connection connection, CommonListenerCookie cookie,
                      GameProfile profile, boolean vip, boolean staff, long queuedAtTick) {
        this.serverPlayer = serverPlayer;
        this.connection = connection;
        this.cookie = cookie;
        this.profile = profile;
        this.vip = vip;
        this.staff = staff;
        this.queuedAtTick = queuedAtTick;
        this.state = QueueEntryState.WAITING;
    }

    public String getName() {
        return profile.getName();
    }
}
