package net.deceasedcraft.deceasedcc.client.camera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;
import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.mixin.camera.CameraAccessor;
import net.deceasedcraft.deceasedcc.mixin.camera.LevelRendererAccessor;
import net.deceasedcraft.deceasedcc.mixin.camera.MinecraftAccessor;
import net.deceasedcraft.deceasedcc.util.FrustumScanner;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Marker;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;

/**
 * Phase 8.2 production render-to-FBO. Captures a single world frame
 * viewed from {@code camPos} looking along {@code yaw/pitch} into the
 * provided {@link TextureTarget}.
 *
 * <p>Implementation is the "SecurityCraft FrameFeedHandler" pattern —
 * save all relevant render state, swap Minecraft's main render target +
 * cameraEntity + various overlay toggles, invoke
 * {@code gameRenderer.renderLevel}, then restore everything. See
 * {@code v2.0-stages.txt} Phase 8.2 section for attribution + full
 * rationale.
 *
 * <p>Must be called on the client render thread during an active render
 * frame — it leverages live GL context state.
 */
public final class CameraFeedRenderer {
    private CameraFeedRenderer() {}

    /** Set while a capture is in progress. Handlers subscribed to
     *  {@code RenderLevelStageEvent} can check this to guard against
     *  infinite recursion when our invoked renderLevel fires nested
     *  events. */
    private static volatile boolean currentlyCapturing = false;

    /** Iris/Oculus injects a {@code WorldRenderingPipeline} field on
     *  {@code LevelRenderer} via mixin. Re-invoking
     *  {@code gameRenderer.renderLevel} during the outer frame tears down
     *  that pipeline, so when the outer terrain pass later fires Iris's
     *  mixin hook, the field is null → NPE.
     *
     *  <p>Fix (SecurityCraft-style): reflectively save the pipeline
     *  reference before our nested render, restore it after. Our nested
     *  render runs without an Iris pipeline (vanilla path — matches the
     *  design decision "no shaders in camera feed"). The outer frame
     *  picks up where it left off with its pipeline intact.
     *
     *  <p>Field resolution is best-effort — if Iris isn't installed OR
     *  it renamed the field, we skip the save/restore. */
    private static final boolean IRIS_OR_OCULUS_LOADED =
            ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris");
    private static final Field IRIS_PIPELINE_FIELD = findIrisPipelineField();
    private static volatile boolean LOGGED_SHADER_SKIP = false;

    private static Field findIrisPipelineField() {
        if (!IRIS_OR_OCULUS_LOADED) return null;
        // Try direct name first; then scan by type-name match.
        try {
            Field f = LevelRenderer.class.getDeclaredField("pipeline");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException ignored) {}
        for (Field f : LevelRenderer.class.getDeclaredFields()) {
            if (f.getType().getName().contains("WorldRenderingPipeline")) {
                try {
                    f.setAccessible(true);
                    return f;
                } catch (Exception ignored) {}
            }
        }
        DeceasedCC.LOGGER.warn("[CameraFeed] Iris/Oculus loaded but WorldRenderingPipeline "
                + "field not found — capture may crash. Expected LevelRenderer.pipeline or a "
                + "field typed WorldRenderingPipeline.");
        return null;
    }

    public static boolean isCapturing() { return currentlyCapturing; }

    public static boolean isShaderModActive() { return IRIS_OR_OCULUS_LOADED; }

    /**
     * Capture a frame from {@code camPos} / {@code yaw} / {@code pitch}
     * into {@code target}. Returns true on success. Safe to call every
     * frame; per-feed rate-limiting is the caller's responsibility.
     */
    public static boolean captureTo(TextureTarget target, BlockPos camPos,
                                     float yaw, float pitch, float fovDegrees) {
        if (currentlyCapturing) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;

        // ---- Iris shader-active check ----
        // Iris leaves a non-null fallback pipeline even with no shader pack
        // selected, so "pipeline != null" is too broad. Only the actual
        // shader-executing class (IrisRenderingPipeline) causes the render-
        // pipeline crash we're trying to avoid. Fallback pipelines render
        // vanilla-compatibly and DON'T crash when re-entered.
        //
        // We also preserve the pipeline reference across our nested render
        // as belt-and-suspenders — Iris may mutate it mid-call.
        Object savedIrisPipeline = null;
        boolean irisPipelineSaved = false;
        if (IRIS_PIPELINE_FIELD != null) {
            try {
                savedIrisPipeline = IRIS_PIPELINE_FIELD.get(mc.levelRenderer);
                irisPipelineSaved = true;
                if (savedIrisPipeline != null) {
                    String cls = savedIrisPipeline.getClass().getSimpleName();
                    // Match only the shader-executing pipeline classes. Iris's
                    // vanilla fallback typically has "Vanilla" or "FixedFunction"
                    // in the class name; the shader pipeline is "IrisRenderingPipeline".
                    if (cls.equals("IrisRenderingPipeline")) {
                        if (!LOGGED_SHADER_SKIP) {
                            LOGGED_SHADER_SKIP = true;
                            DeceasedCC.LOGGER.warn("[CameraFeed] Iris shader pack active ({}) " +
                                    "— skipping live feed to avoid render-pipeline crash. " +
                                    "Disable shaders via Iris's menu to restore the feed. " +
                                    "See ideas.txt #46.", cls);
                        }
                        return false;
                    }
                }
            } catch (IllegalAccessException ignored) {}
        }

        Marker marker = EntityType.MARKER.create(mc.level);
        if (marker == null) return false;
        // Offset the view origin FORWARD along the look direction so we
        // render from just past the lens barrel — NOT from inside the
        // camera block. Without this, the camera's own lens/body geometry
        // appears in the captured frame. 0.6 blocks clears the lens tip
        // (which extends to 1.0 block in the lens-barrel model).
        Vec3 forward = FrustumScanner.lookVec(yaw, pitch);
        double cx = camPos.getX() + 0.5 + forward.x * 0.6;
        double cy = camPos.getY() + 0.5 + forward.y * 0.6;
        double cz = camPos.getZ() + 0.5 + forward.z * 0.6;
        marker.setPos(cx, cy, cz);
        marker.setYRot(yaw);
        marker.setXRot(pitch);
        marker.yRotO = yaw;
        marker.xRotO = pitch;
        marker.xo = cx; marker.yo = cy; marker.zo = cz;

        LevelRendererAccessor lrAcc = (LevelRendererAccessor) (Object) mc.levelRenderer;
        MinecraftAccessor mcAcc = (MinecraftAccessor) (Object) mc;
        Camera mainCam = mc.gameRenderer.getMainCamera();
        CameraAccessor camAcc = (CameraAccessor) (Object) mainCam;

        // ---- SAVE ----
        RenderTarget oMain = mc.getMainRenderTarget();
        RenderTarget oTranslucent = lrAcc.getTranslucentTarget();
        RenderTarget oItemEntity = lrAcc.getItemEntityTarget();
        RenderTarget oWeather = lrAcc.getWeatherTarget();
        RenderTarget oClouds = lrAcc.getCloudsTarget();
        PostChain oTransparency = lrAcc.getTransparencyChain();
        Entity oCamEntity = mcAcc.getCameraEntity();
        CameraType oCamType = mc.options.getCameraType();
        float oEye = camAcc.getEyeHeight();
        float oEyeOld = camAcc.getEyeHeightOld();
        // Save RenderSystem matrix state — gameRenderer.renderLevel will
        // set its own projection + vertex sorting, and if we don't restore,
        // the outer frame's next draws run with OUR matrix → visible jitter.
        Matrix4f oProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oVertexSorting = RenderSystem.getVertexSorting();
        // Save + override FOV. gameRenderer.renderLevel reads options.fov
        // when building its projection matrix. We temporarily set it to
        // the requested feedFov, then restore after the nested render.
        int oFov = mc.options.fov().get();
        int wantedFov = Math.max(10, Math.min(170, (int) fovDegrees));

        currentlyCapturing = true;
        boolean ok = true;
        try {
            mc.renderBuffers().bufferSource().endBatch();

            // ---- SWAP ----
            mcAcc.setMainRenderTarget(target);
            lrAcc.setTranslucentTarget(null);
            lrAcc.setItemEntityTarget(null);
            lrAcc.setWeatherTarget(null);
            lrAcc.setCloudsTarget(null);
            lrAcc.setTransparencyChain(null);
            // Direct field set — avoids checkEntityPostEffect() which
            // would reload post-process shaders twice per capture cycle,
            // causing visible flicker on the player's screen.
            mcAcc.setCameraEntityDirect(marker);
            camAcc.setEyeHeight(0.0f);
            camAcc.setEyeHeightOld(0.0f);
            // cameraType NOT changed — forcing FIRST_PERSON fights user F5
            // presses that land during our capture window. Our render
            // doesn't actually need a specific cameraType.
            mc.gameRenderer.setPanoramicMode(true);
            mc.gameRenderer.setRenderBlockOutline(false);
            mc.gameRenderer.setRenderHand(false);
            // Override FOV for the capture — GameRenderer reads options.fov
            // to build its projection matrix.
            if (wantedFov != oFov) mc.options.fov().set(wantedFov);

            target.bindWrite(true);
            target.clear(Minecraft.ON_OSX);

            // ---- INVOKE ----
            mc.gameRenderer.renderLevel(1.0F, 0L, new PoseStack());
        } catch (Throwable t) {
            DeceasedCC.LOGGER.error("[CameraFeed] capture failed at {}", camPos, t);
            ok = false;
        } finally {
            // ---- RESTORE ----
            mc.gameRenderer.setRenderHand(true);
            mc.gameRenderer.setRenderBlockOutline(true);
            mc.gameRenderer.setPanoramicMode(false);
            // cameraType not changed — no restore needed.
            mcAcc.setCameraEntityDirect(oCamEntity);
            // CRITICAL: re-run Camera.setup with the original entity so
            // the main Camera object's position + rotation + view matrix
            // match the player's POV again. Without this, rendering passes
            // that come AFTER our event (particles, clouds, weather) see
            // the marker's camera state → particles teleport, clouds
            // shift, visible jitter. Camera.setup reads the current
            // cameraType and cameraEntity (both restored above).
            if (oCamEntity != null && mc.level != null) {
                boolean thirdPerson = oCamType != CameraType.FIRST_PERSON;
                boolean mirrored = oCamType == CameraType.THIRD_PERSON_FRONT;
                mainCam.setup(mc.level, oCamEntity, thirdPerson, mirrored, 1.0f);
            }
            // Re-restore eyeHeight AFTER setup — setup can mutate these.
            camAcc.setEyeHeight(oEye);
            camAcc.setEyeHeightOld(oEyeOld);
            // Restore FOV BEFORE restoring matrices, in case some internal
            // code reads it during the swap.
            if (wantedFov != oFov) mc.options.fov().set(oFov);
            // Restore RenderSystem matrices — critical for no-jitter.
            RenderSystem.setProjectionMatrix(oProjection, oVertexSorting);
            lrAcc.setTranslucentTarget(oTranslucent);
            lrAcc.setItemEntityTarget(oItemEntity);
            lrAcc.setWeatherTarget(oWeather);
            lrAcc.setCloudsTarget(oClouds);
            lrAcc.setTransparencyChain(oTransparency);
            mcAcc.setMainRenderTarget(oMain);
            oMain.bindWrite(true);
            RenderSystem.viewport(0, 0, oMain.width, oMain.height);
            // Restore Iris pipeline in case our nested render mutated it.
            if (irisPipelineSaved && IRIS_PIPELINE_FIELD != null) {
                try { IRIS_PIPELINE_FIELD.set(mc.levelRenderer, savedIrisPipeline); }
                catch (IllegalAccessException ignored) {}
            }
            currentlyCapturing = false;
        }
        return ok;
    }
}
