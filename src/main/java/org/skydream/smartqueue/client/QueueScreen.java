package org.skydream.smartqueue.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public class QueueScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private Button leaveButton;

    public QueueScreen() {
        super(Component.translatable(
                ClientQueueState.isRejected() ? "smartqueue.screen.title_rejected"
                        : ClientQueueState.isPaused() ? "smartqueue.screen.title_paused"
                        : "smartqueue.screen.title"));
    }

    @Override
    protected void init() {
        LOGGER.debug("QueueScreen.init() rejected={}", ClientQueueState.isRejected());
        super.init();
        int centerX = this.width / 2;
        int buttonY = this.height / 2 + 72;

        if (ClientQueueState.isRejected()) {
            leaveButton = Button.builder(
                            Component.translatable("smartqueue.screen.back"),
                            btn -> {
                                LOGGER.debug("QueueScreen: back button clicked (rejected)");
                                ClientQueueState.onRejectedBack();
                            })
                    .pos(centerX - 50, buttonY)
                    .size(100, 20)
                    .build();
        } else {
            leaveButton = Button.builder(
                            Component.translatable("smartqueue.screen.leave"),
                            btn -> {
                                LOGGER.debug("QueueScreen: leave button clicked");
                                ClientQueueState.onLeave();
                            })
                    .pos(centerX - 50, buttonY)
                    .size(100, 20)
                    .build();
        }
        addRenderableWidget(leaveButton);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int y = this.height / 2 - 82;

        // Rejected screen
        if (ClientQueueState.isRejected()) {
            Component title = Component.translatable("smartqueue.screen.title_rejected");
            graphics.drawCenteredString(font, title, centerX, y, 0xFF5555);
            y += 36;

            Component msg = Component.translatable("smartqueue.screen.rejected_msg");
            graphics.drawCenteredString(font, msg, centerX, y, 0xCCCCCC);
            y += 20;

            Component hint = Component.translatable("smartqueue.screen.rejected_hint");
            graphics.drawCenteredString(font, hint, centerX, y, 0x888888);

            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        // Title
        Component displayTitle = Component.translatable(
                ClientQueueState.isPaused() ? "smartqueue.screen.title_paused" : "smartqueue.screen.title");
        graphics.drawCenteredString(font, displayTitle, centerX, y, 0xFFFFFF);
        y += 14;

        // Position
        Component posText = Component.translatable("smartqueue.screen.position",
                ClientQueueState.getPosition(), ClientQueueState.getTotal());
        graphics.drawCenteredString(font, posText, centerX, y, 0xCCCCCC);
        y += 12;

        // Queue detail
        if (ClientQueueState.isShowDetail()) {
            y += 4;
            Component header = Component.translatable("smartqueue.screen.queue_detail_header");
            graphics.drawCenteredString(font, header, centerX, y, 0xAAAAAA);
            y += 10;

            int pq = ClientQueueState.getPlayerQueueOrdinal();
            int dim = 0x888888;
            int hl = 0x55FF55;

            if (!ClientQueueState.isStaffBypassQueue()) {
                Component staff = Component.translatable("smartqueue.screen.queue_detail_staff",
                        ClientQueueState.getTotalStaff(), ClientQueueState.getAheadStaff());
                graphics.drawCenteredString(font, staff, centerX, y, pq == 0 ? hl : dim);
                y += 10;
            }

            Component prio = Component.translatable("smartqueue.screen.queue_detail_priority",
                    ClientQueueState.getTotalPriority(), ClientQueueState.getAheadPriority());
            graphics.drawCenteredString(font, prio, centerX, y, pq == 1 ? hl : dim);
            y += 10;

            Component vip = Component.translatable("smartqueue.screen.queue_detail_vip",
                    ClientQueueState.getTotalVip(), ClientQueueState.getAheadVip());
            graphics.drawCenteredString(font, vip, centerX, y, pq == 2 ? hl : dim);
            y += 10;

            Component normal = Component.translatable("smartqueue.screen.queue_detail_normal",
                    ClientQueueState.getTotalNormal(), ClientQueueState.getAheadNormal());
            graphics.drawCenteredString(font, normal, centerX, y, pq == 3 ? hl : dim);
            y += 12;
        }

        // Ahead count
        int ahead = ClientQueueState.getAhead();
        if (ahead == 0) {
            if (ClientQueueState.isBlocked()) {
                Component blockedText = Component.translatable("smartqueue.screen.blocked");
                graphics.drawCenteredString(font, blockedText, centerX, y, 0xFFAA00);
            } else {
                Component nextText = Component.translatable("smartqueue.screen.next");
                graphics.drawCenteredString(font, nextText, centerX, y, 0x55FF55);
            }
        } else {
            Component aheadText = Component.translatable("smartqueue.screen.ahead", ahead);
            graphics.drawCenteredString(font, aheadText, centerX, y, 0xCCCCCC);
        }
        y += 12;

        // ETA
        int eta = ClientQueueState.getEtaSeconds();
        if (eta > 0) {
            String etaStr;
            if (eta >= 60) {
                etaStr = (eta / 60) + "m " + (eta % 60) + "s";
            } else {
                etaStr = eta + "s";
            }
            graphics.drawCenteredString(font, "ETA: " + etaStr, centerX, y, 0xAAAAAA);
            y += 10;
        }

        y += 6;

        // Waiting message
        Component waitText = Component.translatable("smartqueue.screen.waiting");
        graphics.drawCenteredString(font, waitText, centerX, y, 0xFFFFFF);
        y += 10;

        // Paused message
        if (ClientQueueState.isPaused()) {
            Component pausedText = Component.translatable("smartqueue.screen.paused");
            graphics.drawCenteredString(font, pausedText, centerX, y, 0xFF5555);
            y += 10;
        }

        // Connection lost warning
        if (ClientQueueState.isConnectionLost()) {
            Component lostText = Component.translatable("smartqueue.screen.connection_lost");
            graphics.drawCenteredString(font, lostText, centerX, y, 0xFFAA00);
            y += 10;
        }

        y += 6;

        // Don't close hint
        Component hintText = Component.translatable("smartqueue.screen.dont_close");
        graphics.drawCenteredString(font, hintText, centerX, y, 0x888888);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        LOGGER.debug("QueueScreen.onClose() isQueued={}, isRejected={}",
                ClientQueueState.isQueued(), ClientQueueState.isRejected());
        if (ClientQueueState.isRejected()) {
            ClientQueueState.onRejectedBack();
        } else if (ClientQueueState.isQueued()) {
            ClientQueueState.ensureScreen();
        }
    }
}
