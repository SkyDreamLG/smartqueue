package org.skydream.smartqueue.queue;

import java.util.UUID;

public class RejoinEntry {
    public final UUID uuid;
    public final RejoinType type;
    public final boolean vip;
    public final boolean staff;
    public final int savedPosition;
    public final long leftAtTick;
    public final QueueType queueType;

    public RejoinEntry(UUID uuid, RejoinType type, boolean vip, boolean staff, int savedPosition,
                       long leftAtTick, QueueType queueType) {
        this.uuid = uuid;
        this.type = type;
        this.vip = vip;
        this.staff = staff;
        this.savedPosition = savedPosition;
        this.leftAtTick = leftAtTick;
        this.queueType = queueType;
    }
}
