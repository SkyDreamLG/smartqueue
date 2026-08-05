package org.skydream.smartqueue.queue;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
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
import org.skydream.smartqueue.network.QueuePayloads;

import java.util.*;

@EventBusSubscriber(modid = Smartqueue.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class QueueManager {

    private static QueueManager instance;

    private final List<QueueEntry> queue = new ArrayList<>();
    private final Map<UUID, QueueEntry> byUuid = new HashMap<>();
    private final Map<UUID, RejoinEntry> rejoinMap = new HashMap<>();
    private final Map<Connection, QueueEntry> byConnection = new HashMap<>();

    private boolean paused = false;
    private int vipTimer = 0;
    private int normalTimer = 0;
    private MinecraftServer server;

    private static final ThreadLocal<Boolean> ADMITTING = ThreadLocal.withInitial(() -> false);

    public static boolean isAdmitting() {
        return ADMITTING.get();
    }

    private QueueManager() {}

    public static QueueManager getInstance() {
        if (instance == null) instance = new QueueManager();
        return instance;
    }

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

    // ── Called from PlayerListMixin (placeNewPlayer HEAD) ──

    public boolean shouldQueue(GameProfile profile) {
        if (!Config.ENABLED.get()) return false;
        if (isStaff(profile)) return false;
        if (isVip(profile)) return false;
        return activeCount() >= Config.EFFECTIVE_MAX_PLAYERS.get();
    }

    public void enqueue(Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        GameProfile profile = player.getGameProfile();
        UUID uuid = profile.getId();
        long now = server.getTickCount();
        boolean staff = isStaff(profile);
        boolean vip = !staff && isVip(profile);

        // Check rejoin
        RejoinEntry rejoin = rejoinMap.remove(uuid);
        boolean isRejoin = false;
        if (rejoin != null) {
            int grace = Config.REJOIN_GRACE_TICKS.get();
            if (grace == 0 || (now - rejoin.leftAtTick) <= grace) {
                isRejoin = true;
            }
        }

        // Queue full check
        if (!isRejoin && queue.size() >= Config.MAX_QUEUE_SIZE.get()) {
            connection.disconnect(Component.translatable("smartqueue.screen.full"));
            return;
        }

        QueueEntry entry = new QueueEntry(player, connection, cookie, profile, vip, staff, now);

        // Insert by priority
        if (isRejoin && rejoin.type == RejoinType.WAS_QUEUING) {
            int pos = Math.min(rejoin.savedPosition, queue.size());
            queue.add(pos, entry);
        } else if (staff) {
            int idx = lastIndexOfType(true);
            queue.add(idx + 1, entry);
        } else if (vip || (isRejoin && rejoin.type == RejoinType.WAS_PLAYING)) {
            int idx = Math.max(lastIndexOfType(true), lastIndexOfType(false));
            queue.add(idx + 1, entry);
        } else {
            queue.add(entry);
        }

        byUuid.put(uuid, entry);
        byConnection.put(connection, entry);

        Smartqueue.LOGGER.info("Player {} queued at position {} (staff={}, vip={})",
                profile.getName(), queue.indexOf(entry) + 1, staff, vip);

        sendStatusToEntry(entry);
        broadcastStatus();
    }

    private int lastIndexOfType(boolean staff) {
        for (int i = queue.size() - 1; i >= 0; i--) {
            QueueEntry e = queue.get(i);
            if (staff && e.staff) return i;
            if (!staff && (e.staff || e.vip)) return i;
        }
        return -1;
    }

    // ── Connection disconnect (from mixin) ──

    public void onConnectionDisconnect(ServerConfigurationPacketListenerImpl listener) {
        Connection conn = listener.getConnection();
        QueueEntry entry = byConnection.remove(conn);
        if (entry != null) {
            queue.remove(entry);
            byUuid.remove(entry.profile.getId());
            long now = server.getTickCount();
            int posBeforeRemove = queue.indexOf(entry); // rough position after removal
            rejoinMap.put(entry.profile.getId(),
                    new RejoinEntry(entry.profile.getId(), RejoinType.WAS_QUEUING,
                            entry.vip, entry.staff, Math.max(0, posBeforeRemove), now));
            Smartqueue.LOGGER.info("Queued player {} disconnected (position saved for rejoin)", entry.getName());
            broadcastStatus();
        }
    }

    // ── Player logout (WAS_PLAYING rejoin tracking) ──

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (instance == null || !Config.ENABLED.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        UUID uuid = sp.getUUID();
        if (instance.byUuid.containsKey(uuid)) return; // Was queued, handled by disconnect

        boolean staff = instance.isStaff(sp.getGameProfile());
        boolean vip = !staff && instance.isVip(sp.getGameProfile());
        instance.rejoinMap.put(uuid,
                new RejoinEntry(uuid, RejoinType.WAS_PLAYING, vip, staff, 0, instance.server.getTickCount()));
        Smartqueue.LOGGER.debug("Active player {} left, rejoin entry created", sp.getGameProfile().getName());
        instance.tryAdmit();
    }

    // ── Server tick ──

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        if (instance == null) return;

        long now = event.getServer().getTickCount();
        int grace = Config.REJOIN_GRACE_TICKS.get();
        if (grace > 0) {
            instance.rejoinMap.values().removeIf(e -> (now - e.leftAtTick) > grace);
        }

        if (!Config.ENABLED.get()) {
            instance.admitAll();
            return;
        }

        if (instance.paused) return;

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
        for (QueueEntry entry : queue) {
            if (entry.state != QueueEntryState.WAITING) continue;
            if (vip && (entry.staff || entry.vip)) { admit(entry); return; }
            if (!vip && !entry.staff && !entry.vip) { admit(entry); return; }
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
            // Complete config→play transition, then place player in world
            entry.connection.send(new ClientboundFinishConfigurationPacket());
            server.getPlayerList().placeNewPlayer(entry.connection, entry.serverPlayer, entry.cookie);
        } finally {
            ADMITTING.set(false);
        }

        broadcastStatus();
    }

    private void admitAll() {
        ADMITTING.set(true);
        try {
            for (Iterator<QueueEntry> it = queue.iterator(); it.hasNext(); ) {
                QueueEntry entry = it.next();
                it.remove();
                byUuid.remove(entry.profile.getId());
                byConnection.remove(entry.connection);
                entry.state = QueueEntryState.ADMITTED;
                entry.connection.send(new ClientboundFinishConfigurationPacket());
                server.getPlayerList().placeNewPlayer(entry.connection, entry.serverPlayer, entry.cookie);
            }
        } finally {
            ADMITTING.set(false);
        }
    }

    public void leaveQueue(PlayerEvent.PlayerLoggedInEvent event) {
        // Not applicable for config-phase hold — player isn't in world yet
    }

    public void leaveQueue(ServerConfigurationPacketListenerImpl listener) {
        Connection conn = listener.getConnection();
        QueueEntry entry = byConnection.remove(conn);
        if (entry != null) {
            entry.state = QueueEntryState.LEFT;
            queue.remove(entry);
            byUuid.remove(entry.profile.getId());
            Smartqueue.LOGGER.info("Player {} left the queue voluntarily", entry.getName());

            ADMITTING.set(true);
            try {
                entry.connection.send(new ClientboundFinishConfigurationPacket());
                server.getPlayerList().placeNewPlayer(entry.connection, entry.serverPlayer, entry.cookie);
            } finally {
                ADMITTING.set(false);
            }
            broadcastStatus();
        }
    }

    private void tryAdmit() {
        if (paused || !Config.ENABLED.get()) return;
        if (activeCount() < Config.EFFECTIVE_MAX_PLAYERS.get()) {
            if (!admitFirstAvailable(true)) admitFirstAvailable(false);
        }
    }

    private boolean admitFirstAvailable(boolean vip) {
        for (QueueEntry entry : queue) {
            if (entry.state != QueueEntryState.WAITING) continue;
            if (vip && (entry.staff || entry.vip)) { admit(entry); return true; }
            if (!vip && !entry.staff && !entry.vip) { admit(entry); return true; }
        }
        return false;
    }

    // ── Status broadcasting (via raw connection for config-phase clients) ──

    private void broadcastStatus() {
        for (QueueEntry entry : queue) {
            sendStatusToEntry(entry);
        }
    }

    private void sendStatusToEntry(QueueEntry entry) {
        int position = queue.indexOf(entry) + 1;
        int total = queue.size();
        int ahead = position - 1;

        int etaSeconds = 0;
        if (ahead > 0) {
            int vipAhead = 0, normalAhead = 0;
            for (int i = 0; i < ahead && i < queue.size(); i++) {
                QueueEntry e = queue.get(i);
                if (e.staff || e.vip) vipAhead++;
                else normalAhead++;
            }
            etaSeconds = (vipAhead * Config.VIP_ADMIT_INTERVAL_TICKS.get()
                    + normalAhead * Config.NORMAL_ADMIT_INTERVAL_TICKS.get()) / 20;
        }

        QueuePayloads.QueueStatusPayload payload = new QueuePayloads.QueueStatusPayload(
                position, total, ahead, false, paused, etaSeconds);

        if (entry.connection.isConnected()) {
            entry.connection.send(new ClientboundCustomPayloadPacket(payload));
        }
    }

    // ── Server lifecycle ──

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

    // ── Public queries ──

    public int activeCount() {
        if (server == null) return 0;
        return server.getPlayerList().getPlayerCount();
    }

    public boolean isStaff(GameProfile profile) {
        String name = profile.getName();
        return Config.STAFF_LIST.get().stream().anyMatch(s -> s.equalsIgnoreCase(name));
    }

    public boolean isVip(GameProfile profile) {
        String name = profile.getName();
        return Config.VIP_LIST.get().stream().anyMatch(s -> s.equalsIgnoreCase(name));
    }

    public int queueSize() { return queue.size(); }
    public boolean isPaused() { return paused; }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused) { vipTimer = 0; normalTimer = 0; }
        broadcastStatus();
    }

    public void setEnabled(boolean enabled) {
        Config.ENABLED.set(enabled);
        if (enabled) { vipTimer = 0; normalTimer = 0; }
    }

    public List<String> getStaffList() { return List.copyOf(Config.STAFF_LIST.get()); }
    public List<String> getVipList() { return List.copyOf(Config.VIP_LIST.get()); }

    public void addStaff(String name) {
        List<String> list = new ArrayList<>(Config.STAFF_LIST.get());
        if (list.stream().noneMatch(s -> s.equalsIgnoreCase(name))) {
            list.add(name);
            Config.STAFF_LIST.set(list);
        }
    }

    public void removeStaff(String name) {
        List<String> list = new ArrayList<>(Config.STAFF_LIST.get());
        list.removeIf(s -> s.equalsIgnoreCase(name));
        Config.STAFF_LIST.set(list);
    }

    public void addVip(String name) {
        List<String> list = new ArrayList<>(Config.VIP_LIST.get());
        if (list.stream().noneMatch(s -> s.equalsIgnoreCase(name))) {
            list.add(name);
            Config.VIP_LIST.set(list);
        }
    }

    public void removeVip(String name) {
        List<String> list = new ArrayList<>(Config.VIP_LIST.get());
        list.removeIf(s -> s.equalsIgnoreCase(name));
        Config.VIP_LIST.set(list);
    }

    public List<QueueEntry> getQueueList() { return List.copyOf(queue); }
}
