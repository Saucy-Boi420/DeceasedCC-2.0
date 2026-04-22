package net.deceasedcraft.deceasedcc.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.blocks.entity.HologramProjectorBlockEntity.HologramMode;
import net.deceasedcraft.deceasedcc.network.HologramClearPacket;
import net.deceasedcraft.deceasedcc.network.HologramDataPacket;
import net.deceasedcraft.deceasedcc.network.HologramMarkersPacket;
import net.deceasedcraft.deceasedcc.network.HologramTransformPacket;
import net.deceasedcraft.deceasedcc.network.HologramVisibilityPacket;
import net.deceasedcraft.deceasedcc.network.HologramVoxelPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Client-side per-projector hologram state. One entry per known projector
 * BlockPos, lazily populated by incoming S2C packets. The renderer
 * ({@link HologramRenderer}) iterates this map every frame.
 *
 * <p>Textures live on the GPU — each projector owns one {@link DynamicTexture}
 * that's uploaded when a new image arrives and freed when the projector is
 * cleared or the chunk unloads.
 */
public final class HologramClientCache {

    public static final class Entry {
        public HologramMode mode = HologramMode.IDLE;
        public boolean visible = false;

        // Image payload
        public int width = 0;
        public int height = 0;
        public DynamicTexture texture;
        public ResourceLocation textureLocation;

        // Voxel payload (Phase 3)
        public int voxSizeX = 0, voxSizeY = 0, voxSizeZ = 0;
        public int[] voxPalette;
        public byte[] voxIndexes;            // inflated, 1 byte / voxel
        public HologramVertexBuffer voxBuffer; // lazy-built on first render after a content change

        // Markers payload (Phase 5) — coord space is markerSize*, which
        // equals the voxel grid's size* in COMPOSITE mode.
        public int markersSizeX = 0, markersSizeY = 0, markersSizeZ = 0;
        public int markersCount = 0;
        public float[] markerXs, markerYs, markerZs;
        public byte[]  markerShapes;
        public int[]   markerColors;
        public float[] markerScaleXs, markerScaleYs, markerScaleZs;
        public float[] markerYaws, markerPitches;

        // Transform — CURRENT (interpolated, used by the renderer).
        public float sx = 1f, sy = 1f, sz = 1f;
        public float ox = 0f, oy = 0.5f, oz = 0f;
        public float yaw = 0f, pitch = 0f, roll = 0f;
        public int colorARGB = 0xFFFFFFFF;
        // Phase 7d — read by HologramRenderer to scale per-pixel/per-voxel
        // alpha. -1 = "use legacy cap" (back-compat); 0..1 = explicit scale.
        public float alphaMultiplier = -1f;

        // Phase 8.2 — live-camera mode. When true, HologramRenderer invokes
        // CameraFeedManager to capture the paired camera's POV and binds
        // the resulting FBO texture instead of the static image texture.
        public boolean liveCameraMode = false;
        /** Server-synced paired camera BlockPos. Used by HologramRenderer's
         *  live-camera branch to look up the camera's BE without relying on
         *  the projector's client-side BE (which doesn't receive NBT syncs). */
        public net.minecraft.core.BlockPos pairedCameraPos = null;
        /** Phase 8.3 — sticky feed opts (width, height, fov) synced via
         *  the transform packet. CameraFeedManager uses these to size the
         *  per-projector FBO; CameraFeedRenderer uses fov for projection. */
        public int feedWidth  = 256;
        public int feedHeight = 144;
        public float feedFov  = 60.0f;

        // Transform — TARGET (last value received from the server). Every
        // frame the renderer nudges the CURRENT fields toward these
        // (exponential smoothing, same feel as the turret-aim smoothing).
        // First-ever packet snaps CURRENT = TARGET so the hologram doesn't
        // grow in from the default scale = 1 state on first show.
        public float targetSx = 1f, targetSy = 1f, targetSz = 1f;
        public float targetOx = 0f, targetOy = 0.5f, targetOz = 0f;
        public float targetYaw = 0f, targetPitch = 0f, targetRoll = 0f;
        public int targetColorARGB = 0xFFFFFFFF;
        public boolean transformInitialized = false;

        /** Free the GPU texture. Does NOT zero width/height — the caller
         *  decides whether those stay (replacing a texture) or go (clearing
         *  2D state). acceptImage replaces the texture every upload, so it
         *  relies on width/height surviving this call. */
        public void releaseTexture() {
            if (texture != null) {
                texture.close();
                texture = null;
                textureLocation = null;
            }
        }

        public void releaseVoxel() {
            if (voxBuffer != null) {
                voxBuffer.close();
                voxBuffer = null;
            }
            voxPalette = null;
            voxIndexes = null;
            voxSizeX = voxSizeY = voxSizeZ = 0;
        }

        public void releaseMarkers() {
            markersCount = 0;
            markerXs = null; markerYs = null; markerZs = null;
            markerShapes = null;
            markerColors = null;
            markerScaleXs = null; markerScaleYs = null; markerScaleZs = null;
            markerYaws = null; markerPitches = null;
            markersSizeX = markersSizeY = markersSizeZ = 0;
        }

        /** Move each CURRENT transform field a fraction of the way toward
         *  its TARGET. Called once per render frame by {@link HologramRenderer}. */
        public void tickLerp(float f) {
            sx = lerpF(sx, targetSx, f);
            sy = lerpF(sy, targetSy, f);
            sz = lerpF(sz, targetSz, f);
            ox = lerpF(ox, targetOx, f);
            oy = lerpF(oy, targetOy, f);
            oz = lerpF(oz, targetOz, f);
            yaw   = lerpAngle(yaw,   targetYaw,   f);
            pitch = lerpAngle(pitch, targetPitch, f);
            roll  = lerpAngle(roll,  targetRoll,  f);
            colorARGB = lerpColor(colorARGB, targetColorARGB, f);
        }

        /** Exponential lerp with snap-to-target when within epsilon, to
         *  avoid floating-point drift when the client has converged. */
        private static float lerpF(float c, float t, float f) {
            float next = c + (t - c) * f;
            if (Math.abs(t - next) < 0.001f) return t;
            return next;
        }

        /** Short-path angular lerp. Normalises the delta to (-180, 180]
         *  so 358° → 2° takes the +4° route, not -356°. */
        private static float lerpAngle(float c, float t, float f) {
            float delta = ((t - c) % 360f + 540f) % 360f - 180f;
            float next = c + delta * f;
            if (Math.abs(delta) < 0.1f) return t;
            return next;
        }

        /** Channel-wise ARGB lerp. Good enough for small per-frame steps;
         *  the test script emits many HSV-spaced intermediate colours so
         *  the user-visible path follows the hue wheel even though each
         *  tiny step is RGB-linear. */
        private static int lerpColor(int c, int t, float f) {
            if (c == t) return t;
            int cA = (c >>> 24) & 0xFF;
            int cR = (c >>> 16) & 0xFF;
            int cG = (c >>>  8) & 0xFF;
            int cB =  c         & 0xFF;
            int tA = (t >>> 24) & 0xFF;
            int tR = (t >>> 16) & 0xFF;
            int tG = (t >>>  8) & 0xFF;
            int tB =  t         & 0xFF;
            int nA = (int) Math.round(cA + (tA - cA) * f);
            int nR = (int) Math.round(cR + (tR - cR) * f);
            int nG = (int) Math.round(cG + (tG - cG) * f);
            int nB = (int) Math.round(cB + (tB - cB) * f);
            return ((nA & 0xFF) << 24) | ((nR & 0xFF) << 16) | ((nG & 0xFF) << 8) | (nB & 0xFF);
        }
    }

    private static final Map<BlockPos, Entry> ENTRIES = new ConcurrentHashMap<>();

    private HologramClientCache() {}

    public static Map<BlockPos, Entry> entries() { return ENTRIES; }

    /** Wipe everything — called on disconnect / world unload. */
    public static void clear() {
        for (Entry e : ENTRIES.values()) {
            e.releaseTexture();
            e.releaseVoxel();
            e.releaseMarkers();
        }
        ENTRIES.clear();
    }

    public static Entry getOrCreate(BlockPos pos) {
        return ENTRIES.computeIfAbsent(pos.immutable(), k -> new Entry());
    }

    // ---- packet handlers (called on MC's main thread) ----------------------

    public static void acceptImage(HologramDataPacket p) {
        Entry e = getOrCreate(p.pos());
        // Uploading a new 2D image evicts voxel + markers (one content
        // kind at a time). Mirrors the server-side rule in the BE.
        e.releaseVoxel();
        e.releaseMarkers();
        e.mode = p.mode();
        e.width = p.width();
        e.height = p.height();

        // Inflate the Deflate-compressed payload. Expected size: w*h*4 bytes
        // (ARGB, 1 byte per channel).
        int expected = p.width() * p.height() * 4;
        byte[] raw = new byte[expected];
        Inflater inf = new Inflater();
        try {
            inf.setInput(p.compressed());
            int produced = 0;
            while (!inf.finished() && produced < expected) {
                int n = inf.inflate(raw, produced, expected - produced);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                produced += n;
            }
        } catch (DataFormatException ex) {
            DeceasedCC.LOGGER.warn("Hologram image inflate failed at {}: {}", p.pos(), ex.getMessage());
            return;
        } finally {
            inf.end();
        }

        // Free the old texture before allocating a new one. Reuse when the
        // dimensions match — avoids a GPU realloc on every frame update.
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, p.width(), p.height(), false);
        for (int y = 0; y < p.height(); y++) {
            for (int x = 0; x < p.width(); x++) {
                int idx = (y * p.width() + x) * 4;
                int a = raw[idx]     & 0xFF;
                int r = raw[idx + 1] & 0xFF;
                int g = raw[idx + 2] & 0xFF;
                int b = raw[idx + 3] & 0xFF;
                // NativeImage.RGBA is stored as 0xAABBGGRR on little-endian.
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                img.setPixelRGBA(x, y, abgr);
            }
        }

        e.releaseTexture();
        e.texture = new DynamicTexture(img);
        e.textureLocation = Minecraft.getInstance().getTextureManager().register(
                "deceasedcc_hologram_" + p.pos().asLong(), e.texture);
    }

    public static void acceptVisibility(HologramVisibilityPacket p) {
        Entry e = getOrCreate(p.pos());
        e.visible = p.visible();
    }

    public static void acceptTransform(HologramTransformPacket p) {
        Entry e = getOrCreate(p.pos());
        e.targetSx = p.sx(); e.targetSy = p.sy(); e.targetSz = p.sz();
        e.targetOx = p.ox(); e.targetOy = p.oy(); e.targetOz = p.oz();
        e.targetYaw = p.yaw(); e.targetPitch = p.pitch(); e.targetRoll = p.roll();
        e.targetColorARGB = p.colorARGB();
        e.alphaMultiplier = p.alphaMultiplier();
        e.liveCameraMode = p.liveCameraMode();
        long packed = p.pairedCameraPosPacked();
        e.pairedCameraPos = (packed == HologramTransformPacket.NO_PAIR)
                ? null
                : net.minecraft.core.BlockPos.of(packed);
        e.feedWidth  = p.feedWidth();
        e.feedHeight = p.feedHeight();
        e.feedFov    = p.feedFov();
        // First packet for this projector snaps CURRENT = TARGET so the
        // hologram doesn't smoothly "grow in" from the default state when
        // it first appears. Subsequent packets just update TARGET and let
        // tickLerp smooth between them.
        if (!e.transformInitialized) {
            e.sx = e.targetSx; e.sy = e.targetSy; e.sz = e.targetSz;
            e.ox = e.targetOx; e.oy = e.targetOy; e.oz = e.targetOz;
            e.yaw = e.targetYaw; e.pitch = e.targetPitch; e.roll = e.targetRoll;
            e.colorARGB = e.targetColorARGB;
            e.transformInitialized = true;
        }
    }

    public static void acceptClear(HologramClearPacket p) {
        Entry e = ENTRIES.remove(p.pos());
        if (e != null) {
            e.releaseTexture();
            e.releaseVoxel();
            e.releaseMarkers();
        }
    }

    /** Stash marker overlay data on the entry. Mode comes from the packet —
     *  MARKERS for markers-only, COMPOSITE when layered on a voxel mesh. */
    public static void acceptMarkers(HologramMarkersPacket p) {
        Entry e = getOrCreate(p.pos());
        e.mode = p.mode();
        e.markersSizeX = p.sizeX();
        e.markersSizeY = p.sizeY();
        e.markersSizeZ = p.sizeZ();
        e.markersCount = p.count();
        e.markerXs = p.xs();
        e.markerYs = p.ys();
        e.markerZs = p.zs();
        e.markerShapes = p.shapes();
        e.markerColors = p.colors();
        e.markerScaleXs = p.scaleXs();
        e.markerScaleYs = p.scaleYs();
        e.markerScaleZs = p.scaleZs();
        e.markerYaws = p.yaws();
        e.markerPitches = p.pitches();
        // MARKERS mode evicts any 2D image (one content kind). COMPOSITE
        // keeps the voxel grid that arrives alongside via the voxel packet.
        if (p.mode() == HologramMode.MARKERS) {
            e.releaseTexture();
            e.width = 0;
            e.height = 0;
            e.releaseVoxel();
        }
    }

    /**
     * Inflate voxel indexes, stash them on the entry, and invalidate any
     * cached mesh so the renderer rebuilds on its next pass.
     */
    public static void acceptVoxel(HologramVoxelPacket p) {
        Entry e = getOrCreate(p.pos());
        // Voxel upload evicts any 2D image. Does NOT evict markers — in
        // COMPOSITE mode the voxel + markers packets arrive together and
        // we want to keep both; in MODE_3D_* the mesher has already
        // cleared markers server-side so the field is null anyway.
        e.releaseTexture();
        e.width = 0;
        e.height = 0;

        e.mode = p.mode();
        e.voxSizeX = p.sizeX();
        e.voxSizeY = p.sizeY();
        e.voxSizeZ = p.sizeZ();
        e.voxPalette = p.palette();

        int expected = p.sizeX() * p.sizeY() * p.sizeZ();
        byte[] raw = new byte[expected];
        Inflater inf = new Inflater();
        try {
            inf.setInput(p.compressedIndexes());
            int produced = 0;
            while (!inf.finished() && produced < expected) {
                int n = inf.inflate(raw, produced, expected - produced);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                produced += n;
            }
        } catch (DataFormatException ex) {
            DeceasedCC.LOGGER.warn("Hologram voxel inflate failed at {}: {}", p.pos(), ex.getMessage());
            e.releaseVoxel();
            return;
        } finally {
            inf.end();
        }
        e.voxIndexes = raw;

        // Drop the previous cached mesh — renderer lazy-rebuilds.
        if (e.voxBuffer != null) {
            e.voxBuffer.close();
            e.voxBuffer = null;
        }
    }
}
