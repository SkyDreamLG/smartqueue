package org.skydream.smartqueue.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.skydream.smartqueue.network.QueuePayloads;
import org.slf4j.Logger;

public final class ClientQueueState {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final SoundEvent SOUND_JOIN_QUEUE = SoundEvent.createVariableRangeEvent(
            ResourceLocation.fromNamespaceAndPath("smartqueue", "join_queue"));
    private static final SoundEvent SOUND_LEAVE_QUEUE = SoundEvent.createVariableRangeEvent(
            ResourceLocation.fromNamespaceAndPath("smartqueue", "leave_queue"));
    private static final SoundEvent SOUND_QUEUE_COMPLETED = SoundEvent.createVariableRangeEvent(
            ResourceLocation.fromNamespaceAndPath("smartqueue", "queue_completed"));

    private static final long CONNECTION_WARN_MS = 30_000;
    private static final long CONNECTION_GIVE_UP_MS = 60_000;

    private static boolean queued = false;
    private static boolean admitted = false;
    private static boolean paused = false;
    private static boolean left = false;
    private static boolean rejected = false;
    private static int position = 0;
    private static int total = 0;
    private static int ahead = 0;
    private static int etaSeconds = 0;
    private static boolean showDetail = false;
    private static boolean staffBypassQueue = false;
    private static int totalStaff = 0, totalPriority = 0, totalVip = 0, totalNormal = 0;
    private static int aheadStaff = 0, aheadPriority = 0, aheadVip = 0, aheadNormal = 0;
    private static int playerQueueOrdinal = 0;
    private static boolean blocked = false;

    private static Connection capturedConnection;
    private static long lastPacketTime;
    private static boolean connectionLost;

    private ClientQueueState() {}

    public static void captureConnection(Connection conn) {
        if (conn != null) {
            capturedConnection = conn;
        }
    }

    public static void update(QueuePayloads.QueueStatusPayload payload) {
        LOGGER.debug("update() called: admitted={}, paused={}, position={}, total={}, rejected={}, left={}",
                payload.admitted(), payload.paused(), payload.position(), payload.total(), payload.rejected(), left);

        lastPacketTime = System.currentTimeMillis();
        if (connectionLost) {
            connectionLost = false;
            LOGGER.debug("update() connection restored");
        }

        if (left) {
            LOGGER.debug("update() ignored: already left");
            return;
        }

        if (payload.rejected()) {
            LOGGER.debug("update() queue full — showing rejected screen");
            rejected = true;
            queued = false;
            ensureScreen();
            return;
        }

        if (payload.admitted()) {
            LOGGER.debug("update() admitted: clearing screen");
            queued = false;
            admitted = true;
            rejected = false;
            playSound(SOUND_QUEUE_COMPLETED);
            Minecraft.getInstance().setScreen(null);
            return;
        }

        boolean firstJoin = !queued;
        queued = true;
        admitted = false;
        rejected = false;
        paused = payload.paused();
        position = payload.position();
        total = payload.total();
        ahead = payload.ahead();
        etaSeconds = payload.etaSeconds();
        showDetail = payload.showDetail();
        staffBypassQueue = payload.staffBypassQueue();
        totalStaff = payload.totalStaff();
        totalPriority = payload.totalPriority();
        totalVip = payload.totalVip();
        totalNormal = payload.totalNormal();
        aheadStaff = payload.aheadStaff();
        aheadPriority = payload.aheadPriority();
        aheadVip = payload.aheadVip();
        aheadNormal = payload.aheadNormal();
        playerQueueOrdinal = payload.playerQueueOrdinal();
        blocked = payload.blocked();
        LOGGER.debug("update() state updated: position={}, total={}, ahead={}, eta={}, paused={}",
                position, total, ahead, etaSeconds, paused);
        if (firstJoin) playSound(SOUND_JOIN_QUEUE);
        ensureScreen();
    }

    public static void onLeave() {
        LOGGER.debug("onLeave() called, capturedConnection={}", capturedConnection);
        Minecraft mc = Minecraft.getInstance();

        Connection conn = capturedConnection;

        if (conn == null) {
            var listener = mc.getConnection();
            LOGGER.debug("onLeave() getConnection() returned: {}", listener);
            if (listener instanceof ClientCommonPacketListenerImpl impl) {
                conn = impl.getConnection();
                LOGGER.debug("onLeave() got connection from listener: {}", conn);
            }
        }

        if (conn != null && conn.isConnected()) {
            LOGGER.debug("onLeave() disconnecting");
            left = true;
            queued = false;
            admitted = false;
            rejected = false;
            playSound(SOUND_LEAVE_QUEUE);

            conn.disconnect(Component.translatable("smartqueue.screen.left"));
            mc.setScreen(new TitleScreen());
        } else {
            LOGGER.debug("onLeave() no valid connection (conn={}) — doing nothing", conn);
        }
    }

    public static void onRejectedBack() {
        LOGGER.debug("onRejectedBack() — returning to title");
        rejected = false;
        left = true;
        Minecraft mc = Minecraft.getInstance();
        if (capturedConnection != null && capturedConnection.isConnected()) {
            capturedConnection.disconnect(Component.translatable("smartqueue.screen.left"));
        }
        mc.setScreen(new TitleScreen());
    }

    public static void checkConnectionTimeout() {
        if (!queued || left) return;

        if (capturedConnection != null && !capturedConnection.isConnected()) {
            LOGGER.warn("Connection closed (channel inactive), leaving queue");
            queued = false;
            left = true;
            connectionLost = false;
            playSound(SOUND_LEAVE_QUEUE);
            Minecraft.getInstance().setScreen(new TitleScreen());
            return;
        }

        if (lastPacketTime == 0) return;

        long elapsed = System.currentTimeMillis() - lastPacketTime;

        if (elapsed > CONNECTION_GIVE_UP_MS) {
            LOGGER.warn("Giving up on connection (no status packet for {}ms)", elapsed);
            queued = false;
            left = true;
            connectionLost = false;
            playSound(SOUND_LEAVE_QUEUE);
            Minecraft mc = Minecraft.getInstance();
            if (capturedConnection != null) {
                capturedConnection.disconnect(Component.translatable("smartqueue.screen.server_lost"));
            }
            mc.setScreen(new TitleScreen());
            return;
        }

        if (elapsed > CONNECTION_WARN_MS && !connectionLost) {
            connectionLost = true;
            LOGGER.warn("Connection to server lost (no status packet for {}ms)", elapsed);
        }
    }

    public static void ensureScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (queued || rejected) {
            if (!(mc.screen instanceof QueueScreen)) {
                LOGGER.debug("ensureScreen() opening QueueScreen (queued={}, rejected={})", queued, rejected);
                mc.setScreen(new QueueScreen());
            }
        }
    }

    public static boolean isQueued() { return queued; }
    public static boolean isRejected() { return rejected; }
    public static boolean isPaused() { return paused; }
    public static boolean isConnectionLost() { return connectionLost; }
    public static int getPosition() { return position; }
    public static int getTotal() { return total; }
    public static int getAhead() { return ahead; }
    public static int getEtaSeconds() { return etaSeconds; }
    public static boolean isShowDetail() { return showDetail; }
    public static boolean isStaffBypassQueue() { return staffBypassQueue; }
    public static int getTotalStaff() { return totalStaff; }
    public static int getTotalPriority() { return totalPriority; }
    public static int getTotalVip() { return totalVip; }
    public static int getTotalNormal() { return totalNormal; }
    public static int getAheadStaff() { return aheadStaff; }
    public static int getAheadPriority() { return aheadPriority; }
    public static int getAheadVip() { return aheadVip; }
    public static int getAheadNormal() { return aheadNormal; }
    public static int getPlayerQueueOrdinal() { return playerQueueOrdinal; }
    public static boolean isBlocked() { return blocked; }

    private static void playSound(SoundEvent sound) {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(sound, 1.0f, 1.0f));
    }

    public static void reset() {
        LOGGER.debug("reset() called");
        queued = false;
        admitted = false;
        paused = false;
        left = false;
        rejected = false;
        position = 0;
        total = 0;
        ahead = 0;
        etaSeconds = 0;
        showDetail = false;
        staffBypassQueue = false;
        totalStaff = 0; totalPriority = 0; totalVip = 0; totalNormal = 0;
        aheadStaff = 0; aheadPriority = 0; aheadVip = 0; aheadNormal = 0;
        playerQueueOrdinal = 0;
        blocked = false;
        capturedConnection = null;
        lastPacketTime = 0;
        connectionLost = false;
    }
}
