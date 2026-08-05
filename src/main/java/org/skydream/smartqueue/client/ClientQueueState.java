package org.skydream.smartqueue.client;

import net.minecraft.client.Minecraft;
import org.skydream.smartqueue.network.QueuePayloads;

public final class ClientQueueState {

    private static boolean queued = false;
    private static boolean admitted = false;
    private static boolean paused = false;
    private static int position = 0;
    private static int total = 0;
    private static int ahead = 0;
    private static int etaSeconds = 0;

    private ClientQueueState() {}

    public static void update(QueuePayloads.QueueStatusPayload payload) {
        if (payload.admitted()) {
            queued = false;
            admitted = true;
            Minecraft.getInstance().setScreen(null);
            return;
        }

        queued = true;
        admitted = false;
        paused = payload.paused();
        position = payload.position();
        total = payload.total();
        ahead = payload.ahead();
        etaSeconds = payload.etaSeconds();
    }

    public static void onLeave() {
        queued = false;
        admitted = false;
        Minecraft.getInstance().setScreen(null);
    }

    public static void ensureScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (queued && mc.level != null) {
            if (!(mc.screen instanceof QueueScreen)) {
                mc.setScreen(new QueueScreen());
            }
        }
    }

    public static boolean isQueued() { return queued; }
    public static boolean isPaused() { return paused; }
    public static int getPosition() { return position; }
    public static int getTotal() { return total; }
    public static int getAhead() { return ahead; }
    public static int getEtaSeconds() { return etaSeconds; }

    public static void reset() {
        queued = false;
        admitted = false;
        paused = false;
        position = 0;
        total = 0;
        ahead = 0;
        etaSeconds = 0;
    }
}
