package net.deceasedcraft.deceasedcc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Renders the loaded gun hovering over the turret mount.
 *
 * <p>Lighting note: the packedLight the BER dispatch hands us was sampled at
 * the slab's block position, which is dark when lighting-update ordering
 * doesn't refresh the block's cache (common when the gun is inserted via
 * GUI vs. loaded from world-save NBT). We re-sample explicitly at the air
 * block above the slab, where the gun actually sits — that way the gun's
 * brightness always matches what's around it.</p>
 */
@OnlyIn(Dist.CLIENT)
public class TurretMountBER implements BlockEntityRenderer<TurretMountBlockEntity> {
    public TurretMountBER(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(TurretMountBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buf,
                       int packedLight, int packedOverlay) {
        ItemStack weapon = be.state.weapon;
        if (weapon.isEmpty()) return;

        // Hide the gun for the local player when they're remote-controlling
        // this specific turret — otherwise the gun model blocks their view.
        if (TurretControlClient.isControllingPos(be.getBlockPos())) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        int gunLight = LevelRenderer.getLightColor(mc.level, be.getBlockPos().above());
        boolean inoperable = net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge
                .isGunInoperable(weapon, be.state.ammoSlots);

        ps.pushPose();
        if (inoperable) {
            // Lay the gun flat on its side on the slab top, nestled in
            // front of the tripod/pole so it isn't impaled by the rest.
            // Gun keeps its last yaw so the orientation still reads as
            // "this turret was facing that way when it died."
            ps.translate(0.5, 0.55, 0.5);
            ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-be.state.yawDeg + 90f));
            ps.translate(0.0, 0.0, 0.35);          // shift forward of the tripod
            ps.mulPose(com.mojang.math.Axis.XP.rotationDegrees(90f));
            ps.scale(1.0f, 1.0f, 1.0f);
        } else {
            long tick = mc.level.getGameTime();
            float yaw = be.rotationLerp.initialized
                    ? be.rotationLerp.sampleYaw(tick, partialTick) : be.state.yawDeg;
            float pitch = be.rotationLerp.initialized
                    ? be.rotationLerp.samplePitch(tick, partialTick) : be.state.pitchDeg;
            // Gun floats above the tripod's top platform (Y=14/16 = 0.875).
            ps.translate(0.5, 1.05, 0.5);
            boolean flip = net.deceasedcraft.deceasedcc.core.ModConfig.CLIENT_FLIP_GUN_RENDER.get();
            float yawRot = -yaw + 90f;
            if (flip) yawRot += 180f;
            ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yawRot));
            // The 180° Y flip also flips the gun's local Z axis, so pitch
            // (which rotates around Z) now appears inverted. Negate pitch
            // when flipped to compensate.
            float pitchRot = flip ? -pitch : pitch;
            ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(pitchRot));
            // Most TACZ gun models have their origin on the grip, putting the
            // body slightly right of the block centreline. Nudge left ~1 px
            // (gun-local X) to visually centre the barrel.
            ps.translate(-0.04, 0.0, 0.0);
            ps.scale(1.25f, 1.25f, 1.25f);
        }

        mc.getItemRenderer().renderStatic(
                weapon, ItemDisplayContext.FIXED,
                gunLight, packedOverlay,
                ps, buf, mc.level, 0);
        ps.popPose();
    }

    @Override public int getViewDistance() { return 96; }

    @Override public boolean shouldRenderOffScreen(TurretMountBlockEntity be) { return false; }
}
