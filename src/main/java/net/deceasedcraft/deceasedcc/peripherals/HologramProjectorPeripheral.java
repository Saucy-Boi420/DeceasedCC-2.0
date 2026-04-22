package net.deceasedcraft.deceasedcc.peripherals;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.blocks.entity.CameraBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.HologramProjectorBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.HologramProjectorBlockEntity.HologramMode;
import net.deceasedcraft.deceasedcc.core.ModConfig;
import net.deceasedcraft.deceasedcc.util.CameraFrameRenderer;
import net.deceasedcraft.deceasedcc.util.FrustumScanner;
import net.deceasedcraft.deceasedcc.util.MarkerShape;
import net.deceasedcraft.deceasedcc.util.VoxelPalette;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;

/**
 * {@code hologram_projector} peripheral — Phase 2 wires up setImage / show /
 * hide / clear / setColor + transform setters to push actual state + packets.
 * Voxel / marker / composite methods remain stubbed for Phases 3+.
 */
public class HologramProjectorPeripheral implements IPeripheral {

    /** Hard cap on image dimensions — matches hologram.txt §Image2D encoding.
     *  Phase 10 moves this to ModConfig. */
    private static final int MAX_IMAGE_DIM = 128;

    /** Hard cap on voxel grid dimension per axis (hologram.txt §12 — Phase 10
     *  will make this config-tunable). A 64³ grid = 262 144 cells, which
     *  greedy-meshes to a manageable quad count and fits in a single packet
     *  once Deflate-compressed. */
    private static final int MAX_VOXEL_DIM = 64;

    /** Hard cap on entity-marker count per projector (hologram.txt §12).
     *  256 markers × 17 wire bytes = ~4 KiB packet, well under MTU. */
    private static final int MAX_MARKERS = 256;

    private final HologramProjectorBlockEntity host;
    private final Set<IComputerAccess> attached = ConcurrentHashMap.newKeySet();

    public HologramProjectorPeripheral(HologramProjectorBlockEntity host) {
        this.host = host;
    }

    @Override public String getType() { return "hologram_projector"; }
    @Override public void attach(IComputerAccess c) { attached.add(c); }

    /**
     * When the last attached computer disconnects (shutdown, modem removed,
     * network break), hide the projection so a powered-off setup doesn't
     * leave a ghost hologram hovering in mid-air. The payload + transform
     * stay intact on the BE — the next attached computer can call
     * {@link #show()} to restore visibility without re-uploading content.
     */
    @Override
    public void detach(IComputerAccess c) {
        attached.remove(c);
        if (attached.isEmpty()) {
            host.setVisible(false);
        }
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof HologramProjectorPeripheral o && o.host == this.host;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getPosition() {
        var p = host.getBlockPos();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", p.getX()); out.put("y", p.getY()); out.put("z", p.getZ());
        return out;
    }

    // ---- content setters ---------------------------------------------------

    /**
     * Upload a 2D image to display.
     *
     * Lua shape:
     *   projector.setImage({
     *     width = 32,
     *     height = 32,
     *     pixels = { 0xFFFF00FF, 0xFFFF0000, ... }  -- ARGB ints, row-major
     *   })
     *
     * The pixel count must equal width * height. Max 128x128.
     */
    @LuaFunction(mainThread = true)
    public final void setImage(Map<?, ?> imageTable) throws LuaException {
        if (imageTable == null) throw new LuaException("image table required");
        int w = intField(imageTable, "width");
        int h = intField(imageTable, "height");
        if (w <= 0 || h <= 0) throw new LuaException("width and height must be > 0");
        if (w > MAX_IMAGE_DIM || h > MAX_IMAGE_DIM) {
            throw new LuaException("image dimensions exceed cap of " + MAX_IMAGE_DIM);
        }
        Object pixelsObj = imageTable.get("pixels");
        if (!(pixelsObj instanceof Map<?, ?> pixels)) {
            throw new LuaException("pixels must be a table of ARGB ints");
        }
        int expected = w * h;
        if (pixels.size() != expected) {
            throw new LuaException("pixel count " + pixels.size() + " != " + expected + " (w*h)");
        }

        // Flatten to raw ARGB bytes row-major. CC passes Lua arrays as
        // java Maps<Integer, Object> with 1-indexed keys.
        byte[] raw = new byte[expected * 4];
        for (int i = 0; i < expected; i++) {
            Object v = pixels.get((double) (i + 1));
            if (v == null) v = pixels.get(i + 1);
            if (v == null) v = pixels.get((long) (i + 1));
            long px = v instanceof Number n ? n.longValue() : 0L;
            int idx = i * 4;
            raw[idx]     = (byte) ((px >>> 24) & 0xFF); // A
            raw[idx + 1] = (byte) ((px >>> 16) & 0xFF); // R
            raw[idx + 2] = (byte) ((px >>>  8) & 0xFF); // G
            raw[idx + 3] = (byte) ( px         & 0xFF); // B
        }
        byte[] compressed = deflate(raw);
        host.setImage(w, h, compressed);
    }

    /**
     * Upload a 3D voxel grid (Phase 3).
     *
     * Lua shape:
     *   projector.setVoxelGrid({
     *     sizeX = 16, sizeY = 16, sizeZ = 16,
     *     palette = { "minecraft:stone", "#00FFFF", "#AAFF0000" },
     *     indexes = { 0, 0, 1, 2, ...  -- sizeX*sizeY*sizeZ entries, row-major X→Y→Z
     *                                  -- 0 = empty, 1..#palette = palette slot
     *   })
     *
     * <p>Per-axis dimension cap is {@value #MAX_VOXEL_DIM}. The projector's
     * current mode stays as set (default {@code 3d_culled} when none was
     * explicitly picked); call {@link #setMode(String)} with {@code "3d_full"}
     * before/after this to opt into heterogeneous-boundary face emission.
     */
    @LuaFunction(mainThread = true)
    public final void setVoxelGrid(Map<?, ?> gridTable) throws LuaException {
        if (gridTable == null) throw new LuaException("grid table required");

        int sx = intField(gridTable, "sizeX");
        int sy = intField(gridTable, "sizeY");
        int sz = intField(gridTable, "sizeZ");
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new LuaException("sizeX/sizeY/sizeZ must be > 0");
        }
        if (sx > MAX_VOXEL_DIM || sy > MAX_VOXEL_DIM || sz > MAX_VOXEL_DIM) {
            throw new LuaException("voxel dimension exceeds cap of " + MAX_VOXEL_DIM);
        }

        Object paletteObj = gridTable.get("palette");
        if (!(paletteObj instanceof Map<?, ?> paletteMap) || paletteMap.isEmpty()) {
            throw new LuaException("palette must be a non-empty table");
        }
        int paletteLen = paletteMap.size();
        if (paletteLen > 255) {
            // Single-byte index encoding — 255 distinct palette slots + empty(0).
            throw new LuaException("palette too large (max 255 entries)");
        }
        int[] palette = new int[paletteLen];
        var level = host.getLevel();
        for (int i = 0; i < paletteLen; i++) {
            Object entry = lookup1Based(paletteMap, i + 1);
            if (entry == null) throw new LuaException("palette entry " + (i + 1) + " missing");
            palette[i] = VoxelPalette.resolve(entry, level);
        }

        Object indexesObj = gridTable.get("indexes");
        if (!(indexesObj instanceof Map<?, ?> indexesMap)) {
            throw new LuaException("indexes must be a table of palette indices");
        }
        int expected = sx * sy * sz;
        if (indexesMap.size() != expected) {
            throw new LuaException("index count " + indexesMap.size() +
                    " != sizeX*sizeY*sizeZ (" + expected + ")");
        }
        byte[] rawIndexes = new byte[expected];
        for (int i = 0; i < expected; i++) {
            Object v = lookup1Based(indexesMap, i + 1);
            long n = (v instanceof Number num) ? num.longValue() : 0L;
            if (n < 0 || n > paletteLen) {
                throw new LuaException("index " + n + " at position " + (i + 1) +
                        " out of range [0.." + paletteLen + "]");
            }
            rawIndexes[i] = (byte) (n & 0xFF);
        }

        // Keep whichever 3D mode the projector is in; default to CULLED.
        HologramMode current = host.hologramMode();
        HologramMode voxelMode =
                (current == HologramMode.MODE_3D_FULL) ? HologramMode.MODE_3D_FULL
                                                       : HologramMode.MODE_3D_CULLED;

        byte[] compressed = deflate(rawIndexes);
        host.setVoxelGrid(voxelMode, sx, sy, sz, palette, compressed);
    }

    /**
     * Upload an entity-marker overlay (Phase 5). Replaces any current
     * image/voxel/markers content and switches to MARKERS mode.
     *
     * Lua shape:
     *   projector.setEntityMarkers({
     *     sizeX = 16, sizeY = 16, sizeZ = 16,
     *     markers = {
     *       { x=5,   y=8,   z=3,   shape="cube",   color="#FF0000", scale=1.0 },
     *       { x=10.5,y=2.5, z=10,  shape="sphere", color="#00FF00", scale=1.5 },
     *       ...
     *     }
     *   })
     *
     * <p>shape: "cube" / "tetrahedron"(|"tetra") / "octahedron"(|"diamond") /
     * "sphere"(|"ball"). Unknown strings fall back to cube.
     * <p>color: any form {@link VoxelPalette#resolve} accepts (hex string,
     * "#AARRGGBB" hex with alpha, or a block ID — block's MapColor wins).
     * <p>scale: floating voxel-cell multiplier. Missing → 1.0.
     */
    @LuaFunction(mainThread = true)
    public final void setEntityMarkers(Map<?, ?> markersTable) throws LuaException {
        if (markersTable == null) throw new LuaException("markers table required");
        int sx = intField(markersTable, "sizeX");
        int sy = intField(markersTable, "sizeY");
        int sz = intField(markersTable, "sizeZ");
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new LuaException("sizeX/sizeY/sizeZ must be > 0");
        }

        MarkerPayload mp = parseMarkers(markersTable.get("markers"));
        host.setMarkers(sx, sy, sz, mp.count,
                mp.xs, mp.ys, mp.zs, mp.shapes, mp.colors,
                mp.scaleXs, mp.scaleYs, mp.scaleZs, mp.yaws, mp.pitches);
    }

    /** Phase 7f — overlay entity markers on top of the existing voxel
     *  grid WITHOUT evicting the voxel data. Switches the projector to
     *  COMPOSITE mode so the renderer draws voxels and markers together.
     *  Throws if no voxel grid is loaded (use setComposite to upload
     *  both atomically in that case). Intended for live mob tracking
     *  where the blueprint is static and markers update at ~2 Hz. */
    @LuaFunction(mainThread = true)
    public final void updateMarkers(Map<?, ?> markersTable) throws LuaException {
        if (markersTable == null) throw new LuaException("markers table required");
        int sx = intField(markersTable, "sizeX");
        int sy = intField(markersTable, "sizeY");
        int sz = intField(markersTable, "sizeZ");
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new LuaException("sizeX/sizeY/sizeZ must be > 0");
        }
        MarkerPayload mp = parseMarkers(markersTable.get("markers"));
        host.setCompositeMarkers(sx, sy, sz, mp.count,
                mp.xs, mp.ys, mp.zs, mp.shapes, mp.colors,
                mp.scaleXs, mp.scaleYs, mp.scaleZs, mp.yaws, mp.pitches);
    }

    /**
     * Upload a voxel grid + marker overlay atomically (Phase 5). Replaces
     * any current content and switches to COMPOSITE mode.
     *
     * Lua shape:
     *   projector.setComposite({
     *     voxelGrid = { sizeX=16, sizeY=16, sizeZ=16, palette={...}, indexes={...} },
     *     markers   = { { x=8, y=8, z=8, shape="cube", color="#FFAA00" }, ... },
     *   })
     *
     * Voxel grid parsing is identical to {@link #setVoxelGrid}. Markers
     * share the voxel grid's coordinate space (their sizeX/Y/Z is
     * inherited, so the markers table doesn't need its own size fields).
     */
    @LuaFunction(mainThread = true)
    public final void setComposite(Map<?, ?> compositeTable) throws LuaException {
        if (compositeTable == null) throw new LuaException("composite table required");

        // --- voxel half (same validation as setVoxelGrid) ---
        Object vgObj = compositeTable.get("voxelGrid");
        if (!(vgObj instanceof Map<?, ?> vg)) {
            throw new LuaException("voxelGrid table required");
        }
        int sx = intField(vg, "sizeX");
        int sy = intField(vg, "sizeY");
        int sz = intField(vg, "sizeZ");
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new LuaException("sizeX/sizeY/sizeZ must be > 0");
        }
        if (sx > MAX_VOXEL_DIM || sy > MAX_VOXEL_DIM || sz > MAX_VOXEL_DIM) {
            throw new LuaException("voxel dimension exceeds cap of " + MAX_VOXEL_DIM);
        }
        Object paletteObj = vg.get("palette");
        if (!(paletteObj instanceof Map<?, ?> paletteMap) || paletteMap.isEmpty()) {
            throw new LuaException("palette must be a non-empty table");
        }
        int paletteLen = paletteMap.size();
        if (paletteLen > 255) throw new LuaException("palette too large (max 255)");
        int[] palette = new int[paletteLen];
        var level = host.getLevel();
        for (int i = 0; i < paletteLen; i++) {
            Object entry = lookup1Based(paletteMap, i + 1);
            if (entry == null) throw new LuaException("palette entry " + (i + 1) + " missing");
            palette[i] = VoxelPalette.resolve(entry, level);
        }
        Object indexesObj = vg.get("indexes");
        if (!(indexesObj instanceof Map<?, ?> indexesMap)) {
            throw new LuaException("indexes must be a table of palette indices");
        }
        int expected = sx * sy * sz;
        if (indexesMap.size() != expected) {
            throw new LuaException("index count " + indexesMap.size() +
                    " != sizeX*sizeY*sizeZ (" + expected + ")");
        }
        byte[] rawIndexes = new byte[expected];
        for (int i = 0; i < expected; i++) {
            Object v = lookup1Based(indexesMap, i + 1);
            long n = (v instanceof Number num) ? num.longValue() : 0L;
            if (n < 0 || n > paletteLen) {
                throw new LuaException("index " + n + " at position " + (i + 1) +
                        " out of range [0.." + paletteLen + "]");
            }
            rawIndexes[i] = (byte) (n & 0xFF);
        }
        byte[] compressed = deflate(rawIndexes);

        // --- markers half ---
        MarkerPayload mp = parseMarkers(compositeTable.get("markers"));

        host.setComposite(sx, sy, sz, palette, compressed,
                mp.count, mp.xs, mp.ys, mp.zs, mp.shapes, mp.colors,
                mp.scaleXs, mp.scaleYs, mp.scaleZs, mp.yaws, mp.pitches);
    }

    /** Packed marker arrays built from a Lua markers table. */
    private record MarkerPayload(int count,
                                  float[] xs, float[] ys, float[] zs,
                                  byte[] shapes, int[] colors,
                                  float[] scaleXs, float[] scaleYs, float[] scaleZs,
                                  float[] yaws, float[] pitches) {}

    /** Parse a Lua {markers = {...}} table (may be null / empty). */
    private MarkerPayload parseMarkers(Object markersObj) throws LuaException {
        if (markersObj == null) {
            return new MarkerPayload(0, new float[0], new float[0], new float[0],
                    new byte[0], new int[0],
                    new float[0], new float[0], new float[0],
                    new float[0], new float[0]);
        }
        if (!(markersObj instanceof Map<?, ?> markersMap)) {
            throw new LuaException("markers must be a table");
        }
        int count = markersMap.size();
        if (count > MAX_MARKERS) {
            throw new LuaException("too many markers (max " + MAX_MARKERS + ")");
        }
        float[] xs = new float[count];
        float[] ys = new float[count];
        float[] zs = new float[count];
        byte[] shapes = new byte[count];
        int[] colors = new int[count];
        float[] sxs = new float[count];
        float[] sys = new float[count];
        float[] szs = new float[count];
        float[] yaws = new float[count];
        float[] pitches = new float[count];
        var level = host.getLevel();
        for (int i = 0; i < count; i++) {
            Object entryObj = lookup1Based(markersMap, i + 1);
            if (!(entryObj instanceof Map<?, ?> m)) {
                throw new LuaException("marker entry " + (i + 1) + " must be a table");
            }
            xs[i] = (float) numField(m, "x");
            ys[i] = (float) numField(m, "y");
            zs[i] = (float) numField(m, "z");
            Object shapeObj = m.get("shape");
            MarkerShape shape = shapeObj instanceof String s
                    ? MarkerShape.fromString(s)
                    : MarkerShape.CUBE;
            shapes[i] = (byte) shape.ordinal();
            Object colorObj = m.get("color");
            colors[i] = (colorObj == null)
                    ? 0xFFFFFFFF
                    : VoxelPalette.resolve(colorObj, level);
            // scale: accepts either a number (uniform) or a {x=,y=,z=} table.
            Object scaleObj = m.get("scale");
            float sU = 1.0f, sX = 1.0f, sY = 1.0f, sZ = 1.0f;
            if (scaleObj instanceof Number n) {
                sU = n.floatValue(); sX = sU; sY = sU; sZ = sU;
            } else if (scaleObj instanceof Map<?, ?> sm) {
                Object sxO = sm.get("x"), syO = sm.get("y"), szO = sm.get("z");
                if (sxO instanceof Number sxN) sX = sxN.floatValue();
                if (syO instanceof Number syN) sY = syN.floatValue();
                if (szO instanceof Number szN) sZ = szN.floatValue();
            }
            sxs[i] = sX; sys[i] = sY; szs[i] = sZ;
            // orientation: yaw/pitch in degrees (MC convention). Optional.
            Object yawObj   = m.get("yaw");
            Object pitchObj = m.get("pitch");
            yaws[i]    = (yawObj   instanceof Number yn) ? yn.floatValue() : 0f;
            pitches[i] = (pitchObj instanceof Number pn) ? pn.floatValue() : 0f;
        }
        return new MarkerPayload(count, xs, ys, zs, shapes, colors,
                sxs, sys, szs, yaws, pitches);
    }

    /**
     * Pick the hologram's rendering mode. Accepts:
     * <ul>
     *   <li>{@code "2d"} — flat image (Phase 2)</li>
     *   <li>{@code "3d"} or {@code "3d_culled"} — voxel mesh, silhouette only
     *       (Phase 3)</li>
     *   <li>{@code "3d_full"} — voxel mesh including heterogeneous-colour
     *       internal faces</li>
     * </ul>
     *
     * <p>Setting a mode before its content kind has been uploaded leaves the
     * hologram blank but preserves the choice for the next upload. Setting a
     * mode whose kind differs from the current content evicts the current
     * payload on the next upload (one kind per projector).
     */
    @LuaFunction(mainThread = true)
    public final void setMode(String newMode) throws LuaException {
        if (newMode == null) throw new LuaException("mode required");
        String norm = newMode.toLowerCase();
        HologramMode target = switch (norm) {
            case "2d"                  -> HologramMode.MODE_2D;
            case "3d", "3d_culled"     -> HologramMode.MODE_3D_CULLED;
            case "3d_full"             -> HologramMode.MODE_3D_FULL;
            default -> throw new LuaException(
                    "unknown mode '" + newMode + "' — expected 2d / 3d / 3d_culled / 3d_full");
        };
        host.setHologramMode(target);
    }

    @LuaFunction(mainThread = true)
    public final void clear() { host.clearContent(); }

    // ---- visibility --------------------------------------------------------

    @LuaFunction(mainThread = true)
    public final void show() { host.setVisible(true); }

    @LuaFunction(mainThread = true)
    public final void hide() { host.setVisible(false); }

    @LuaFunction(mainThread = true)
    public final boolean isVisible() { return host.isVisible(); }

    // ---- transform ---------------------------------------------------------

    @LuaFunction(mainThread = true)
    public final void setScale(double sx, double sy, double sz) {
        host.setScale(sx, sy, sz);
    }

    @LuaFunction(mainThread = true)
    public final void setOffset(double dx, double dy, double dz) {
        host.setOffset(dx, dy, dz); // BE clamps dy >= 0.5
    }

    @LuaFunction(mainThread = true)
    public final void setRotation(double yaw, double pitch, double roll) {
        host.setRotation(yaw, pitch, roll);
    }

    @LuaFunction(mainThread = true)
    public final void setColor(int argb) { host.setColor(argb); }

    /** Phase 7d — global alpha multiplier. Range 0.0 (fully transparent)
     *  to 1.0 (fully opaque). Overrides the legacy translucent caps so
     *  per-voxel alpha (e.g. palette entry "#80FFFFFF") shows through.
     *  Useful for "glass-like" voxels and for fading the whole hologram
     *  in/out without changing content. */
    @LuaFunction(mainThread = true)
    public final void setAlpha(double multiplier) {
        host.setAlphaMultiplier((float) multiplier);
    }

    /** Restore the default translucent cap behavior (180 for 2D, 60 for
     *  voxels). Identical to {@code setAlpha(0.706)} for 2D or
     *  {@code setAlpha(0.235)} for voxels but expressed via a sentinel so
     *  downstream code knows it's the default. */
    @LuaFunction(mainThread = true)
    public final void clearAlpha() {
        host.setAlphaMultiplier(-1f);
    }

    @LuaFunction(mainThread = true)
    public final double getAlpha() {
        return host.getAlphaMultiplier();
    }

    // ---- camera pairing (Phase 8) ------------------------------------------

    /**
     * Pull the paired camera's current frustum frame (live, not buffered)
     * and render it to this projector as a composite of sampled voxel
     * blocks inside the cone plus entity markers. Throws if:
     * <ul>
     *   <li>no camera is paired (use the linking tool to pair one)</li>
     *   <li>the paired camera's chunk is unloaded</li>
     *   <li>the cone AABB exceeds {@code MAX_VOXEL_DIM} or
     *       {@code hologram.scanMaxVoxels}</li>
     * </ul>
     * Honors the projector's existing {@code alphaMultiplier} + color tint —
     * those live on the transform state and aren't affected by content mutations.
     */
    @LuaFunction(mainThread = true)
    public final boolean loadFromCamera() throws LuaException {
        BlockPos camPos = host.getPairedCamera();
        if (camPos == null) {
            throw new LuaException("no camera paired — use the linking tool: "
                    + "click camera, then click this projector");
        }
        Level lvl = host.getLevel();
        if (!(lvl instanceof ServerLevel serverLevel)) {
            throw new LuaException("projector not in a server level");
        }
        if (!serverLevel.isLoaded(camPos)) {
            throw new LuaException("paired camera at " + camPos.getX() + "," + camPos.getY()
                    + "," + camPos.getZ() + " is in an unloaded chunk");
        }
        BlockEntity camBE = serverLevel.getBlockEntity(camPos);
        if (!(camBE instanceof CameraBlockEntity cam)) {
            // BE missing or replaced — clear the dangling pair so future
            // calls get a cleaner error.
            host.clearPairedCamera();
            throw new LuaException("paired camera no longer exists (block removed or replaced)");
        }

        double coneAngle = ModConfig.CAMERA_CONE_ANGLE_DEGREES.get();
        double range     = ModConfig.CAMERA_CONE_RANGE.get();
        Map<String, Object> frame = FrustumScanner.scan(
                serverLevel, camPos, cam.getYawDeg(), cam.getPitchDeg(), coneAngle, range);
        CameraFrameRenderer.Frame rendered = CameraFrameRenderer.render(
                serverLevel, camPos, cam.getYawDeg(), cam.getPitchDeg(),
                coneAngle, range, CameraFrameRenderer.extractEntityList(frame));
        rendered.applyToComposite(host);
        host.setVisible(true);
        return true;
    }

    /**
     * Render a live 2D CCTV-style frame from the paired camera onto this
     * projector's 2D image display. Costs scale with voxel count, not pixel
     * count — 144p and 720p are similar cost because we project voxels, not
     * ray-cast pixels.
     *
     * <p>opts (all optional):
     * <ul>
     *   <li>{@code width}, {@code height} — pixels (aspect ratio is free).
     *       Capped by {@code camera.viewMaxWidth} / {@code viewMaxHeight}.</li>
     *   <li>{@code fov} — degrees. Default 60. Capped by {@code viewMaxFovDegrees} (150°).</li>
     *   <li>{@code skyColor} — hex string / ARGB int for frustum misses.</li>
     *   <li>{@code tint} — hex string / ARGB int multiplied into every pixel.</li>
     *   <li>{@code grayscale} — bool. Applies BT.601 luma before tint.</li>
     *   <li>{@code scanlines} — bool. Darkens every other row for CRT feel.</li>
     * </ul>
     */
    @LuaFunction(mainThread = true)
    public final boolean loadFromCamera2D(Optional<Map<?, ?>> optsMap) throws LuaException {
        BlockPos camPos = host.getPairedCamera();
        if (camPos == null) {
            throw new LuaException("no camera paired — use the linking tool: "
                    + "click camera, then click this projector");
        }
        Level lvl = host.getLevel();
        if (!(lvl instanceof ServerLevel sl)) {
            throw new LuaException("projector not in a server level");
        }
        if (!sl.isLoaded(camPos)) {
            throw new LuaException("paired camera chunk unloaded");
        }
        BlockEntity camBE = sl.getBlockEntity(camPos);
        if (!(camBE instanceof CameraBlockEntity)) {
            host.clearPairedCamera();
            throw new LuaException("paired camera no longer exists");
        }

        // Phase 8.3 — sticky opts. If user passed width/height/fov, store
        // them on the BE (broadcast via transform packet → clients resize
        // their FBO + adjust render projection). If no opts, the BE keeps
        // its current values (default 256×144 / 60° on fresh projectors).
        if (optsMap.isPresent()) {
            applyFeedOpts(host, optsMap.get());
        }
        host.setLiveCameraMode(true);
        // Heartbeat every call. If Lua stops calling (script terminated,
        // error, etc.), the BE's ticker auto-clears liveCameraMode after
        // LIVE_CAMERA_HEARTBEAT_TIMEOUT_MS so clients stop capturing.
        host.heartbeatLiveCamera();
        host.setVisible(true);
        return true;
    }

    /** Parse width / height / fov from a Lua opts table and apply them to
     *  the projector BE. Clamped to server config caps. Unspecified fields
     *  leave the BE's current value in place (sticky). */
    static void applyFeedOpts(HologramProjectorBlockEntity target, Map<?, ?> opts) throws LuaException {
        int w = target.getFeedWidth();
        int h = target.getFeedHeight();
        float fov = target.getFeedFov();
        Object wo = opts.get("width");
        if (wo instanceof Number n) w = n.intValue();
        Object ho = opts.get("height");
        if (ho instanceof Number n) h = n.intValue();
        Object fo = opts.get("fov");
        if (fo instanceof Number n) fov = n.floatValue();
        int maxW = ModConfig.CAMERA_VIEW_MAX_WIDTH.get();
        int maxH = ModConfig.CAMERA_VIEW_MAX_HEIGHT.get();
        double maxFov = ModConfig.CAMERA_VIEW_MAX_FOV_DEGREES.get();
        if (w < 16 || w > maxW) throw new LuaException(
                "width " + w + " out of range [16, " + maxW + "]");
        if (h < 16 || h > maxH) throw new LuaException(
                "height " + h + " out of range [16, " + maxH + "]");
        if (fov < 10.0f || fov > maxFov) throw new LuaException(
                "fov " + fov + "° out of range [10, " + maxFov + "]");
        target.setFeedOpts(w, h, fov);
    }

    /** Disable the live-camera feed. Static image / voxel content can be
     *  uploaded afterward via setImage / setVoxelGrid as usual. */
    @LuaFunction(mainThread = true)
    public final void clearLiveCamera() {
        host.setLiveCameraMode(false);
    }

    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getLinkedCamera() {
        BlockPos camPos = host.getPairedCamera();
        if (camPos == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> posOut = new LinkedHashMap<>();
        posOut.put("x", camPos.getX());
        posOut.put("y", camPos.getY());
        posOut.put("z", camPos.getZ());
        out.put("pos", posOut);
        Level lvl = host.getLevel();
        boolean loaded = lvl != null && lvl.isLoaded(camPos)
                && lvl.getBlockEntity(camPos) instanceof CameraBlockEntity;
        out.put("loaded", loaded);
        return out;
    }

    // ---- state read --------------------------------------------------------

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getState() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visible", host.isVisible());
        out.put("mode", host.hologramMode().name());
        out.put("imageWidth",  host.imageWidth());
        out.put("imageHeight", host.imageHeight());
        out.put("scale",    Map.of("x", host.getScaleX(), "y", host.getScaleY(), "z", host.getScaleZ()));
        out.put("offset",   Map.of("x", host.getOffsetX(), "y", host.getOffsetY(), "z", host.getOffsetZ()));
        out.put("rotation", Map.of("yaw", host.getYawDeg(), "pitch", host.getPitchDeg(), "roll", host.getRollDeg()));
        out.put("colorARGB", host.getColorARGB());
        return out;
    }

    // ---- internals ---------------------------------------------------------

    private static int intField(Map<?, ?> table, String key) throws LuaException {
        Object v = table.get(key);
        if (!(v instanceof Number n)) throw new LuaException("missing numeric field '" + key + "'");
        return n.intValue();
    }

    private static double numField(Map<?, ?> table, String key) throws LuaException {
        Object v = table.get(key);
        if (!(v instanceof Number n)) throw new LuaException("missing numeric field '" + key + "'");
        return n.doubleValue();
    }

    /** CC-Tweaked passes Lua arrays as Maps with 1-indexed keys. The key
     *  type is implementation-defined (Double historically, sometimes Long,
     *  rarely Integer) — probe each form to stay version-portable. */
    private static Object lookup1Based(Map<?, ?> map, int oneBasedIdx) {
        Object v = map.get((double) oneBasedIdx);
        if (v != null) return v;
        v = map.get((long) oneBasedIdx);
        if (v != null) return v;
        return map.get(oneBasedIdx);
    }

    private static byte[] deflate(byte[] raw) {
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2);
        byte[] chunk = new byte[4096];
        while (!def.finished()) {
            int n = def.deflate(chunk);
            out.write(chunk, 0, n);
        }
        def.end();
        return out.toByteArray();
    }
}
