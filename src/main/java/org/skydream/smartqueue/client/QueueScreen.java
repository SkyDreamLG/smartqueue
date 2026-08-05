package org.skydream.smartqueue.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.skydream.smartqueue.network.QueuePayloads;

public class QueueScreen extends Screen {

    private Button leaveButton;

    public QueueScreen() {
        super(Component.translatable(
                ClientQueueState.isPaused() ? "smartqueue.screen.title_paused" : "smartqueue.screen.title"));
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int buttonY = this.height / 2 + 60;

        leaveButton = Button.builder(
                        Component.translatable("smartqueue.screen.leave"),
                        btn -> {
                            PacketDistributor.sendToServer(new QueuePayloads.QueueActionPayload(
                                    QueuePayloads.QueueAction.LEAVE_QUEUE));
                            ClientQueueState.onLeave();
                        })
                .pos(centerX - 50, buttonY)
                .size(100, 20)
                .build();
        addRenderableWidget(leaveButton);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int y = this.height / 2 - 70;

        // Title
        Component displayTitle = Component.translatable(
                ClientQueueState.isPaused() ? "smartqueue.screen.title_paused" : "smartqueue.screen.title");
        graphics.drawCenteredString(font, displayTitle, centerX, y, 0xFFFFFF);
        y += 24;

        // Position
        Component posText = Component.translatable("smartqueue.screen.position",
                ClientQueueState.getPosition(), ClientQueueState.getTotal());
        graphics.drawCenteredString(font, posText, centerX, y, 0xCCCCCC);
        y += 20;

        // Ahead count
        int ahead = ClientQueueState.getAhead();
        if (ahead == 0) {
            Component nextText = Component.translatable("smartqueue.screen.next");
            graphics.drawCenteredString(font, nextText, centerX, y, 0x55FF55);
        } else {
            Component aheadText = Component.translatable("smartqueue.screen.ahead", ahead);
            graphics.drawCenteredString(font, aheadText, centerX, y, 0xCCCCCC);
        }
        y += 20;

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
            y += 16;
        }

        y += 10;

        // Waiting message
        Component waitText = Component.translatable("smartqueue.screen.waiting");
        graphics.drawCenteredString(font, waitText, centerX, y, 0xFFFFFF);
        y += 16;

        // Paused message
        if (ClientQueueState.isPaused()) {
            Component pausedText = Component.translatable("smartqueue.screen.paused");
            graphics.drawCenteredString(font, pausedText, centerX, y, 0xFF5555);
            y += 16;
        }

        y += 10;

        // Don't close hint
        Component hintText = Component.translatable("smartqueue.screen.dont_close");
        graphics.drawCenteredString(font, hintText, centerX, y, 0x888888);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Semi-transparent dark background
        graphics.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        // Prevent closing — re-open if still queued
        if (ClientQueueState.isQueued()) {
            ClientQueueState.ensureScreen();
        }
    }
}
