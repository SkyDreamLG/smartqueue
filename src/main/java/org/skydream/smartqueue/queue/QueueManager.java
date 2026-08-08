package org.skydream.smartqueue.queue;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.skydream.smartqueue.Config;
import org.skydream.smartqueue.Smartqueue;
import org.skydream.smartqueue.mixin.ConnectionAccessor;
import org.skydream.smartqueue.network.QueuePayloads;

import java.util.*;

@EventBusSubscriber(modid = Smartqueue.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class QueueManager {

    private static QueueManager instance;
    private final List<QueueEntry> staffQueue = new ArrayList<>();
    private final List<QueueEntry> priorityQueue = new ArrayList<>();
    private final List<QueueEntry> vipQueue = new ArrayList<>();
    private final List<QueueEntry> normalQueue = new ArrayList<>();
    private final Map<UUID, QueueEntry> byUuid = new HashMap<>();
    private final Map<UUID, RejoinEntry> rejoinMap = new HashMap<>();
    private final Map<UUID, List<Long>> rejoinHistory = new HashMap<>();
    private final Map<Connection, QueueEntry> byConnection = new HashMap<>();
    private final Map<Connection, Integer> rejectedConnections = new HashMap<>();
    private boolean paused;
    private int vipTimer, normalTimer, keepAliveTimer;
    private MinecraftServer server;

    // Proportional mode state
    private boolean proportionalVipPhase = true;
    private int proportionalPhaseCount = 0;
    private long globalJoinOrder = 0;
    private int skippedNormalCount = 0;
    private QueueEntry lockedEntry = null;

    private static final ThreadLocal<Boolean> ADMITTING = ThreadLocal.withInitial(() -> false);
    private static final int KEEPALIVE_INTERVAL = 100;

    private QueueManager() {}

    public static QueueManager getInstance() {
        if (instance == null) instance = new QueueManager();
        return instance;
    }

    public static boolean isAdmitting() { return ADMITTING.get(); }

    static void reset() {
        if (instance != null) {
            instance.staffQueue.clear();
            instance.priorityQueue.clear();
            instance.vipQueue.clear();
            instance.normalQueue.clear();
            instance.byUuid.clear();
            instance.rejoinMap.clear();
            instance.rejoinHistory.clear();
            instance.byConnection.clear();
            instance.paused = false;
            instance.vipTimer = 0;
            instance.normalTimer = 0;
            instance.proportionalVipPhase = true;
            instance.proportionalPhaseCount = 0;
            instance.globalJoinOrder = 0;
            instance.skippedNormalCount = 0;
            instance.lockedEntry = null;
        }
        instance = null;
    }

    // ── Queue list accessors ──

    private List<QueueEntry> queueFor(QueueType type) {
        return switch (type) {
            case STAFF -> staffQueue;
            case PRIORITY -> priorityQueue;
            case VIP -> vipQueue;
            case NORMAL -> normalQueue;
        };
    }

    private List<QueueEntry> queueForEntry(QueueEntry e) {
        return queueFor(e.queueType);
    }

    // ── Called from mixin (placeNewPlayer HEAD) ──

    public boolean shouldQueue(GameProfile profile) {
        if (!Config.ENABLED.get()) return false;
        if (Config.STAFF_BYPASS_QUEUE.get() && isStaff(profile)) return false;

        int effectiveMax = Config.EFFECTIVE_MAX_PLAYERS.get();
        int vipSlots = effectiveVipSlots();

        if (vipSlots > 0) {
            boolean vipEligible = isStaff(profile) || isVip(profile);
            if (Config.STAFF_BYPASS_QUEUE.get()) vipEligible = isVip(profile);

            if (!vipEligible) {
                int nonVipLimit = effectiveMax - vipSlots;
                int nonVipOnline = countNonVipOnline();
                if (nonVipOnline >= nonVipLimit) return true;
            }
        }

        if (isStaffExclusiveEnabled()) {
            return countNonStaffOccupiedSlots() >= effectiveMax;
        }

        return activeCount() >= effectiveMax;
    }

    public void enqueue(Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        GameProfile profile = player.getGameProfile();
        UUID uuid = profile.getId();
        long now = server.getTickCount();
        boolean staff = isStaff(profile);
        boolean vip = !staff && isVip(profile);

        // Restore disconnected entry if player reconnects within position-hold grace period
        QueueEntry disconnected = byUuid.get(uuid);
        if (disconnected != null && disconnected.state == QueueEntryState.DISCONNECTED) {
            disconnected.connection = connection;
            disconnected.serverPlayer = player;
            disconnected.cookie = cookie;
            disconnected.state = QueueEntryState.WAITING;
            disconnected.disconnectedAtTick = 0;
            byConnection.put(connection, disconnected);
            Smartqueue.LOGGER.info("Player {} reconnected, queue position restored (queue={}, pos={})",
                    profile.getName(), disconnected.queueType,
                    queueFor(disconnected.queueType).indexOf(disconnected) + 1);
            sendStatus(disconnected);
            broadcastPositions();
            return;
        }

        // Clean stale entry (only WAITING, DISCONNECTED handled above)
        QueueEntry old = byUuid.remove(uuid);
        if (old != null) {
            queueForEntry(old).remove(old);
            byConnection.remove(old.connection);
        }

        RejoinEntry rejoin = rejoinMap.remove(uuid);
        boolean isRejoin = false;
        if (rejoin != null) {
            int grace = Config.REJOIN_GRACE_TICKS.get();
            if (grace == 0 || (now - rejoin.leftAtTick) <= grace) isRejoin = true;
        }

        // Rejoin rate limiting
        boolean rateLimited = false;
        if (isRejoin && rejoin.type == RejoinType.WAS_PLAYING
                && Config.REJOIN_RATE_LIMIT_ENABLED.get()) {
            long window = Config.REJOIN_RATE_LIMIT_WINDOW_TICKS.get();
            int maxCount = Config.REJOIN_RATE_LIMIT_MAX_COUNT.get();
            List<Long> history = rejoinHistory.computeIfAbsent(uuid, k -> new ArrayList<>());
            history.add(now);
            long cutoff = now - window;
            history.removeIf(t -> t < cutoff);
            if (history.size() > maxCount) {
                rateLimited = true;
                isRejoin = false;
                Smartqueue.LOGGER.info("Player {} rejoin rate limit exceeded ({}/{}), treating as new",
                        profile.getName(), history.size(), maxCount);
            }
        }
        // Clear history only when rejoin chain is genuinely broken (not rate-limited)
        if (!isRejoin && !rateLimited) {
            rejoinHistory.remove(uuid);
        }

        int totalQueued = staffQueue.size() + priorityQueue.size() + vipQueue.size() + normalQueue.size();
        if (!isRejoin && totalQueued >= Config.MAX_QUEUE_SIZE.get()) {
            connection.send(new ClientboundCustomPayloadPacket(
                    new QueuePayloads.QueueStatusPayload(0, 0, 0, false, false, 0, true,
                            false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, false)));
            rejectedConnections.put(connection, server.getTickCount());
            Smartqueue.LOGGER.info("Rejected {} — queue is full", profile.getName());
            return;
        }

        QueueEntry entry;
        QueueType targetQueue;

        if (staff) {
            targetQueue = QueueType.STAFF;
        } else if (isRejoin && rejoin.type == RejoinType.WAS_PLAYING) {
            targetQueue = QueueType.PRIORITY;
        } else if (vip) {
            targetQueue = QueueType.VIP;
        } else {
            targetQueue = QueueType.NORMAL;
        }

        entry = new QueueEntry(player, connection, cookie, profile, vip, staff, now, targetQueue, ++globalJoinOrder);
        List<QueueEntry> targetList = queueFor(targetQueue);

        if (isRejoin && rejoin.type == RejoinType.WAS_PLAYING) {
            targetList.add(entry);
        } else if (staff) {
            targetList.add(0, entry);
        } else {
            targetList.add(entry);
        }
        byUuid.put(uuid, entry);
        byConnection.put(connection, entry);

        Smartqueue.LOGGER.info("Player {} queued in {} at pos {} (staff={}, vip={})",
                profile.getName(), targetQueue, targetList.indexOf(entry) + 1, staff, vip);
        sendStatus(entry);
        broadcastPositions();
    }

    // ── Disconnect ──

    public void onConnectionDisconnect(ServerConfigurationPacketListenerImpl listener) {
        Connection conn = listener.getConnection();
        Smartqueue.LOGGER.debug("onConnectionDisconnect: conn={}, queued={}", conn, byConnection.containsKey(conn));
        QueueEntry entry = byConnection.get(conn);
        if (entry != null && entry.state == QueueEntryState.WAITING) {
            if (entry == lockedEntry) lockedEntry = null;
            entry.state = QueueEntryState.DISCONNECTED;
            entry.disconnectedAtTick = server.getTickCount();
            byConnection.remove(conn);
            Smartqueue.LOGGER.info("Player {} connection lost, holding queue position for {}s",
                    entry.getName(), Config.QUEUE_DISCONNECT_GRACE_TICKS.get() / 20);
            broadcastPositions();
        }
    }

    private void cleanupRejoinHistory(long now) {
        if (!Config.REJOIN_RATE_LIMIT_ENABLED.get()) return;
        long window = Config.REJOIN_RATE_LIMIT_WINDOW_TICKS.get();
        long cutoff = now - window;
        var it = rejoinHistory.values().iterator();
        while (it.hasNext()) {
            List<Long> history = it.next();
            history.removeIf(t -> t < cutoff);
            if (history.isEmpty()) it.remove();
        }
    }

    private void cleanupRejected(long now) {
        if (rejectedConnections.isEmpty()) return;
        var it = rejectedConnections.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now - e.getValue() > 100) {
                e.getKey().disconnect(Component.translatable("smartqueue.screen.full"));
                Smartqueue.LOGGER.debug("cleanupRejected: disconnecting {}", e.getKey());
                it.remove();
            }
        }
    }

    private void cleanupDisconnected() {
        var it = byConnection.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!e.getKey().isConnected()) {
                QueueEntry entry = e.getValue();
                if (entry.state != QueueEntryState.WAITING) {
                    it.remove();
                    continue;
                }
                if (entry == lockedEntry) lockedEntry = null;
                entry.state = QueueEntryState.DISCONNECTED;
                entry.disconnectedAtTick = server.getTickCount();
                it.remove();
                Smartqueue.LOGGER.info("Player {} connection lost, holding queue position for {}s",
                        entry.getName(), Config.QUEUE_DISCONNECT_GRACE_TICKS.get() / 20);
            }
        }
    }

    private void cleanupExpiredDisconnected(long now) {
        int grace = Config.QUEUE_DISCONNECT_GRACE_TICKS.get();
        for (List<QueueEntry> queue : List.of(staffQueue, priorityQueue, vipQueue, normalQueue)) {
            var it = queue.iterator();
            while (it.hasNext()) {
                QueueEntry entry = it.next();
                if (entry.state == QueueEntryState.DISCONNECTED && (now - entry.disconnectedAtTick) >= grace) {
                    Smartqueue.LOGGER.info("Player {} removed from queue (reconnect timeout)", entry.getName());
                    if (entry == lockedEntry) lockedEntry = null;
                    byUuid.remove(entry.profile.getId());
                    it.remove();
                }
            }
        }
    }

    // ── Player logout (WAS_PLAYING rejoin) ──

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (instance == null || !Config.ENABLED.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        UUID uuid = sp.getUUID();
        if (instance.byUuid.containsKey(uuid)) return;
        boolean staff = instance.isStaff(sp.getGameProfile());
        boolean vip = !staff && instance.isVip(sp.getGameProfile());
        QueueType qt = staff ? QueueType.STAFF : QueueType.PRIORITY;
        instance.rejoinMap.put(uuid,
                new RejoinEntry(uuid, RejoinType.WAS_PLAYING, vip, staff, 0, instance.server.getTickCount(), qt));
        instance.tryAdmit();
    }

    // ── Tick ──

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        if (instance == null) return;
        long now = event.getServer().getTickCount();
        int grace = Config.REJOIN_GRACE_TICKS.get();
        if (grace > 0) instance.rejoinMap.values().removeIf(e -> (now - e.leftAtTick) > grace);

        instance.cleanupDisconnected();
        instance.cleanupExpiredDisconnected(now);
        instance.cleanupRejected(now);

        if (!Config.ENABLED.get()) { instance.kickAllQueued(); return; }

        instance.keepAliveTimer++;
        if (instance.keepAliveTimer >= KEEPALIVE_INTERVAL) {
            instance.keepAliveTimer = 0;
            if (instance.hasAnyQueued()) instance.broadcastPositions();
            instance.cleanupRejoinHistory(now);
        }

        if (instance.paused) return;

        // Reset anti-imbalance counter when queue is empty
        if (!instance.hasAnyQueued() && instance.skippedNormalCount > 0) {
            instance.skippedNormalCount = 0;
        }

        // Safety net: fill any open slot
        if (instance.hasAnyQueued() && !instance.isServerFull()) {
            if (instance.tryAdmit()) {
                instance.vipTimer = 0;
                instance.normalTimer = 0;
                if (!Config.PROPORTIONAL_MODE.get()) {
                    instance.proportionalPhaseCount = 0;
                }
            }
        }

        if (Config.PROPORTIONAL_MODE.get()) {
            instance.tickProportional();
        } else {
            instance.tickLegacy();
        }
    }

    private void tickProportional() {
        vipTimer++;
        if (vipTimer >= Config.NORMAL_ADMIT_INTERVAL_TICKS.get()) {
            vipTimer = 0;
            admitNextProportional();
        }
    }

    private void tickLegacy() {
        vipTimer++;
        if (vipTimer >= Config.VIP_ADMIT_INTERVAL_TICKS.get()) {
            vipTimer = 0;
            admitNextLegacy(true);
        }
        normalTimer++;
        if (normalTimer >= Config.NORMAL_ADMIT_INTERVAL_TICKS.get()) {
            normalTimer = 0;
            admitNextLegacy(false);
        }
    }

    // ── Proportional admission ──

    private void admitNextProportional() {
        if (isServerFull()) return;

        if (tryAdmitLocked()) return;

        // 1. Staff always first
        if (admitFirstFrom(staffQueue)) return;

        // 2. Priority rejoin next
        if (admitFirstPriorityChecked()) return;

        // 3. Anti-imbalance catch-up: admit by real join order regardless of type
        if (skippedNormalCount > 0) {
            if (canAdmitNormal()) {
                if (admitOldestAny()) return;
            } else {
                if (admitFirstFrom(vipQueue)) return;
            }
        }

        // 4. Proportional VIP/normal cycle
        admitProportionalCycle();
    }

    private boolean admitProportionalCycle() {
        int vipQuota = Config.PROPORTIONAL_VIP_COUNT.get();
        int normalQuota = Config.PROPORTIONAL_NORMAL_COUNT.get();

        if (proportionalVipPhase) {
            if (proportionalPhaseCount < vipQuota) {
                if (admitFirstFrom(vipQueue)) { proportionalPhaseCount++; return true; }
            }
            proportionalVipPhase = false;
            proportionalPhaseCount = 0;
        }

        if (!proportionalVipPhase) {
            if (proportionalPhaseCount < normalQuota) {
                if (canAdmitNormal()) {
                    if (admitFirstFrom(normalQueue)) { proportionalPhaseCount++; return true; }
                } else if (hasWaitingNormal()) {
                    if (hasWaiting(vipQueue)) {
                        skippedNormalCount++;
                        Smartqueue.LOGGER.debug("Normal admission skipped (slots full), skipCount={}", skippedNormalCount);
                    }
                    proportionalVipPhase = true;
                    proportionalPhaseCount = 0;
                }
            }
            if (proportionalPhaseCount >= normalQuota || !hasWaitingNormal()) {
                proportionalVipPhase = true;
                proportionalPhaseCount = 0;
            }
        }

        // Try new phase immediately
        if (proportionalVipPhase && admitFirstFrom(vipQueue)) { proportionalPhaseCount++; return true; }
        if (!proportionalVipPhase && canAdmitNormal() && admitFirstFrom(normalQueue)) {
            proportionalPhaseCount++;
            return true;
        }
        return false;
    }

    // ── Legacy admission ──

    private void admitNextLegacy(boolean vip) {
        if (isServerFull()) return;
        if (tryAdmitLocked()) return;
        int vipSlots = effectiveVipSlots();
        if (!vip && vipSlots > 0) {
            int nonVipLimit = Config.EFFECTIVE_MAX_PLAYERS.get() - vipSlots;
            int nonVipOnline = countNonVipOnline();
            if (nonVipOnline >= nonVipLimit) return;
        }
        if (vip) {
            if (admitFirstFrom(staffQueue)) return;
            if (admitFirstPriorityChecked()) return;
            if (admitFirstFrom(vipQueue)) return;
        } else {
            if (canAdmitNormal() && admitFirstFrom(normalQueue)) return;
        }
    }

    // ── Helpers ──

    private boolean tryAdmitLocked() {
        if (lockedEntry != null && lockedEntry.state == QueueEntryState.WAITING) {
            if (!lockedEntry.vip && !lockedEntry.staff && !canAdmitNormal()) {
                return false;
            }
            if (Config.PROPORTIONAL_MODE.get()) {
                advanceProportionalState(lockedEntry);
            }
            admit(lockedEntry); // admit() clears lockedEntry and calls broadcastPositions()
            return true;
        }
        return false;
    }

    private void advanceProportionalState(QueueEntry entry) {
        boolean isPriority = entry.queueType == QueueType.STAFF
                || entry.queueType == QueueType.PRIORITY
                || entry.queueType == QueueType.VIP;
        int vipQuota = Config.PROPORTIONAL_VIP_COUNT.get();
        int normalQuota = Config.PROPORTIONAL_NORMAL_COUNT.get();

        if (isPriority && proportionalVipPhase) {
            proportionalPhaseCount++;
            if (proportionalPhaseCount >= vipQuota) {
                proportionalVipPhase = false;
                proportionalPhaseCount = 0;
            }
        } else if (!isPriority && !proportionalVipPhase) {
            proportionalPhaseCount++;
            if (proportionalPhaseCount >= normalQuota) {
                proportionalVipPhase = true;
                proportionalPhaseCount = 0;
            }
        }
    }

    private boolean admitFirstFrom(List<QueueEntry> q) {
        for (QueueEntry e : q) {
            if (e.state == QueueEntryState.WAITING) {
                admit(e);
                return true;
            }
        }
        return false;
    }

    private boolean admitFirstPriorityChecked() {
        for (QueueEntry e : priorityQueue) {
            if (e.state != QueueEntryState.WAITING) continue;
            if (!e.vip && !e.staff && !canAdmitNormal()) continue;
            admit(e);
            return true;
        }
        return false;
    }

    private boolean hasWaiting(List<QueueEntry> q) {
        for (QueueEntry e : q) if (e.state == QueueEntryState.WAITING) return true;
        return false;
    }

    private boolean hasWaitingNormal() {
        return hasWaiting(normalQueue);
    }

    private boolean admitOldestAny() {
        QueueEntry oldest = null;
        for (QueueEntry e : vipQueue) {
            if (e.state != QueueEntryState.WAITING) continue;
            if (oldest == null || e.joinOrder < oldest.joinOrder) oldest = e;
        }
        for (QueueEntry e : normalQueue) {
            if (e.state != QueueEntryState.WAITING) continue;
            if (oldest == null || e.joinOrder < oldest.joinOrder) oldest = e;
        }
        if (oldest != null) {
            admit(oldest);
            return true;
        }
        return false;
    }

    private boolean canAdmitNormal() {
        int vipSlots = effectiveVipSlots();
        if (vipSlots <= 0) return true;
        int nonVipLimit = Config.EFFECTIVE_MAX_PLAYERS.get() - vipSlots;
        return countNonVipOnline() < nonVipLimit;
    }

    public boolean isAntiImbalance() {
        return Config.PROPORTIONAL_MODE.get() && skippedNormalCount > 0;
    }

    public int getSkippedNormalCount() {
        return skippedNormalCount;
    }

    private void admit(QueueEntry entry) {
        // Each normal admitted counts toward anti-imbalance catch-up
        if (Config.PROPORTIONAL_MODE.get() && entry.queueType == QueueType.NORMAL
                && skippedNormalCount > 0) {
            skippedNormalCount--;
        }
        if (entry == lockedEntry) lockedEntry = null;
        entry.state = QueueEntryState.ADMITTED;
        queueForEntry(entry).remove(entry);
        byUuid.remove(entry.profile.getId());
        byConnection.remove(entry.connection);
        Smartqueue.LOGGER.info("Admitting player {} from queue (type={})", entry.getName(), entry.queueType);
        ADMITTING.set(true);
        try {
            server.getPlayerList().placeNewPlayer(entry.connection, entry.serverPlayer, entry.cookie);
        } finally {
            ADMITTING.set(false);
        }
        int total = totalQueued();
        entry.connection.send(new ClientboundCustomPayloadPacket(
                new QueuePayloads.QueueStatusPayload(0, total, 0, true, paused, 0, false,
                        false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, false)));
        broadcastPositions();
    }

    private void kickAllQueued() {
        List<QueueEntry> all = allQueued();
        if (all.isEmpty()) return;
        staffQueue.clear();
        priorityQueue.clear();
        vipQueue.clear();
        normalQueue.clear();
        for (QueueEntry e : all) {
            byUuid.remove(e.profile.getId());
            byConnection.remove(e.connection);
            if (e.state != QueueEntryState.DISCONNECTED) {
                e.connection.disconnect(Component.translatable("smartqueue.command.disabled"));
            }
        }
        Smartqueue.LOGGER.info("Kicked {} queued players (queue disabled)", all.size());
    }

    public void leaveQueue(ServerConfigurationPacketListenerImpl listener) {
        Connection conn = listener.getConnection();
        Smartqueue.LOGGER.debug("leaveQueue: conn={}, queued={}", conn, byConnection.containsKey(conn));
        QueueEntry entry = byConnection.get(conn);
        if (entry != null && entry.state == QueueEntryState.WAITING) {
            entry.state = QueueEntryState.DISCONNECTED;
            entry.disconnectedAtTick = server.getTickCount();
            byConnection.remove(conn);
            Smartqueue.LOGGER.info("Player {} left the queue, holding position for {}s",
                    entry.getName(), Config.QUEUE_DISCONNECT_GRACE_TICKS.get() / 20);
            conn.disconnect(Component.translatable("smartqueue.screen.left"));
            broadcastPositions();
        }
    }

    private boolean tryAdmit() {
        if (paused || !Config.ENABLED.get()) return false;
        if (!isServerFull()) {
            if (tryAdmitLocked()) return true;
            if (admitFirstFrom(staffQueue)) return true;
            if (admitFirstPriorityChecked()) return true;
            if (skippedNormalCount > 0) {
                if (canAdmitNormal()) {
                    if (admitOldestAny()) return true;
                }
            }
            if (Config.PROPORTIONAL_MODE.get()) {
                return admitProportionalCycle();
            } else {
                if (admitFirstFrom(vipQueue)) return true;
                if (canAdmitNormal() && admitFirstFrom(normalQueue)) return true;
            }
        }
        return false;
    }

    // ── Unified view for client ──

    private int countWaiting(List<QueueEntry> q) {
        int c = 0;
        for (QueueEntry e : q) if (e.state == QueueEntryState.WAITING) c++;
        return c;
    }

    private int totalWaiting() {
        return countWaiting(staffQueue) + countWaiting(priorityQueue)
                + countWaiting(vipQueue) + countWaiting(normalQueue);
    }

    private List<QueueEntry> buildDispatchOrder() {
        List<QueueEntry> order = new ArrayList<>();

        // Locked entry always goes first — prevents newly arriving high-priority
        // players from displacing the person who is "next"
        if (lockedEntry != null && lockedEntry.state == QueueEntryState.WAITING) {
            order.add(lockedEntry);
        }

        for (QueueEntry e : staffQueue)
            if (e.state == QueueEntryState.WAITING && e != lockedEntry) order.add(e);
        for (QueueEntry e : priorityQueue)
            if (e.state == QueueEntryState.WAITING && e != lockedEntry) order.add(e);

        if (!Config.PROPORTIONAL_MODE.get()) {
            for (QueueEntry e : vipQueue)
                if (e.state == QueueEntryState.WAITING && e != lockedEntry) order.add(e);
            for (QueueEntry e : normalQueue)
                if (e.state == QueueEntryState.WAITING && e != lockedEntry) order.add(e);
        } else {
            simulateProportionalDispatch(order);
        }

        return order;
    }

    private void simulateProportionalDispatch(List<QueueEntry> order) {
        int vipQuota = Config.PROPORTIONAL_VIP_COUNT.get();
        int normalQuota = Config.PROPORTIONAL_NORMAL_COUNT.get();
        boolean vipPhase = proportionalVipPhase;
        int phaseCount = proportionalPhaseCount;
        int skipped = skippedNormalCount;
        boolean canAdmitNormalNow = canAdmitNormal();

        // Account for locked entry already placed first in dispatch order
        if (lockedEntry != null && lockedEntry.state == QueueEntryState.WAITING) {
            boolean lockedIsPriority = lockedEntry.queueType == QueueType.STAFF
                    || lockedEntry.queueType == QueueType.PRIORITY
                    || lockedEntry.queueType == QueueType.VIP;
            if (lockedIsPriority && vipPhase) {
                phaseCount++;
                if (phaseCount >= vipQuota) { vipPhase = false; phaseCount = 0; }
            } else if (!lockedIsPriority && !vipPhase) {
                phaseCount++;
                if (phaseCount >= normalQuota) { vipPhase = true; phaseCount = 0; }
            }
        }

        List<QueueEntry> vipRemaining = new ArrayList<>();
        for (QueueEntry e : vipQueue)
            if (e.state == QueueEntryState.WAITING && e != lockedEntry) vipRemaining.add(e);
        List<QueueEntry> normalRemaining = new ArrayList<>();
        for (QueueEntry e : normalQueue)
            if (e.state == QueueEntryState.WAITING && e != lockedEntry) normalRemaining.add(e);

        int maxIter = vipRemaining.size() + normalRemaining.size() + 10;
        while ((!vipRemaining.isEmpty() || !normalRemaining.isEmpty()) && maxIter-- > 0) {
            if (skipped > 0) {
                QueueEntry oldest = null;
                boolean fromVip = false;
                for (QueueEntry e : vipRemaining) {
                    if (oldest == null || e.joinOrder < oldest.joinOrder) {
                        oldest = e;
                        fromVip = true;
                    }
                }
                if (canAdmitNormalNow) {
                    for (QueueEntry e : normalRemaining) {
                        if (oldest == null || e.joinOrder < oldest.joinOrder) {
                            oldest = e;
                            fromVip = false;
                        }
                    }
                }
                if (oldest != null) {
                    order.add(oldest);
                    if (fromVip) vipRemaining.remove(oldest);
                    else { normalRemaining.remove(oldest); skipped--; }
                    continue;
                }
            }

            if (vipPhase) {
                if (phaseCount < vipQuota && !vipRemaining.isEmpty()) {
                    order.add(vipRemaining.remove(0));
                    phaseCount++;
                    continue;
                }
                vipPhase = false;
                phaseCount = 0;
            }

            if (!vipPhase) {
                if (phaseCount < normalQuota && !normalRemaining.isEmpty()) {
                    if (canAdmitNormalNow) {
                        order.add(normalRemaining.remove(0));
                        phaseCount++;
                        continue;
                    } else if (!vipRemaining.isEmpty()) {
                        skipped++;
                        vipPhase = true;
                        phaseCount = 0;
                        continue;
                    }
                    // can't admit normals and no VIPs to skip to → dump remaining normals
                    order.addAll(normalRemaining);
                    normalRemaining.clear();
                    continue;
                }
                if (phaseCount >= normalQuota || normalRemaining.isEmpty()) {
                    vipPhase = true;
                    phaseCount = 0;
                    continue;
                }
            }

            if (vipPhase && vipRemaining.isEmpty() && !normalRemaining.isEmpty()) {
                if (canAdmitNormalNow) { vipPhase = false; phaseCount = 0; }
                else { order.addAll(normalRemaining); normalRemaining.clear(); }
            } else if (!vipPhase && normalRemaining.isEmpty() && !vipRemaining.isEmpty()) {
                vipPhase = true; phaseCount = 0;
            } else if (vipRemaining.isEmpty() && normalRemaining.isEmpty()) {
                break;
            }
        }
    }

    private void broadcastPositions() {
        List<QueueEntry> dispatchOrder = buildDispatchOrder();
        for (QueueEntry e : allQueued()) {
            if (e.state == QueueEntryState.WAITING) sendStatus(e, dispatchOrder);
        }
    }

    private void sendStatus(QueueEntry entry) {
        sendStatus(entry, buildDispatchOrder());
    }

    private void sendStatus(QueueEntry entry, List<QueueEntry> dispatchOrder) {
        // Compute ahead from dispatch order for consistency with per-queue breakdown
        int ahead = 0;
        int vAhead = 0, nAhead = 0;
        for (QueueEntry e : dispatchOrder) {
            if (e == entry) break;
            ahead++;
            if (e.staff || e.vip) vAhead++; else nAhead++;
        }
        int total = totalWaiting();
        int position = ahead + 1;
        int eta = 0;
        if (ahead > 0) {
            if (Config.PROPORTIONAL_MODE.get()) {
                eta = (ahead * Config.NORMAL_ADMIT_INTERVAL_TICKS.get()) / 20;
            } else {
                eta = (vAhead * Config.VIP_ADMIT_INTERVAL_TICKS.get()
                       + nAhead * Config.NORMAL_ADMIT_INTERVAL_TICKS.get()) / 20;
            }
        }

        boolean showDetail = Config.SHOW_QUEUE_DETAIL.get();
        boolean staffBypassQueue = Config.STAFF_BYPASS_QUEUE.get();
        int totalStaff = 0, totalPriority = 0, totalVip = 0, totalNormal = 0;
        int aheadStaff = 0, aheadPriority = 0, aheadVip = 0, aheadNormal = 0;

        if (showDetail) {
            totalStaff = countWaiting(staffQueue);
            totalPriority = countWaiting(priorityQueue);
            totalVip = countWaiting(vipQueue);
            totalNormal = countWaiting(normalQueue);

            for (QueueEntry e : dispatchOrder) {
                if (e == entry) break;
                switch (e.queueType) {
                    case STAFF -> aheadStaff++;
                    case PRIORITY -> aheadPriority++;
                    case VIP -> aheadVip++;
                    case NORMAL -> aheadNormal++;
                }
            }
        }

        // Lock: first person in line cannot be displaced by new arrivals.
        // Whether they can enter is checked at admission time (tryAdmitLocked).
        if (ahead == 0 && (lockedEntry == null || lockedEntry.state != QueueEntryState.WAITING)) {
            lockedEntry = entry;
        }

        boolean blocked = !entry.vip && !entry.staff && !canAdmitNormal();
        var payload = new QueuePayloads.QueueStatusPayload(
                position, total, ahead, false, paused, eta, false,
                showDetail, staffBypassQueue,
                totalStaff, totalPriority, totalVip, totalNormal,
                aheadStaff, aheadPriority, aheadVip, aheadNormal,
                entry.queueType.ordinal(), blocked);
        if (entry.connection.isConnected())
            entry.connection.send(new ClientboundCustomPayloadPacket(payload));
    }

    // ── Lifecycle ──

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        getInstance().server = event.getServer();
        Smartqueue.LOGGER.info("QueueManager initialized");
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        reset();
        Smartqueue.LOGGER.info("QueueManager shut down");
    }

    // ── Queries / mgmt ──

    public int activeCount() {
        if (server == null) return 0;
        return server.getPlayerList().getPlayerCount();
    }

    public boolean isStaff(GameProfile p) {
        String n = p.getName();
        return Config.STAFF_LIST.get().stream().anyMatch(s -> s.equalsIgnoreCase(n));
    }
    public boolean isVip(GameProfile p) {
        String n = p.getName();
        return Config.VIP_LIST.get().stream().anyMatch(s -> s.equalsIgnoreCase(n));
    }

    public int effectiveVipSlots() {
        return Math.min(Config.VIP_EXCLUSIVE_SLOTS.get(), Config.EFFECTIVE_MAX_PLAYERS.get());
    }

    // ── Staff exclusive slots helpers ──

    public boolean isStaffExclusiveEnabled() {
        return Config.STAFF_BYPASS_QUEUE.get() && Config.STAFF_EXCLUSIVE_SLOTS.get();
    }

    public int countStaffOnline() {
        if (server == null) return 0;
        int count = 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (isStaff(sp.getGameProfile())) count++;
        }
        return count;
    }

    public int getStaffInExclusiveSlots() {
        if (!isStaffExclusiveEnabled()) return 0;
        int staffOnline = countStaffOnline();
        int exclusiveCount = Config.STAFF_EXCLUSIVE_SLOTS_COUNT.get();
        if (exclusiveCount == 0) return staffOnline;
        return Math.min(staffOnline, exclusiveCount);
    }

    public int countNonStaffOccupiedSlots() {
        return activeCount() - getStaffInExclusiveSlots();
    }

    private boolean isServerFull() {
        if (isStaffExclusiveEnabled()) {
            return countNonStaffOccupiedSlots() >= Config.EFFECTIVE_MAX_PLAYERS.get();
        }
        return activeCount() >= Config.EFFECTIVE_MAX_PLAYERS.get();
    }

    public int getDisplayMaxPlayers() {
        return Config.EFFECTIVE_MAX_PLAYERS.get();
    }

    public int getStaffExclusiveSlotsForDisplay() {
        if (!isStaffExclusiveEnabled()) return 0;
        return Config.STAFF_EXCLUSIVE_SLOTS_COUNT.get();
    }

    public int countVipEligibleOnline() {
        if (server == null) return 0;
        int count = 0;
        boolean staffBypass = Config.STAFF_BYPASS_QUEUE.get();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            GameProfile profile = sp.getGameProfile();
            if (isVip(profile)) count++;
            else if (!staffBypass && isStaff(profile)) count++;
        }
        return count;
    }

    public int countNonVipOnline() {
        if (server == null) return 0;
        int count = 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            GameProfile profile = sp.getGameProfile();
            if (!isVip(profile) && !isStaff(profile)) count++;
        }
        return count;
    }

    // ── Queue size queries ──

    private List<QueueEntry> allQueued() {
        List<QueueEntry> all = new ArrayList<>();
        all.addAll(staffQueue);
        all.addAll(priorityQueue);
        all.addAll(vipQueue);
        all.addAll(normalQueue);
        return all;
    }

    private boolean hasAnyQueued() {
        return !staffQueue.isEmpty() || !priorityQueue.isEmpty()
                || !vipQueue.isEmpty() || !normalQueue.isEmpty();
    }

    public int queueSize() {
        return staffQueue.size() + priorityQueue.size() + vipQueue.size() + normalQueue.size();
    }

    private int totalQueued() { return queueSize(); }

    public boolean isPaused() { return paused; }
    public void setPaused(boolean v) {
        paused = v;
        if (!v) { vipTimer = 0; normalTimer = 0; proportionalPhaseCount = 0; skippedNormalCount = 0; }
        broadcastPositions();
    }
    public void setEnabled(boolean v) {
        Config.ENABLED.set(v);
        if (!v) { kickAllQueued(); }
    }

    public List<String> getStaffList() { return List.copyOf(Config.STAFF_LIST.get()); }
    public List<String> getVipList() { return List.copyOf(Config.VIP_LIST.get()); }

    public void addStaff(String n) {
        List<String> l = new ArrayList<>(Config.STAFF_LIST.get());
        if (l.stream().noneMatch(s -> s.equalsIgnoreCase(n))) {
            l.add(n);
            Config.STAFF_LIST.set(l);
            Config.saveStaffConfig();
        }
    }
    public void removeStaff(String n) {
        List<String> l = new ArrayList<>(Config.STAFF_LIST.get());
        if (l.removeIf(s -> s.equalsIgnoreCase(n))) {
            Config.STAFF_LIST.set(l);
            Config.saveStaffConfig();
            broadcastPositions();
        }
    }
    public void addVip(String n) {
        List<String> l = new ArrayList<>(Config.VIP_LIST.get());
        if (l.stream().noneMatch(s -> s.equalsIgnoreCase(n))) {
            l.add(n);
            Config.VIP_LIST.set(l);
            Config.saveVipConfig();
        }
    }
    public void removeVip(String n) {
        List<String> l = new ArrayList<>(Config.VIP_LIST.get());
        if (l.removeIf(s -> s.equalsIgnoreCase(n))) {
            Config.VIP_LIST.set(l);
            Config.saveVipConfig();
            broadcastPositions();
        }
    }

    public boolean isQueuedConnection(Connection conn) {
        return byConnection.containsKey(conn);
    }

    public void cleanupIfDisconnected(UUID uuid) {
        QueueEntry entry = byUuid.get(uuid);
        if (entry != null && entry.state == QueueEntryState.DISCONNECTED) {
            queueForEntry(entry).remove(entry);
            byUuid.remove(uuid);
            Smartqueue.LOGGER.info("Player {} removed from queue (joined directly, server not full)", entry.getName());
            broadcastPositions();
        }
    }

    // ── Status display (for commands) ──

    public QueueSnapshot getSnapshot() {
        return new QueueSnapshot(
                List.copyOf(staffQueue), List.copyOf(priorityQueue),
                List.copyOf(vipQueue), List.copyOf(normalQueue),
                getNextAdmitEntry(), isNextAdmitCertain(),
                isAntiImbalance(), skippedNormalCount);
    }

    private boolean isNextAdmitCertain() {
        // Locked entry is first in line — but if blocked, next is uncertain
        if (lockedEntry != null && lockedEntry.state == QueueEntryState.WAITING) {
            return lockedEntry.vip || lockedEntry.staff || canAdmitNormal();
        }
        // If nextAdmit is a normal who can't currently enter → uncertain
        QueueEntry next = getNextAdmitEntry();
        if (next != null && !next.vip && !next.staff && !canAdmitNormal()) {
            return false;
        }
        // Check if both VIP-like and Normal players are waiting across all queues
        boolean hasVip = hasWaiting(vipQueue)
                || hasWaitingMatching(priorityQueue, e -> e.vip || e.staff);
        boolean hasNormal = hasWaiting(normalQueue)
                || hasWaitingMatching(priorityQueue, e -> !e.vip && !e.staff);
        if (!hasVip || !hasNormal) return true;
        return canAdmitNormal();
    }

    private boolean hasWaitingMatching(List<QueueEntry> q, java.util.function.Predicate<QueueEntry> pred) {
        for (QueueEntry e : q)
            if (e.state == QueueEntryState.WAITING && pred.test(e)) return true;
        return false;
    }

    private QueueEntry getNextAdmitEntry() {
        // Locked entry always goes first in dispatch order
        if (lockedEntry != null && lockedEntry.state == QueueEntryState.WAITING) {
            return lockedEntry;
        }
        // Staff first
        for (QueueEntry e : staffQueue) if (e.state == QueueEntryState.WAITING) return e;
        // Priority rejoin
        for (QueueEntry e : priorityQueue) if (e.state == QueueEntryState.WAITING) return e;
        // Anti-imbalance: oldest across VIP and normal by real join order
        if (skippedNormalCount > 0) {
            QueueEntry oldest = null;
            if (canAdmitNormal()) {
                for (QueueEntry e : vipQueue) {
                    if (e.state != QueueEntryState.WAITING) continue;
                    if (oldest == null || e.joinOrder < oldest.joinOrder) oldest = e;
                }
                for (QueueEntry e : normalQueue) {
                    if (e.state != QueueEntryState.WAITING) continue;
                    if (oldest == null || e.joinOrder < oldest.joinOrder) oldest = e;
                }
            } else {
                for (QueueEntry e : vipQueue) {
                    if (e.state != QueueEntryState.WAITING) continue;
                    if (oldest == null || e.joinOrder < oldest.joinOrder) oldest = e;
                }
            }
            if (oldest != null) return oldest;
        }
        // Then proportional or legacy order
        if (Config.PROPORTIONAL_MODE.get()) {
            if (proportionalVipPhase) {
                for (QueueEntry e : vipQueue) if (e.state == QueueEntryState.WAITING) return e;
                if (canAdmitNormal()) {
                    for (QueueEntry e : normalQueue) if (e.state == QueueEntryState.WAITING) return e;
                }
            } else {
                if (canAdmitNormal()) {
                    for (QueueEntry e : normalQueue) if (e.state == QueueEntryState.WAITING) return e;
                }
                for (QueueEntry e : vipQueue) if (e.state == QueueEntryState.WAITING) return e;
            }
        } else {
            for (QueueEntry e : vipQueue) if (e.state == QueueEntryState.WAITING) return e;
            if (canAdmitNormal()) {
                for (QueueEntry e : normalQueue) if (e.state == QueueEntryState.WAITING) return e;
            }
        }
        return null;
    }

    public record QueueSnapshot(List<QueueEntry> staff, List<QueueEntry> priority,
                                 List<QueueEntry> vip, List<QueueEntry> normal,
                                 QueueEntry nextAdmit, boolean nextAdmitCertain,
                                 boolean antiImbalance, int skippedNormalCount) {}
}
