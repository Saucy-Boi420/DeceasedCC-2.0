package net.deceasedcraft.deceasedcc.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.blocks.entity.CameraBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.HologramProjectorBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.HologramProjectorBlockEntity.HologramMode;
import net.deceasedcraft.deceasedcc.client.camera.CameraFeedManager;
import net.deceasedcraft.deceasedcc.client.camera.CameraFeedRenderer;
import net.deceasedcraft.deceasedcc.util.MarkerShape;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * Phase 2 renderer — for every visible hologram in the client cache, draws
 * a double-sided textured quad at the projector's emitter position with the
 * configured transform (scale / offset / rotation) and color tint.
 *
 * <p>Hooks {@link RenderLevelStageEvent.Stage#AFTER_TRANSLUCENT_BLOCKS}.
 * Rendering at this stage means:
 * <ul>
 *   <li>Solid and translucent world blocks have already drawn and written
 *       to the depth buffer, so our depth-test catches stone-between-camera
 *       -and-quad occlusion correctly.</li>
 *   <li>Particles, clouds, and weather draw AFTER this stage, so they can
 *       correctly appear in front of the quad when they physically should.</li>
 * </ul>
 * (Previous attempt used AFTER_PARTICLES — clouds rendered AFTER the quad
 *  then "showed through" since the quad used depth-write-off translucent.
 *  AFTER_TRANSLUCENT_BLOCKS fixes that.)
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class HologramRenderer {
    private HologramRenderer() {}

    // Keep cull distance generous — the hologram payload is small, but if a
    // player walks 150+ blocks away the quad becomes a pixel and we waste
    // draw calls. Tunable via ModConfig in Phase 10.
    /** Fallback (pre-config-loaded). Real value comes from ModConfig. */
    private static final double DEFAULT_MAX_RENDER_DISTANCE_SQ = 128.0 * 128.0;

    /** Phase 10 — cache the config value each frame to avoid repeated
     *  config-map lookups in the per-entry loop. Recomputed on first entry
     *  of each frame. */
    private static double maxRenderDistanceSq() {
        try {
            int d = net.deceasedcraft.deceasedcc.core.ModConfig.CLIENT_HOLOGRAM_RENDER_DISTANCE.get();
            return (double) d * d;
        } catch (Throwable t) {
            return DEFAULT_MAX_RENDER_DISTANCE_SQ;
        }
    }

    // Alpha-blend render type with a MAX alpha cap applied per-vertex.
    // Previous attempts:
    //   1. entityTranslucentEmissive (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) —
    //      opaque pixels fully obscured blocks behind them. User rejected.
    //   2. LIGHTNING_TRANSPARENCY (SRC_ALPHA, ONE) additive — saturated
    //      bright colors (pure yellow's R+G channels = 1.0 both, adding
    //      to any background clamped to white, hiding background entirely
    //      behind yellow pixels). User rejected.
    // This attempt: classic alpha blend but cap source alpha at MAX_HOLO_ALPHA
    // so even 0xFF pixels are at most ~70% opaque. Background always has at
    // least (1 - 70% = 30%) influence on the final pixel. No single color
    // can fully occlude, pure yellow included.
    private static final int MAX_HOLO_ALPHA = 180; // out of 255 (~70%)

    /** Per-frame exponential-smoothing factor for transform interpolation.
     *  0.2 gives a "feel" similar to the turret-aim smoothing — converges
     *  within ~5 frames at 60 FPS. */
    private static final float TRANSFORM_LERP = 0.2f;

    // For voxel meshes, many coplanar quads stack (inner + outer of a
    // 1-voxel-thick shell once 1-thick-wall dedup is off, cube front+back
    // through the hollow, heterogeneous layers in MODE_3D_FULL). Alpha
    // blending compounds: 2 layers of 70% alpha → 91% opaque, 4 layers
    // → 99%, 6 layers (corner) → saturated. A lower per-face cap keeps
    // each individual face translucent AND leaves a readable window
    // through the deepest stack. 60/255 (~24%) → 2 layers = 42%,
    // 4 layers = 66%, 6 layers = 80%. Tunable via ModConfig in Phase 10.
    // (An additive-blend variant was tried for 3D and reverted — user
    // preferred the alpha-blend look even without far-face see-through.)
    private static final int MAX_HOLO_VOXEL_ALPHA = 60;

    /** Per-projector Lua-controlled alpha policy. When the projector hasn't
     *  set an alpha multiplier (sentinel -1), keep the legacy translucent
     *  cap so existing scripts render exactly as before. When it HAS set
     *  one, use it as a 0..1 scale on per-pixel/per-voxel alpha — no cap.
     *  This is what allows a palette entry like "#80FFFFFF" to render
     *  half-transparent (per-voxel "glass" effect) when the projector's
     *  multiplier is 1.0. */
    private static int applyAlphaPolicy(int a, float multiplier, int legacyCap) {
        if (multiplier < 0f) return Math.min(a, legacyCap);
        int scaled = Math.round(a * multiplier);
        if (scaled < 0) return 0;
        if (scaled > 255) return 255;
        return scaled;
    }

    private static final Map<ResourceLocation, RenderType> RT_CACHE = new HashMap<>();

    private static RenderType hologramRT(ResourceLocation tex) {
        return RT_CACHE.computeIfAbsent(tex, HologramRenderTypes::translucent);
    }

    /** Voxel RenderType — reuses the proven Phase-2 translucent pipeline
     *  (POSITION_COLOR_TEX_LIGHTMAP + POSITION_COLOR_TEX_LIGHTMAP_SHADER +
     *  TRANSLUCENT_TRANSPARENCY + LIGHTMAP + NO_CULL + alpha-blend). Voxel
     *  faces are solid-coloured (no per-pixel detail), so we sample a shared
     *  1×1 white texture at UV (0.5, 0.5) and let the vertex colour drive
     *  the actual rendered tint.
     *
     *  Why not a custom {@code POSITION_COLOR} RenderType? The first Phase-3
     *  attempt did exactly that and produced packet-arrives + mesh-builds
     *  + zero-visible-output. Root cause never fully confirmed — possibly
     *  shader not loaded, possibly ColorModulator state, possibly something
     *  else in the AFTER_TRANSLUCENT_BLOCKS state machine. Rather than chase
     *  it further, we piggyback on Phase 2's proven path. */
    private static ResourceLocation WHITE_TEX_LOC;

    private static ResourceLocation ensureWhiteTexture() {
        if (WHITE_TEX_LOC != null) return WHITE_TEX_LOC;
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        img.setPixelRGBA(0, 0, 0xFFFFFFFF); // ABGR — fully opaque white
        DynamicTexture tex = new DynamicTexture(img);
        WHITE_TEX_LOC = Minecraft.getInstance().getTextureManager().register(
                "deceasedcc_hologram_white", tex);
        return WHITE_TEX_LOC;
    }

    /**
     * RenderStateShard's static constants are protected; extending RenderType
     * unlocks visibility on them so we can build a CompositeState.
     */
    private static final class HologramRenderTypes extends RenderType {
        private HologramRenderTypes(String s, VertexFormat f, VertexFormat.Mode m, int sz,
                                     boolean a, boolean b, Runnable r, Runnable r2) {
            super(s, f, m, sz, a, b, r, r2);
        }

        static RenderType translucent(ResourceLocation tex) {
            return RenderType.create(
                    "deceasedcc_hologram_translucent",
                    DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                    VertexFormat.Mode.QUADS,
                    256,
                    false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(POSITION_COLOR_TEX_LIGHTMAP_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY) // SRC_ALPHA, ONE_MINUS_SRC_ALPHA
                            .setLightmapState(LIGHTMAP)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .createCompositeState(false));
        }

    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Map<BlockPos, HologramClientCache.Entry> entries = HologramClientCache.entries();
        if (entries.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Camera cam = event.getCamera();
        net.minecraft.world.phys.Vec3 camPos = cam.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource bufSource = mc.renderBuffers().bufferSource();
        double maxSq = maxRenderDistanceSq();

        // Track every RenderType we emitted into so we can flush them all
        // at the end. Each projector has its own texture -> its own
        // RenderType bucket; endBatch() without args flushes ALL of them.
        boolean drewAny = false;
        for (Map.Entry<BlockPos, HologramClientCache.Entry> me : entries.entrySet()) {
            HologramClientCache.Entry e = me.getValue();
            // Interpolate every frame, including while invisible, so a
            // hidden-then-shown projector snaps back to its last target
            // rather than animating from stale intermediate state.
            e.tickLerp(TRANSFORM_LERP);
            if (!e.visible) continue;
            BlockPos pos = me.getKey();

            double dx = (pos.getX() + 0.5) - camPos.x;
            double dy = (pos.getY() + 0.5) - camPos.y;
            double dz = (pos.getZ() + 0.5) - camPos.z;
            if (dx * dx + dy * dy + dz * dz > maxSq) continue;

            // Phase 8.2 — live-camera mode takes priority over whatever
            // static content was last uploaded. When the server-side BE
            // has liveCameraMode=true, every render invokes the per-client
            // render-to-FBO path (via CameraFeedManager) and binds the
            // captured texture onto the hologram quad. Static image data
            // is ignored while live mode is on.
            if (e.liveCameraMode) {
                if (CameraFeedRenderer.isCapturing()) continue;  // reentry guard
                if (prepareLiveCameraEntry(pos, e)) {
                    renderOne(pose, bufSource, pos, camPos, e);
                    drewAny = true;
                }
                continue;
            }

            // Mode-dispatch: each projector shows either a 2D image (Phase 2)
            // or a voxel mesh (Phase 3). Multiple projectors with different
            // modes coexist in the same frame — each emits into its own
            // RenderType bucket, flushed together by endBatch() below.
            if (e.mode == HologramMode.MODE_2D) {
                if (e.texture == null || e.textureLocation == null) continue;
                renderOne(pose, bufSource, pos, camPos, e);
                drewAny = true;
            } else if (e.mode == HologramMode.MODE_3D_CULLED || e.mode == HologramMode.MODE_3D_FULL) {
                if (e.voxIndexes == null || e.voxPalette == null) continue;
                renderVoxel(pose, bufSource, pos, camPos, e);
                drewAny = true;
            } else if (e.mode == HologramMode.MARKERS) {
                if (e.markersCount <= 0) continue;
                renderMarkers(pose, bufSource, pos, camPos, e);
                drewAny = true;
            } else if (e.mode == HologramMode.COMPOSITE) {
                // Voxel base + marker overlay in one frame.
                if (e.voxIndexes != null && e.voxPalette != null) {
                    renderVoxel(pose, bufSource, pos, camPos, e);
                    drewAny = true;
                }
                if (e.markersCount > 0) {
                    renderMarkers(pose, bufSource, pos, camPos, e);
                    drewAny = true;
                }
            }
        }

        if (drewAny) {
            // Flush ALL buckets — particles/weather/clouds come after this
            // stage and expect a clean pipeline state.
            bufSource.endBatch();
        }

        // Phase 8.2 — periodically reclaim GPU resources for projectors
        // that left view. Cheap: walks a small map and destroys stale FBOs.
        CameraFeedManager.sweepStale();
    }

    /** Phase 8.2 — prepare an entry for live-camera rendering. Looks up the
     *  paired camera, ensures a feed exists, triggers a refresh if the rate
     *  limiter allows, and points the entry's texture + dimensions at the
     *  feed's FBO-backed ResourceLocation. Returns false if the pair or
     *  camera is unavailable — caller should skip rendering this entry. */
    private static boolean prepareLiveCameraEntry(BlockPos projPos,
                                                   HologramClientCache.Entry e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        // Phase 8.2 — pairedCameraPos comes from the Entry (synced via
        // HologramTransformPacket), NOT the projector BE's getBlockEntity()
        // lookup (the client-side BE doesn't receive pairedCameraPos NBT
        // syncs — non-sync'd BE fields are server-only by default).
        BlockPos camPos = e.pairedCameraPos;
        if (camPos == null) return false;
        if (!mc.level.isLoaded(camPos)) return false;
        var camBE = mc.level.getBlockEntity(camPos);
        if (!(camBE instanceof CameraBlockEntity cbe)) return false;

        ResourceLocation feedTex = CameraFeedManager.getOrCreateTexture(projPos, e.feedWidth, e.feedHeight);
        CameraFeedManager.refreshIfDue(projPos, camPos, cbe.getYawDeg(), cbe.getPitchDeg(), e.feedFov);
        e.textureLocation = feedTex;
        e.width  = e.feedWidth;
        e.height = e.feedHeight;
        return true;
    }

    private static void renderOne(PoseStack pose, MultiBufferSource buffers,
                                   BlockPos pos, net.minecraft.world.phys.Vec3 camPos,
                                   HologramClientCache.Entry e) {
        // Emit two triangles as a full quad in the XY plane (Z=0 in local
        // space). Size derived from the image aspect ratio — 1 block tall
        // at aspect 1:1, adjusted horizontally for non-square images.
        float halfH = 0.5f;
        float halfW = 0.5f * ((float) e.width / Math.max(1, e.height));

        // Auto-push the hologram so nothing dips below the projector top,
        // regardless of scale or pitch/roll rotation. 2D quads have 0 Z
        // thickness so halfZ = 0 drops the sin(pitch) term.
        float floorOy = computeFloorOy(halfW, halfH, 0f,
                                        e.sx, e.sy, e.sz, e.pitch, e.roll);
        float effOy = Math.max(e.oy, floorOy);

        pose.pushPose();
        pose.translate(
                (pos.getX() + 0.5) - camPos.x + e.ox,
                (pos.getY() + 1.0) - camPos.y + effOy,
                (pos.getZ() + 0.5) - camPos.z + e.oz);
        // YXZ Euler order per hologram.txt §transform.
        pose.mulPose(Axis.YP.rotationDegrees(e.yaw));
        pose.mulPose(Axis.XP.rotationDegrees(e.pitch));
        pose.mulPose(Axis.ZP.rotationDegrees(e.roll));
        pose.scale(e.sx, e.sy, e.sz);

        int a = (e.colorARGB >>> 24) & 0xFF;
        int r = (e.colorARGB >>> 16) & 0xFF;
        int g = (e.colorARGB >>>  8) & 0xFF;
        int b =  e.colorARGB         & 0xFF;
        a = applyAlphaPolicy(a, e.alphaMultiplier, MAX_HOLO_ALPHA);

        int light = LightTexture.FULL_BRIGHT; // holograms are self-lit.

        // Alpha-blended translucent RenderType with the vertex alpha capped
        // above — the TEXTURE's per-pixel alpha is further multiplied by
        // this tint alpha, so even texture alpha=0xFF is effectively 70%.
        RenderType rt = hologramRT(e.textureLocation);
        VertexConsumer vc = buffers.getBuffer(rt);

        var matrix = pose.last().pose();

        // One double-sided quad (no cull). POSITION_COLOR_TEX_LIGHTMAP
        // vertex format — no normals, no overlay.
        // Live-camera textures come from an FBO (OpenGL bottom-left origin);
        // regular image textures come from NativeImage uploads (top-left
        // origin). Flip V for live-camera so the feed renders right-side-up.
        float vTop    = e.liveCameraMode ? 1f : 0f;
        float vBottom = e.liveCameraMode ? 0f : 1f;
        vc.vertex(matrix, -halfW, -halfH, 0).color(r, g, b, a).uv(0f, vBottom).uv2(light).endVertex();
        vc.vertex(matrix,  halfW, -halfH, 0).color(r, g, b, a).uv(1f, vBottom).uv2(light).endVertex();
        vc.vertex(matrix,  halfW,  halfH, 0).color(r, g, b, a).uv(1f, vTop).uv2(light).endVertex();
        vc.vertex(matrix, -halfW,  halfH, 0).color(r, g, b, a).uv(0f, vTop).uv2(light).endVertex();

        pose.popPose();
    }

    private static void renderVoxel(PoseStack pose, MultiBufferSource buffers,
                                     BlockPos pos, net.minecraft.world.phys.Vec3 camPos,
                                     HologramClientCache.Entry e) {
        // Lazy (re)build on content change. acceptVoxel drops voxBuffer to
        // null; we rebuild once and reuse across frames until the next
        // upload. Greedy meshing is the expensive part — keep it out of
        // the per-frame path.
        if (e.voxBuffer == null) {
            e.voxBuffer = HologramVertexBuffer.build(
                    e.voxSizeX, e.voxSizeY, e.voxSizeZ,
                    e.voxIndexes, e.voxPalette, e.mode);
        }

        // Voxel cell scale: fit the whole grid into a 1-block cube by
        // default (cell_size = 1 / max_dim). A projector.setScale(2,2,2)
        // then yields a 2-block-tall hologram, exactly as with 2D.
        int maxDim = Math.max(e.voxSizeX, Math.max(e.voxSizeY, e.voxSizeZ));
        float cellScale = maxDim > 0 ? (1.0f / maxDim) : 1.0f;

        // Auto-push so the voxel mesh stays above the projector top under
        // any scale/rotation combo.
        float halfX = e.voxSizeX * 0.5f * cellScale;
        float halfY = e.voxSizeY * 0.5f * cellScale;
        float halfZ = e.voxSizeZ * 0.5f * cellScale;
        float floorOy = computeFloorOy(halfX, halfY, halfZ,
                                        e.sx, e.sy, e.sz, e.pitch, e.roll);
        float effOy = Math.max(e.oy, floorOy);

        pose.pushPose();
        // Same emitter-origin translation + YXZ euler + scale as the 2D path.
        pose.translate(
                (pos.getX() + 0.5) - camPos.x + e.ox,
                (pos.getY() + 1.0) - camPos.y + effOy,
                (pos.getZ() + 0.5) - camPos.z + e.oz);
        pose.mulPose(Axis.YP.rotationDegrees(e.yaw));
        pose.mulPose(Axis.XP.rotationDegrees(e.pitch));
        pose.mulPose(Axis.ZP.rotationDegrees(e.roll));
        pose.scale(e.sx, e.sy, e.sz);

        int a = (e.colorARGB >>> 24) & 0xFF;
        int r = (e.colorARGB >>> 16) & 0xFF;
        int g = (e.colorARGB >>>  8) & 0xFF;
        int b =  e.colorARGB         & 0xFF;
        a = applyAlphaPolicy(a, e.alphaMultiplier, MAX_HOLO_VOXEL_ALPHA);

        int light = LightTexture.FULL_BRIGHT;
        RenderType rt = hologramRT(ensureWhiteTexture());
        VertexConsumer vc = buffers.getBuffer(rt);
        e.voxBuffer.render(vc, pose.last(), light, r, g, b, a, cellScale);

        pose.popPose();
    }

    private static void renderMarkers(PoseStack pose, MultiBufferSource buffers,
                                       BlockPos pos, net.minecraft.world.phys.Vec3 camPos,
                                       HologramClientCache.Entry e) {
        // Voxel-local origin convention mirrors renderVoxel: centre the
        // grid on the emitter, scale all voxel-coord lengths by cellScale
        // so the whole grid fits in a unit cube by default.
        int maxDim = Math.max(e.markersSizeX, Math.max(e.markersSizeY, e.markersSizeZ));
        float cellScale = maxDim > 0 ? (1.0f / maxDim) : 1.0f;

        float halfX = e.markersSizeX * 0.5f * cellScale;
        float halfY = e.markersSizeY * 0.5f * cellScale;
        float halfZ = e.markersSizeZ * 0.5f * cellScale;
        float floorOy = computeFloorOy(halfX, halfY, halfZ,
                                        e.sx, e.sy, e.sz, e.pitch, e.roll);
        float effOy = Math.max(e.oy, floorOy);

        pose.pushPose();
        pose.translate(
                (pos.getX() + 0.5) - camPos.x + e.ox,
                (pos.getY() + 1.0) - camPos.y + effOy,
                (pos.getZ() + 0.5) - camPos.z + e.oz);
        pose.mulPose(Axis.YP.rotationDegrees(e.yaw));
        pose.mulPose(Axis.XP.rotationDegrees(e.pitch));
        pose.mulPose(Axis.ZP.rotationDegrees(e.roll));
        pose.scale(e.sx, e.sy, e.sz);

        int tintA = (e.colorARGB >>> 24) & 0xFF;
        int tintR = (e.colorARGB >>> 16) & 0xFF;
        int tintG = (e.colorARGB >>>  8) & 0xFF;
        int tintB =  e.colorARGB         & 0xFF;
        tintA = applyAlphaPolicy(tintA, e.alphaMultiplier, MAX_HOLO_VOXEL_ALPHA);

        // Shared 1×1 white texture so vertex colour drives the final tint,
        // same pipeline as the voxel renderer. One RT binding for all
        // markers keeps the batch tight.
        RenderType rt = hologramRT(ensureWhiteTexture());
        VertexConsumer vc = buffers.getBuffer(rt);
        var matrix = pose.last().pose();
        int light = LightTexture.FULL_BRIGHT;

        // Centre the grid so markers at (0,0,0) sit at the lower-SW corner
        // and (size, size, size) sits at the upper-NE — same convention as
        // the voxel mesher.
        float cx = e.markersSizeX * 0.5f;
        float cy = e.markersSizeY * 0.5f;
        float cz = e.markersSizeZ * 0.5f;

        for (int i = 0; i < e.markersCount; i++) {
            MarkerShape shape = MarkerShape.fromOrdinal(e.markerShapes[i] & 0xFF);
            int c = e.markerColors[i];
            int pr = (c >>> 16) & 0xFF;
            int pg = (c >>>  8) & 0xFF;
            int pb =  c         & 0xFF;
            int pa = (c >>> 24) & 0xFF;
            int r = (pr * tintR) / 255;
            int g = (pg * tintG) / 255;
            int b = (pb * tintB) / 255;
            int a = (pa * tintA) / 255;

            float lx = (e.markerXs[i] - cx) * cellScale;
            float ly = (e.markerYs[i] - cy) * cellScale;
            float lz = (e.markerZs[i] - cz) * cellScale;

            float msx = (e.markerScaleXs != null) ? e.markerScaleXs[i] : 1f;
            float msy = (e.markerScaleYs != null) ? e.markerScaleYs[i] : 1f;
            float msz = (e.markerScaleZs != null) ? e.markerScaleZs[i] : 1f;
            float mYaw   = (e.markerYaws    != null) ? e.markerYaws[i]    : 0f;
            float mPitch = (e.markerPitches != null) ? e.markerPitches[i] : 0f;
            MarkerShapeMesh.renderMarker(vc, matrix, shape,
                    lx, ly, lz,
                    cellScale, msx, msy, msz, mYaw, mPitch,
                    r, g, b, a, light);
        }

        pose.popPose();
    }

    /**
     * Compute how far below the pose-stack origin the rotated + scaled
     * mesh extends, so the caller can push the hologram up with
     * {@code effective_oy = max(user_oy, floor)} and guarantee no face
     * dips below the projector block.
     *
     * <p>Derivation: a YXZ-Euler rotation R = Ry·Rx·Rz has middle row
     * {@code (cosP·sinR, cosP·cosR, -sinP)}. The max Y extent of a
     * scaled axis-aligned half-cuboid {@code (hX·sx, hY·sy, hZ·sz)} under
     * R is the L¹ norm of that row dotted with the half-extents — i.e.
     * {@code |cosP|·(|sinR|·hX·sx + |cosR|·hY·sy) + |sinP|·hZ·sz}. Yaw
     * is absent because rotation about Y preserves Y extent.
     */
    private static float computeFloorOy(float halfX, float halfY, float halfZ,
                                         float sx, float sy, float sz,
                                         float pitchDeg, float rollDeg) {
        float pR = (float) Math.toRadians(pitchDeg);
        float rR = (float) Math.toRadians(rollDeg);
        float cP = Math.abs((float) Math.cos(pR));
        float sP = Math.abs((float) Math.sin(pR));
        float cRo = Math.abs((float) Math.cos(rR));
        float sRo = Math.abs((float) Math.sin(rR));
        return cP * (sRo * halfX * sx + cRo * halfY * sy) + sP * halfZ * sz;
    }

    @SubscribeEvent
    public static void onWorldUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            HologramClientCache.clear();
        }
    }
}
