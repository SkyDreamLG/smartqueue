package org.skydream.smartqueue.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.skydream.smartqueue.network.QueuePayloads;
import org.slf4j.Logger;

public final class ClientQueueState {

    private static final Logger LOGGER = LogUtils.getLogger();

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
            Minecraft.getInstance().setScreen(null);
            return;
        }

        queued = true;
        admitted = false;
        rejected = false;
        paused = payload.paused();
        position = payload.position();
        total = payload.total();
        ahead = payload.ahead();
        etaSeconds = payload.etaSeconds();
        LOGGER.debug("update() state updated: position={}, total={}, ahead={}, eta={}, paused={}",
                position, total, ahead, etaSeconds, paused);
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
        capturedConnection = null;
        lastPacketTime = 0;
        connectionLost = false;
    }
}
