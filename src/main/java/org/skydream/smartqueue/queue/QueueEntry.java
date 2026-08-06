package org.skydream.smartqueue.queue;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

public class QueueEntry {
    public ServerPlayer serverPlayer;
    public Connection connection;
    public CommonListenerCookie cookie;
    public final GameProfile profile;
    public final boolean vip, staff;
    public final long queuedAtTick;
    public final long joinOrder;
    public QueueEntryState state;
    public QueueType queueType;
    public long disconnectedAtTick;

    public QueueEntry(ServerPlayer serverPlayer, Connection connection, CommonListenerCookie cookie,
                      GameProfile profile, boolean vip, boolean staff, long queuedAtTick,
                      QueueType queueType, long joinOrder) {
        this.serverPlayer = serverPlayer;
        this.connection = connection;
        this.cookie = cookie;
        this.profile = profile;
        this.vip = vip;
        this.staff = staff;
        this.queuedAtTick = queuedAtTick;
        this.state = QueueEntryState.WAITING;
        this.queueType = queueType;
        this.joinOrder = joinOrder;
    }

    public String getName() { return profile.getName(); }
}
