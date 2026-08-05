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
            instance.byConnection.clear();
            instance.paused = false;
            instance.vipTimer = 0;
            instance.normalTimer = 0;
            instance.proportionalVipPhase = true;
            instance.proportionalPhaseCount = 0;
            instance.globalJoinOrder = 0;
            instance.skippedNormalCount = 0;
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

        return activeCount() >= effectiveMax;
    }

    public void enqueue(Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        GameProfile profile = player.getGameProfile();
        UUID uuid = profile.getId();
        long now = server.getTickCount();
        boolean staff = isStaff(profile);
        boolean vip = !staff && isVip(profile);

        // Clean stale entry
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

        int totalQueued = staffQueue.size() + priorityQueue.size() + vipQueue.size() + normalQueue.size();
        if (!isRejoin && totalQueued >= Config.MAX_QUEUE_SIZE.get()) {
            connection.send(new ClientboundCustomPayloadPacket(
                    new QueuePayloads.QueueStatusPayload(0, 0, 0, false, false, 0, true)));
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

        long joinOrder;
        if (isRejoin && rejoin.type == RejoinType.WAS_QUEUING && old != null) {
            joinOrder = old.joinOrder;
        } else {
            joinOrder = ++globalJoinOrder;
        }
        entry = new QueueEntry(player, connection, cookie, profile, vip, staff, now, targetQueue, joinOrder);
        List<QueueEntry> targetList = queueFor(targetQueue);

        if (isRejoin && rejoin.type == RejoinType.WAS_QUEUING) {
            int pos = Math.min(rejoin.savedPosition, targetList.size());
            targetList.add(pos, entry);
        } else if (isRejoin && rejoin.type == RejoinType.WAS_PLAYING) {
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

    private void saveRejoinForEntry(QueueEntry entry) {
        int pos = queueForEntry(entry).indexOf(entry);
        rejoinMap.put(entry.profile.getId(),
                new RejoinEntry(entry.profile.getId(), RejoinType.WAS_QUEUING,
                        entry.vip, entry.staff, pos, server.getTickCount(), entry.queueType));
        Smartqueue.LOGGER.debug("saveRejoinForEntry: {} queueType={} pos={}", entry.getName(), entry.queueType, pos);
    }

    public void onConnectionDisconnect(ServerConfigurationPacketListenerImpl listener) {
        Connection conn = listener.getConnection();
        Smartqueue.LOGGER.debug("onConnectionDisconnect: conn={}, queued={}", conn, byConnection.containsKey(conn));
        QueueEntry entry = byConnection.get(conn);
        if (entry != null) {
            saveRejoinForEntry(entry);
            byConnection.remove(conn);
            queueForEntry(entry).remove(entry);
            byUuid.remove(entry.profile.getId());
            Smartqueue.LOGGER.info("Player {} left the queue (disconnected)", entry.getName());
            broadcastPositions();
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
                Smartqueue.LOGGER.debug("cleanupDisconnected: removing {} (channel inactive)", entry.getName());
                saveRejoinForEntry(entry);
                it.remove();
                queueForEntry(entry).remove(entry);
                byUuid.remove(entry.profile.getId());
                Smartqueue.LOGGER.info("Player {} removed from queue (connection lost)", entry.getName());
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
        instance.cleanupRejected(now);

        if (!Config.ENABLED.get()) { instance.kickAllQueued(); return; }

        instance.keepAliveTimer++;
        if (instance.keepAliveTimer >= KEEPALIVE_INTERVAL) {
            instance.keepAliveTimer = 0;
            if (instance.hasAnyQueued()) instance.broadcastPositions();
        }

        if (instance.paused) return;

        // Safety net: fill any open slot
        if (instance.hasAnyQueued() && instance.activeCount() < Config.EFFECTIVE_MAX_PLAYERS.get()) {
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
        if (activeCount() >= Config.EFFECTIVE_MAX_PLAYERS.get()) return;

        // 1. Staff always first
        if (admitFirstFrom(staffQueue)) return;

        // 2. Priority rejoin next
        if (admitFirstFrom(priorityQueue)) return;

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
        if (activeCount() >= Config.EFFECTIVE_MAX_PLAYERS.get()) return;
        int vipSlots = effectiveVipSlots();
        if (!vip && vipSlots > 0) {
            int nonVipLimit = Config.EFFECTIVE_MAX_PLAYERS.get() - vipSlots;
            int nonVipOnline = countNonVipOnline();
            if (nonVipOnline >= nonVipLimit) return;
        }
        if (vip) {
            if (admitFirstFrom(staffQueue)) return;
            if (admitFirstFrom(priorityQueue)) return;
            if (admitFirstFrom(vipQueue)) return;
        } else {
            if (canAdmitNormal() && admitFirstFrom(normalQueue)) return;
        }
    }

    // ── Helpers ──

    private boolean admitFirstFrom(List<QueueEntry> q) {
        for (QueueEntry e : q) {
            if (e.state == QueueEntryState.WAITING) {
                admit(e);
                return true;
            }
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
            if (oldest.queueType == QueueType.NORMAL) skippedNormalCount--;
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
                new QueuePayloads.QueueStatusPayload(0, total, 0, true, paused, 0, false)));
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
            e.connection.disconnect(Component.translatable("smartqueue.command.disabled"));
        }
        Smartqueue.LOGGER.info("Kicked {} queued players (queue disabled)", all.size());
    }

    public void leaveQueue(ServerConfigurationPacketListenerImpl listener) {
        Connection conn = listener.getConnection();
        Smartqueue.LOGGER.debug("leaveQueue: conn={}, queued={}", conn, byConnection.containsKey(conn));
        QueueEntry entry = byConnection.get(conn);
        if (entry != null) {
            saveRejoinForEntry(entry);
            entry.state = QueueEntryState.LEFT;
            byConnection.remove(conn);
            queueForEntry(entry).remove(entry);
            byUuid.remove(entry.profile.getId());
            Smartqueue.LOGGER.info("Player {} left the queue", entry.getName());
            conn.disconnect(Component.translatable("smartqueue.screen.left"));
            broadcastPositions();
        }
    }

    private boolean tryAdmit() {
        if (paused || !Config.ENABLED.get()) return false;
        if (activeCount() < Config.EFFECTIVE_MAX_PLAYERS.get()) {
            if (admitFirstFrom(staffQueue)) return true;
            if (admitFirstFrom(priorityQueue)) return true;
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

    private int computeAhead(QueueEntry entry) {
        return switch (entry.queueType) {
            case STAFF -> indexOfWaiting(staffQueue, entry);
            case PRIORITY -> countWaiting(staffQueue) + indexOfWaiting(priorityQueue, entry);
            case VIP -> countWaiting(staffQueue) + countWaiting(priorityQueue) + indexOfWaiting(vipQueue, entry);
            case NORMAL -> countWaiting(staffQueue) + countWaiting(priorityQueue)
                           + countWaiting(vipQueue) + indexOfWaiting(normalQueue, entry);
        };
    }

    private int countWaiting(List<QueueEntry> q) {
        int c = 0;
        for (QueueEntry e : q) if (e.state == QueueEntryState.WAITING) c++;
        return c;
    }

    private int indexOfWaiting(List<QueueEntry> q, QueueEntry target) {
        int idx = 0;
        for (QueueEntry e : q) {
            if (e.state != QueueEntryState.WAITING) continue;
            if (e == target) return idx;
            idx++;
        }
        return idx;
    }

    private void broadcastPositions() {
        for (QueueEntry e : allQueued()) sendStatus(e);
    }

    private void sendStatus(QueueEntry entry) {
        int ahead = computeAhead(entry);
        int total = countWaiting(staffQueue) + countWaiting(priorityQueue)
                    + countWaiting(vipQueue) + countWaiting(normalQueue);
        int position = ahead + 1;
        int eta = 0;
        if (ahead > 0) {
            if (Config.PROPORTIONAL_MODE.get()) {
                eta = (ahead * Config.NORMAL_ADMIT_INTERVAL_TICKS.get()) / 20;
            } else {
                int v = 0, n = 0;
                for (QueueEntry e : allQueued()) {
                    if (e.state != QueueEntryState.WAITING) continue;
                    if (e == entry) break;
                    if (e.staff || e.vip) v++; else n++;
                }
                eta = (v * Config.VIP_ADMIT_INTERVAL_TICKS.get()
                       + n * Config.NORMAL_ADMIT_INTERVAL_TICKS.get()) / 20;
            }
        }
        var payload = new QueuePayloads.QueueStatusPayload(position, total, ahead, false, paused, eta, false);
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

    // ── Status display (for commands) ──

    public QueueSnapshot getSnapshot() {
        return new QueueSnapshot(
                List.copyOf(staffQueue), List.copyOf(priorityQueue),
                List.copyOf(vipQueue), List.copyOf(normalQueue),
                getNextAdmitEntry(), isAntiImbalance(), skippedNormalCount);
    }

    private QueueEntry getNextAdmitEntry() {
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
                for (QueueEntry e : normalQueue) if (e.state == QueueEntryState.WAITING) return e;
            } else {
                for (QueueEntry e : normalQueue) if (e.state == QueueEntryState.WAITING) return e;
                for (QueueEntry e : vipQueue) if (e.state == QueueEntryState.WAITING) return e;
            }
        } else {
            for (QueueEntry e : vipQueue) if (e.state == QueueEntryState.WAITING) return e;
            for (QueueEntry e : normalQueue) if (e.state == QueueEntryState.WAITING) return e;
        }
        return null;
    }

    public record QueueSnapshot(List<QueueEntry> staff, List<QueueEntry> priority,
                                 List<QueueEntry> vip, List<QueueEntry> normal,
                                 QueueEntry nextAdmit, boolean antiImbalance, int skippedNormalCount) {}
}
