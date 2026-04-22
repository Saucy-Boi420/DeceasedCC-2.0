package net.deceasedcraft.deceasedcc.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.deceasedcraft.deceasedcc.network.CameraSetDirectionPacket;
import net.deceasedcraft.deceasedcc.network.DeceasedNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Phase 8.3 — minimal GUI for setting a camera's yaw/pitch. Opened on
 * right-click of a Camera block with an empty hand (see
 * {@code CameraBlock.use}). Sliders + numeric text inputs for both axes,
 * Apply button fires {@link CameraSetDirectionPacket} to the server,
 * Cancel just closes.
 */
public class CameraDirectionScreen extends Screen {

    private final BlockPos cameraPos;
    private float yaw;
    private float pitch;

    private YawSlider yawSlider;
    private PitchSlider pitchSlider;
    private EditBox yawBox;
    private EditBox pitchBox;

    public CameraDirectionScreen(BlockPos cameraPos, float initialYaw, float initialPitch) {
        super(Component.literal("Camera Direction"));
        this.cameraPos = cameraPos;
        this.yaw = initialYaw;
        this.pitch = initialPitch;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;
        int w = 200;

        yawSlider = new YawSlider(cx - w / 2, cy - 60, w, 20);
        addRenderableWidget(yawSlider);
        yawBox = new EditBox(font, cx - w / 2 + 70, cy - 35, 60, 18, Component.literal("yaw"));
        yawBox.setValue(String.format("%.1f", yaw));
        yawBox.setResponder(s -> {
            try {
                float v = Float.parseFloat(s);
                this.yaw = v;
                this.yawSlider.syncFromExternal();
            } catch (NumberFormatException ignored) {}
        });
        addRenderableWidget(yawBox);

        pitchSlider = new PitchSlider(cx - w / 2, cy, w, 20);
        addRenderableWidget(pitchSlider);
        pitchBox = new EditBox(font, cx - w / 2 + 70, cy + 25, 60, 18, Component.literal("pitch"));
        pitchBox.setValue(String.format("%.1f", pitch));
        pitchBox.setResponder(s -> {
            try {
                float v = Float.parseFloat(s);
                this.pitch = Math.max(-90f, Math.min(90f, v));
                this.pitchSlider.syncFromExternal();
            } catch (NumberFormatException ignored) {}
        });
        addRenderableWidget(pitchBox);

        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> {
            DeceasedNetwork.CHANNEL.sendToServer(
                    new CameraSetDirectionPacket(cameraPos, yaw, pitch));
            onClose();
        }).bounds(cx - w / 2, cy + 60, w / 2 - 5, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(cx + 5, cy + 60, w / 2 - 5, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        int cx = width / 2;
        g.drawCenteredString(font, title, cx, height / 2 - 85, 0xFFFFFFFF);
        g.drawString(font, "Yaw  (°)",   cx - 100, height / 2 - 30, 0xAAAAAA, false);
        g.drawString(font, "Pitch (°)",  cx - 100, height / 2 + 30, 0xAAAAAA, false);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private class YawSlider extends AbstractSliderButton {
        YawSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.literal(""), (yaw + 180f) / 360f);
            updateMessage();
        }
        void syncFromExternal() {
            this.value = (((yaw + 180f) % 360f + 360f) % 360f) / 360f;
            updateMessage();
        }
        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format("Yaw: %.1f°", yaw)));
        }
        @Override
        protected void applyValue() {
            CameraDirectionScreen.this.yaw = (float) (value * 360.0 - 180.0);
            if (yawBox != null) yawBox.setValue(String.format("%.1f", CameraDirectionScreen.this.yaw));
        }
    }

    private class PitchSlider extends AbstractSliderButton {
        PitchSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.literal(""), (pitch + 90f) / 180f);
            updateMessage();
        }
        void syncFromExternal() {
            this.value = (pitch + 90f) / 180f;
            updateMessage();
        }
        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format("Pitch: %.1f°", pitch)));
        }
        @Override
        protected void applyValue() {
            CameraDirectionScreen.this.pitch = (float) (value * 180.0 - 90.0);
            if (pitchBox != null) pitchBox.setValue(String.format("%.1f", CameraDirectionScreen.this.pitch));
        }
    }
}
