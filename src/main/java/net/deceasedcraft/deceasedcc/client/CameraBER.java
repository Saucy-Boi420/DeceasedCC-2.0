package net.deceasedcraft.deceasedcc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.blocks.CameraBlock;
import net.deceasedcraft.deceasedcc.blocks.entity.CameraBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraftforge.client.model.data.ModelData;

import java.util.List;

/**
 * Phase 6c.1 + 8.1 — dynamic camera head renderer. Reads the BE's yaw/pitch
 * and rotates a baked head model around the mount's pivot.
 *
 * <p>Per-face pivot + model:
 * <ul>
 *   <li>FLOOR    → pivot at top of floor mount neck (0.5, 5/16, 0.5),
 *                  model = {@code camera_head} (body extends +Y from origin)</li>
 *   <li>CEILING  → pivot at bottom of ceiling mount neck (0.5, 11/16, 0.5),
 *                  model = {@code camera_head_ceiling} (body extends -Y)</li>
 *   <li>WALL     → pivot at arm end, facing-dependent X/Z (8/16 fixed Y),
 *                  model = {@code camera_head} (body +Y, looks natural for
 *                  a short arm-mounted cam)</li>
 * </ul>
 *
 * <p>Client-side smoothing: first render snaps the displayed angles to the
 * authoritative values so the head doesn't spin in from zero. Subsequent
 * renders lerp displayed → authoritative by a fixed fraction per frame.
 */
public class CameraBER implements BlockEntityRenderer<CameraBlockEntity> {

    /** Floor + wall mount head model. Body extends +Y from origin.
     *  {@code assets/deceasedcc/models/block/camera_head.json} */
    public static final ResourceLocation HEAD_MODEL_LOC =
            new ResourceLocation("deceasedcc", "block/camera_head");

    /** Ceiling mount head model. Body extends -Y from origin so the head
     *  hangs below the ceiling pivot.
     *  {@code assets/deceasedcc/models/block/camera_head_ceiling.json} */
    public static final ResourceLocation HEAD_CEILING_MODEL_LOC =
            new ResourceLocation("deceasedcc", "block/camera_head_ceiling");

    /** Per-frame lerp factor; matches the hologram-transform smoothing feel. */
    private static final float LERP_F = 0.25f;

    /** One-shot debug flag — logs the first render failure. */
    private static boolean loggedMissing = false;

    public CameraBER(BlockEntityRendererProvider.Context ignored) {}

    @Override
    public void render(CameraBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        AttachFace face = state.getValue(CameraBlock.FACE);

        // Pick model + pivot based on attach face (and horizontal facing for WALL).
        double px, py, pz;
        ResourceLocation modelLoc;
        switch (face) {
            case FLOOR -> {
                px = 0.5; py = 5.0 / 16.0; pz = 0.5;
                modelLoc = HEAD_MODEL_LOC;
            }
            case CEILING -> {
                px = 0.5; py = 11.0 / 16.0; pz = 0.5;
                modelLoc = HEAD_CEILING_MODEL_LOC;
            }
            case WALL -> {
                modelLoc = HEAD_MODEL_LOC;
                Direction facing = state.getValue(CameraBlock.FACING);
                switch (facing) {
                    case NORTH -> { px = 0.5;        py = 0.5; pz = 11.0 / 16.0; }
                    case SOUTH -> { px = 0.5;        py = 0.5; pz =  5.0 / 16.0; }
                    case EAST  -> { px = 11.0 / 16.0; py = 0.5; pz = 0.5; }
                    case WEST  -> { px =  5.0 / 16.0; py = 0.5; pz = 0.5; }
                    default    -> { return; }
                }
            }
            default -> { return; }
        }

        // Lerp displayed yaw/pitch toward authoritative. First render snaps so
        // the head doesn't spin in from 0° after placement.
        if (!be.displayInit) {
            be.displayYaw   = be.getYawDeg();
            be.displayPitch = be.getPitchDeg();
            be.displayInit  = true;
        } else {
            be.displayYaw   = lerpAngle(be.displayYaw,   be.getYawDeg(),   LERP_F);
            be.displayPitch = lerpAngle(be.displayPitch, be.getPitchDeg(), LERP_F);
        }

        pose.pushPose();
        // Translate origin to the pivot point in block-local coords.
        pose.translate(px, py, pz);
        // Yaw around world Y, then pitch around the yaw-rotated local X. Negate
        // yaw because MC yaw counts clockwise from above while PoseStack's
        // right-hand Y rotation counts counter-clockwise.
        pose.mulPose(Axis.YP.rotationDegrees(-be.displayYaw));
        pose.mulPose(Axis.XP.rotationDegrees(be.displayPitch));
        // Re-centre the head on the pivot: head model body is centered at
        // (8, _, 8) in 16-unit model coords, i.e. (0.5, _, 0.5) in block coords.
        // Shift by (-0.5, 0, -0.5) so model-local (8, 0, 8) ends up at pivot.
        pose.translate(-0.5, 0, -0.5);

        BakedModel headModel = Minecraft.getInstance().getModelManager().getModel(modelLoc);
        if (headModel == Minecraft.getInstance().getModelManager().getMissingModel()) {
            if (!loggedMissing) {
                DeceasedCC.LOGGER.warn("CameraBER: head model not loaded at {} — "
                        + "ModelEvent.RegisterAdditional wasn't called or the path is wrong",
                        modelLoc);
                loggedMissing = true;
            }
            pose.popPose();
            return;
        }

        // Emit every quad directly into the cutout buffer.
        RandomSource random = RandomSource.create(be.getBlockPos().asLong());
        ModelData data = ModelData.EMPTY;
        VertexConsumer vc = buffers.getBuffer(RenderType.cutout());
        for (Direction dir : Direction.values()) {
            List<BakedQuad> quads = headModel.getQuads(state, dir, random, data, null);
            for (BakedQuad q : quads) {
                vc.putBulkData(pose.last(), q, 1f, 1f, 1f, light, overlay);
            }
        }
        List<BakedQuad> general = headModel.getQuads(state, null, random, data, null);
        for (BakedQuad q : general) {
            vc.putBulkData(pose.last(), q, 1f, 1f, 1f, light, overlay);
        }

        pose.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(CameraBlockEntity be) {
        return false;
    }

    /** Shortest-path angular lerp, handles 358° → 5° taking the +7° route. */
    private static float lerpAngle(float current, float target, float f) {
        float delta = ((target - current) % 360f + 540f) % 360f - 180f;
        if (Math.abs(delta) < 0.1f) return target;
        return current + delta * f;
    }
}
