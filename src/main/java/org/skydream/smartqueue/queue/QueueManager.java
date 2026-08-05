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
    private final List<QueueEntry> queue = new ArrayList<>();
    private final Map<UUID, QueueEntry> byUuid = new HashMap<>();
    private final Map<UUID, RejoinEntry> rejoinMap = new HashMap<>();
    private final Map<Connection, QueueEntry> byConnection = new HashMap<>();
    private final Map<Connection, Integer> rejectedConnections = new HashMap<>();
    private boolean paused;
    private int vipTimer, normalTimer, keepAliveTimer;
    private MinecraftServer server;

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
            instance.queue.clear();
            instance.byUuid.clear();
            instance.rejoinMap.clear();
            instance.byConnection.clear();
            instance.paused = false;
            instance.vipTimer = 0;
            instance.normalTimer = 0;
        }
        instance = null;
    }

    // ── Called from mixin (placeNewPlayer HEAD) ──

    public boolean shouldQueue(GameProfile profile) {
        if (!Config.ENABLED.get()) return false;
        if (Config.STAFF_BYPASS_QUEUE.get() && isStaff(profile)) return false;
        return activeCount() >= Config.EFFECTIVE_MAX_PLAYERS.get();
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
            queue.remove(old);
            byConnection.remove(old.connection);
        }

        RejoinEntry rejoin = rejoinMap.remove(uuid);
        boolean isRejoin = false;
        if (rejoin != null) {
            int grace = Config.REJOIN_GRACE_TICKS.get();
            if (grace == 0 || (now - rejoin.leftAtTick) <= grace) isRejoin = true;
        }

        if (!isRejoin && queue.size() >= Config.MAX_QUEUE_SIZE.get()) {
            connection.send(new ClientboundCustomPayloadPacket(
                    new QueuePayloads.QueueStatusPayload(0, 0, 0, false, false, 0, true)));
            rejectedConnections.put(connection, server.getTickCount());
            Smartqueue.LOGGER.info("Rejected {} — queue is full", profile.getName());
            return;
        }

        QueueEntry entry = new QueueEntry(player, connection, cookie, profile, vip, staff, now);

        if (isRejoin && rejoin.type == RejoinType.WAS_QUEUING) {
            int pos = Math.min(rejoin.savedPosition, queue.size());
            queue.add(pos, entry);
        } else if (isRejoin && rejoin.type == RejoinType.WAS_PLAYING) {
            int idx = Math.max(lastOfType(true), lastOfType(false));
            queue.add(idx + 1, entry);
        } else if (staff) {
            int idx = lastOfType(true);
            queue.add(idx + 1, entry);
        } else if (vip) {
            int idx = Math.max(lastOfType(true), lastOfType(false));
            queue.add(idx + 1, entry);
        } else {
            queue.add(entry);
        }
        byUuid.put(uuid, entry);
        byConnection.put(connection, entry);

        Smartqueue.LOGGER.info("Player {} queued at pos {} (staff={}, vip={})",
                profile.getName(), queue.indexOf(entry) + 1, staff, vip);
        sendStatus(entry);
        broadcastPositions();
    }

    private int lastOfType(boolean staff) {
        for (int i = queue.size() - 1; i >= 0; i--) {
            QueueEntry e = queue.get(i);
            if (staff && e.staff) return i;
            if (!staff && (e.staff || e.vip)) return i;
        }
        return -1;
    }

    // ── Disconnect ──

    private void saveRejoinForEntry(QueueEntry entry) {
        int pos = queue.indexOf(entry);
        rejoinMap.put(entry.profile.getId(),
                new RejoinEntry(entry.profile.getId(), RejoinType.WAS_QUEUING,
                        entry.vip, entry.staff, pos, server.getTickCount()));
        Smartqueue.LOGGER.debug("saveRejoinForEntry: {} pos={}", entry.getName(), pos);
    }

    public void onConnectionDisconnect(ServerConfigurationPacketListenerImpl listener) {
        Connection conn = listener.getConnection();
        Smartqueue.LOGGER.debug("onConnectionDisconnect: conn={}, queued={}", conn, byConnection.containsKey(conn));
        QueueEntry entry = byConnection.get(conn);
        if (entry != null) {
            saveRejoinForEntry(entry);
            byConnection.remove(conn);
            queue.remove(entry);
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
            if (now - e.getValue() > 100) { // 5-second grace period for client to show the message
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
                queue.remove(entry);
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
        instance.rejoinMap.put(uuid,
                new RejoinEntry(uuid, RejoinType.WAS_PLAYING, vip, staff, 0, instance.server.getTickCount()));
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

        // Periodically broadcast status to update queue positions on clients
        instance.keepAliveTimer++;
        if (instance.keepAliveTimer >= KEEPALIVE_INTERVAL) {
            instance.keepAliveTimer = 0;
            if (!instance.queue.isEmpty()) instance.broadcastPositions();
        }

        if (instance.paused) return;

        // Safety net: fill any open slot
        if (!instance.queue.isEmpty() && instance.activeCount() < Config.EFFECTIVE_MAX_PLAYERS.get()) {
            if (instance.tryAdmit()) {
                instance.vipTimer = 0;
                instance.normalTimer = 0;
            }
        }

        instance.vipTimer++;
        if (instance.vipTimer >= Config.VIP_ADMIT_INTERVAL_TICKS.get()) {
            instance.vipTimer = 0;
            instance.admitNext(true);
        }
        instance.normalTimer++;
        if (instance.normalTimer >= Config.NORMAL_ADMIT_INTERVAL_TICKS.get()) {
            instance.normalTimer = 0;
            instance.admitNext(false);
        }
    }

    private void admitNext(boolean vip) {
        if (activeCount() >= Config.EFFECTIVE_MAX_PLAYERS.get()) return;
        for (QueueEntry e : queue) {
            if (e.state != QueueEntryState.WAITING) continue;
            if (vip && (e.staff || e.vip)) { admit(e); return; }
            if (!vip && !e.staff && !e.vip) { admit(e); return; }
        }
    }

    private void admit(QueueEntry entry) {
        entry.state = QueueEntryState.ADMITTED;
        queue.remove(entry);
        byUuid.remove(entry.profile.getId());
        byConnection.remove(entry.connection);
        Smartqueue.LOGGER.info("Admitting player {} from queue", entry.getName());
        ADMITTING.set(true);
        try {
            server.getPlayerList().placeNewPlayer(entry.connection, entry.serverPlayer, entry.cookie);
        } finally {
            ADMITTING.set(false);
        }
        entry.connection.send(new ClientboundCustomPayloadPacket(
                new QueuePayloads.QueueStatusPayload(0, queue.size(), 0, true, paused, 0, false)));
        broadcastPositions();
    }

    private void kickAllQueued() {
        if (queue.isEmpty()) return;
        var snapshot = new ArrayList<>(queue);
        queue.clear();
        for (QueueEntry e : snapshot) {
            byUuid.remove(e.profile.getId());
            byConnection.remove(e.connection);
            e.connection.disconnect(Component.translatable("smartqueue.command.disabled"));
        }
        Smartqueue.LOGGER.info("Kicked {} queued players (queue disabled)", snapshot.size());
    }


    public void leaveQueue(ServerConfigurationPacketListenerImpl listener) {
        Connection conn = listener.getConnection();
        Smartqueue.LOGGER.debug("leaveQueue: conn={}, queued={}", conn, byConnection.containsKey(conn));
        QueueEntry entry = byConnection.get(conn);
        if (entry != null) {
            saveRejoinForEntry(entry);
            entry.state = QueueEntryState.LEFT;
            byConnection.remove(conn);
            queue.remove(entry);
            byUuid.remove(entry.profile.getId());
            Smartqueue.LOGGER.info("Player {} left the queue", entry.getName());
            conn.disconnect(Component.translatable("smartqueue.screen.left"));
            broadcastPositions();
        }
    }

    private boolean tryAdmit() {
        if (paused || !Config.ENABLED.get()) return false;
        if (activeCount() < Config.EFFECTIVE_MAX_PLAYERS.get()) {
            if (admitFirstAvailable(true)) return true;
            return admitFirstAvailable(false);
        }
        return false;
    }

    private boolean admitFirstAvailable(boolean vip) {
        for (QueueEntry e : queue) {
            if (e.state != QueueEntryState.WAITING) continue;
            if (vip && (e.staff || e.vip)) { admit(e); return true; }
            if (!vip && !e.staff && !e.vip) { admit(e); return true; }
        }
        return false;
    }

    // ── Status ──

    private void broadcastPositions() {
        for (QueueEntry e : queue) sendStatus(e);
    }

    private void sendStatus(QueueEntry entry) {
        int position = queue.indexOf(entry) + 1;
        int total = queue.size();
        int ahead = position - 1;
        int eta = 0;
        if (ahead > 0) {
            int v = 0, n = 0;
            for (int i = 0; i < ahead && i < queue.size(); i++) {
                if (queue.get(i).staff || queue.get(i).vip) v++; else n++;
            }
            eta = (v * Config.VIP_ADMIT_INTERVAL_TICKS.get() + n * Config.NORMAL_ADMIT_INTERVAL_TICKS.get()) / 20;
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

    public int queueSize() { return queue.size(); }
    public boolean isPaused() { return paused; }
    public void setPaused(boolean v) { paused = v; if (!v) { vipTimer = 0; normalTimer = 0; } broadcastPositions(); }
    public void setEnabled(boolean v) {
        Config.ENABLED.set(v);
        if (!v) {
            kickAllQueued();
        }
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

    public List<QueueEntry> getQueueList() { return List.copyOf(queue); }
}
