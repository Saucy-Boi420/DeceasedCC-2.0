package net.deceasedcraft.deceasedcc.client;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.integration.tacz.GunClassifier;
import net.deceasedcraft.deceasedcc.items.TurretRemoteItem;
import net.deceasedcraft.deceasedcc.network.DeceasedNetwork;
import net.deceasedcraft.deceasedcc.network.TurretControlPackets;
import net.deceasedcraft.deceasedcc.turrets.GunZoomLevels;
import net.deceasedcraft.deceasedcc.turrets.TurretCameraEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side wireless-control state + Forge event hooks.
 * <p>
 * Phase D v3 design:
 * - Camera is pinned to {@code turret.y + 2} (1 block above the gun) so
 *   the player sees down over the top of the turret.
 * - Player's mouse rotates the camera naturally. Each tick we raytrace
 *   from the camera through the crosshair and find the first block/entity
 *   hit; the turret's aim is then recomputed to point the muzzle at that
 *   world point (compensating for the muzzle/camera offset).
 * - Player's body never moves. Real hand holds the remote (untouched).
 * - First-person hand rendering is suppressed during control.
 * - Left-click (press + hold) → auto-fire via Fire packets; server
 *   cooldown-gates.
 * - Right-click (press only) → cycle zoom levels. Mouse sensitivity is
 *   scaled by 1/zoom so 10× feels precise instead of jumpy.
 * - All input intercepts early-exit when any screen is open. No soft-lock.
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID, value = Dist.CLIENT)
public final class TurretControlClient {
    private TurretControlClient() {}

    // Camera sits at the SAME height the bullet spawns from (see
    // TaczBridge.placeShooter: shooter y = blockY + 1.05). By placing the
    // camera exactly there, the camera ray and the bullet ray start at the
    // same point — zero parallax, no raytrace/smoothing math needed.
    private static final double CAMERA_Y_OFFSET  = 1.05;

    private static BlockPos activeTurretPos;
    private static TurretCameraEntity cameraAnchor;
    private static float savedYRot, savedXRot;
    private static float[] zoomLevels = new float[0];
    private static int zoomIndex = 0;
    private static boolean shiftWasDown = false;
    private static boolean leftDown = false;
    private static float lastSentYaw = Float.NaN, lastSentPitch = Float.NaN;
    private static double savedSensitivity = Double.NaN;

    public static boolean isControlling() { return activeTurretPos != null; }

    /** True iff the local client is currently remote-controlling the turret
     *  at this exact position. Used by BERs to hide the gun for the
     *  controlling player so the model doesn't obstruct their view. */
    public static boolean isControllingPos(BlockPos pos) {
        return activeTurretPos != null && activeTurretPos.equals(pos);
    }

    // ================================================== Enter / exit paths

    public static void onConfirm(BlockPos pos, int gunClassOrdinal) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        if (isControlling()) detachCamera();

        activeTurretPos = pos;
        savedYRot = player.getYRot();
        savedXRot = player.getXRot();
        lastSentYaw = Float.NaN;
        lastSentPitch = Float.NaN;
        zoomIndex = 0;
        leftDown = false;

        GunClassifier.GunClass[] classes = GunClassifier.GunClass.values();
        GunClassifier.GunClass cls = (gunClassOrdinal >= 0 && gunClassOrdinal < classes.length)
                ? classes[gunClassOrdinal] : GunClassifier.GunClass.UNKNOWN;
        zoomLevels = GunZoomLevels.forClass(cls);

        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + CAMERA_Y_OFFSET;
        double cz = pos.getZ() + 0.5;
        cameraAnchor = new TurretCameraEntity(mc.level, cx, cy, cz);
        cameraAnchor.aimAt(cx, cy, cz, player.getYRot(), player.getXRot());
        mc.setCameraEntity(cameraAnchor);
    }

    public static void onForceExit(String reason) {
        detachCamera();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && reason != null && !reason.isEmpty()) {
            mc.player.displayClientMessage(
                    Component.literal(reason).withStyle(ChatFormatting.RED), true);
        }
    }

    public static void release() {
        if (!isControlling()) return;
        DeceasedNetwork.CHANNEL.sendToServer(new TurretControlPackets.Release());
        detachCamera();
    }

    private static void detachCamera() {
        Minecraft mc = Minecraft.getInstance();
        restoreSensitivity();
        if (cameraAnchor != null) {
            cameraAnchor.discard();
            cameraAnchor = null;
        }
        if (mc.player != null) {
            mc.player.setYRot(savedYRot);
            mc.player.setXRot(savedXRot);
            mc.setCameraEntity(mc.player);
        }
        activeTurretPos = null;
        shiftWasDown = false;
        leftDown = false;
        zoomLevels = new float[0];
        zoomIndex = 0;
    }

    // ================================================== Per-tick updates

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END) return;
        if (!isControlling()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || cameraAnchor == null || mc.level == null) {
            detachCamera();
            return;
        }

        BlockPos pos = activeTurretPos;
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + CAMERA_Y_OFFSET;
        double cz = pos.getZ() + 0.5;
        cameraAnchor.aimAt(cx, cy, cz, player.getYRot(), player.getXRot());

        // Option A: camera at the bullet spawn point → mirror the player's
        // yaw/pitch directly onto the turret. Camera ray == bullet ray, so
        // the crosshair is always EXACTLY where the bullet goes. No raytrace,
        // no target-point math, no smoothing. If you aim at a head, the
        // bullet hits the head; if you aim past the head, it misses.
        float turretYaw = ((player.getYRot() % 360f) + 360f) % 360f;
        float turretPitch = Math.max(-90f, Math.min(50f, player.getXRot()));

        if (Float.isNaN(lastSentYaw)
                || Math.abs(turretYaw - lastSentYaw) > 0.05f
                || Math.abs(turretPitch - lastSentPitch) > 0.05f) {
            DeceasedNetwork.CHANNEL.sendToServer(new TurretControlPackets.Aim(turretYaw, turretPitch));
            lastSentYaw = turretYaw;
            lastSentPitch = turretPitch;
        }

        // Left-click hold → auto-fire. Server applies RPM cooldown.
        if (leftDown) {
            DeceasedNetwork.CHANNEL.sendToServer(new TurretControlPackets.Fire());
        }

        // Shift edge-trigger = exit. Not while a screen is open.
        boolean shiftNow = mc.options.keyShift.isDown() && mc.screen == null;
        if (shiftNow && !shiftWasDown) {
            release();
            return;
        }
        shiftWasDown = shiftNow;

        // Refresh mouse sensitivity scaling each tick in case zoom changed.
        applySensitivityForZoom();
    }

    // ================================================== Mouse sensitivity

    private static void applySensitivityForZoom() {
        Minecraft mc = Minecraft.getInstance();
        if (zoomIndex <= 0 || zoomIndex > zoomLevels.length) {
            restoreSensitivity();
            return;
        }
        float zoom = zoomLevels[zoomIndex - 1];
        double desired = getSavedOrCurrentSensitivity() / zoom;
        if (Math.abs(mc.options.sensitivity().get() - desired) > 0.0001) {
            mc.options.sensitivity().set(desired);
        }
    }

    private static double getSavedOrCurrentSensitivity() {
        if (Double.isNaN(savedSensitivity)) {
            savedSensitivity = Minecraft.getInstance().options.sensitivity().get();
        }
        return savedSensitivity;
    }

    private static void restoreSensitivity() {
        Minecraft mc = Minecraft.getInstance();
        if (!Double.isNaN(savedSensitivity)) {
            mc.options.sensitivity().set(savedSensitivity);
            savedSensitivity = Double.NaN;
        }
    }

    // ================================================== Input intercepts

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMouseButton(InputEvent.MouseButton.Pre evt) {
        if (!isControlling()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;

        int button = evt.getButton();
        int action = evt.getAction();
        if (button == 0 /* LEFT */) {
            if (action == 1)      leftDown = true;   // press
            else if (action == 0) leftDown = false;  // release
            if (action == 1) {
                // Send one Fire packet on press (the per-tick loop will continue
                // sending while held, but firing on press feels more responsive).
                DeceasedNetwork.CHANNEL.sendToServer(new TurretControlPackets.Fire());
            }
            evt.setCanceled(true);
        } else if (button == 1 /* RIGHT */) {
            if (action == 1 /* PRESS */) {
                cycleZoom();
                applySensitivityForZoom();
            }
            evt.setCanceled(true);
        }
    }

    private static void cycleZoom() {
        int cycleLen = zoomLevels.length + 1;
        zoomIndex = (zoomIndex + 1) % cycleLen;
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov evt) {
        if (!isControlling() || zoomIndex <= 0) return;
        int idx = zoomIndex - 1;
        if (idx < 0 || idx >= zoomLevels.length) return;
        evt.setFOV(evt.getFOV() / zoomLevels[idx]);
    }

    /** Suppress the first-person hand render during control. The player is
     *  still holding the remote in their real hand — we're just hiding the
     *  render since the camera isn't really at their body. */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent evt) {
        if (isControlling()) evt.setCanceled(true);
    }

    // ================================================== HUD

    @SubscribeEvent
    public static void onHudRender(RenderGuiEvent.Post evt) {
        if (!isControlling()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;

        int w = evt.getWindow().getGuiScaledWidth();
        int h = evt.getWindow().getGuiScaledHeight();
        var gui = evt.getGuiGraphics();

        // Bottom overlay: zoom indicator + exit hint
        String zoomLabel = zoomIndex > 0 && zoomIndex <= zoomLevels.length
                ? "Zoom " + GunZoomLevels.labelFor(zoomLevels[zoomIndex - 1])
                : "";
        if (!zoomLabel.isEmpty()) {
            gui.drawCenteredString(mc.font,
                    Component.literal(zoomLabel).withStyle(ChatFormatting.AQUA),
                    w / 2, h - 50, 0xFFFFFFFF);
        }
        gui.drawCenteredString(mc.font,
                Component.literal("SHIFT to exit · Right-click to zoom")
                         .withStyle(ChatFormatting.GRAY),
                w / 2, h - 38, 0xFFFFFFFF);

        // Battery bar — show the remote's current FE under the hotbar
        if (mc.player != null) {
            ItemStack remote = findRemote(mc.player);
            if (!remote.isEmpty()) {
                int stored = TurretRemoteItem.getStoredEnergy(remote);
                int max    = TurretRemoteItem.getMaxEnergy(remote);
                int barW = 80, barH = 6;
                int barX = (w - barW) / 2;
                int barY = h - 60;
                gui.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF202020);
                gui.fill(barX, barY, barX + barW, barY + barH, 0xFF404040);
                int fill = max > 0 ? (int) ((long) barW * stored / max) : 0;
                if (fill > 0) {
                    gui.fill(barX, barY, barX + fill, barY + barH, 0xFF33CC33);
                    gui.fill(barX, barY, barX + fill, barY + 1, 0xFF77FF77);
                }
            }
        }
    }

    private static ItemStack findRemote(net.minecraft.world.entity.player.Player p) {
        for (InteractionHand h : InteractionHand.values()) {
            ItemStack s = p.getItemInHand(h);
            if (s.getItem() instanceof TurretRemoteItem) return s;
        }
        return ItemStack.EMPTY;
    }
}
