package net.deceasedcraft.deceasedcc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.deceasedcraft.deceasedcc.blocks.entity.BasicTurretBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Same lighting fix as {@link TurretMountBER}. */
@OnlyIn(Dist.CLIENT)
public class BasicTurretBER implements BlockEntityRenderer<BasicTurretBlockEntity> {
    public BasicTurretBER(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(BasicTurretBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buf,
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
            ps.translate(0.5, 0.55, 0.5);
            ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-be.state.yawDeg + 90f));
            ps.translate(0.0, 0.0, 0.35);
            ps.mulPose(com.mojang.math.Axis.XP.rotationDegrees(90f));
            ps.scale(1.0f, 1.0f, 1.0f);
        } else {
            long tick = mc.level.getGameTime();
            float yaw = be.rotationLerp.initialized
                    ? be.rotationLerp.sampleYaw(tick, partialTick) : be.state.yawDeg;
            float pitch = be.rotationLerp.initialized
                    ? be.rotationLerp.samplePitch(tick, partialTick) : be.state.pitchDeg;
            // Gun sits just above the cradle cap (Y=14/16 = 0.875).
            ps.translate(0.5, 1.05, 0.5);
            boolean flip = net.deceasedcraft.deceasedcc.core.ModConfig.CLIENT_FLIP_GUN_RENDER.get();
            float yawRot = -yaw + 90f;
            if (flip) yawRot += 180f;
            ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yawRot));
            // Y flip also inverts the gun's local Z axis → pitch appears
            // inverted. Negate pitch in the flipped case to compensate.
            float pitchRot = flip ? -pitch : pitch;
            ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(pitchRot));
            // See TurretMountBER — nudge left ~1 px to centre the barrel
            // over the block since TACZ models put their origin on the grip.
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
    @Override public boolean shouldRenderOffScreen(BasicTurretBlockEntity be) { return false; }
}
