package net.deceasedcraft.deceasedcc.util;

import dan200.computercraft.api.lua.LuaException;
import net.deceasedcraft.deceasedcc.blocks.entity.HologramProjectorBlockEntity;
import net.deceasedcraft.deceasedcc.core.ModConfig;
import net.deceasedcraft.deceasedcc.scan.ScanFile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * Phase 8 — shared converter: camera frustum scan → hologram composite.
 *
 * <p>Samples non-air blocks inside the camera's cone via BlockGetter (the
 * voxel base) and converts entity entries from a FrustumScanner frame
 * into marker overlays. The resulting {@link Frame} knows how to either
 * apply itself to a projector directly ({@link Frame#applyToComposite})
 * — the path used by {@code loadFromCamera} — or persist as a
 * {@link ScanFile} for later replay via {@code hologramSetFromScan}.
 *
 * <p>Both paths share this helper so the two representations never drift.
 *
 * <p>Voxel coordinates are ABSOLUTE world coords (matching the rest of
 * the scan pipeline). Markers are floats because live entity positions
 * are sub-block precision — rendering them as integer voxel cells would
 * snap them to a cube grid and lose half the visual detail.
 */
public final class CameraFrameRenderer {
    private CameraFrameRenderer() {}

    /** Matches {@code HologramProjectorPeripheral.MAX_VOXEL_DIM}. A cone
     *  whose AABB exceeds this per axis cannot be projected — the
     *  projector's packet format caps each axis at 64. */
    private static final int MAX_VOXEL_DIM = 64;

    /** Marker color / shape / scale for each entity category. Keeping
     *  these hardcoded here means Lua doesn't have to pass a classifier
     *  for the baseline "show me what the camera sees" experience; the
     *  snapshot is always visually consistent. Scripts that want custom
     *  colors can call {@code hologramUpdateMarkers} after load. */
    private static final int COLOR_PLAYER  = 0xFF66CCFF; // cyan — player
    private static final int COLOR_HOSTILE = 0xFFFF3344; // red  — monster category
    private static final int COLOR_PASSIVE = 0xFF66FF66; // green — creature category
    private static final int COLOR_AMBIENT = 0xFFAAAAFF; // soft purple — bats etc.
    private static final int COLOR_WATER   = 0xFF3377FF; // blue — water
    private static final int COLOR_OTHER   = 0xFFFFFF66; // yellow — everything else

    /** Computed frame. Carries both the voxel points and the marker overlays
     *  in ABSOLUTE world coords. Size() methods compute the grid dims that
     *  this frame would produce if applied. */
    public record Frame(
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            List<ScanFile.Point> points,
            List<ScanFile.Marker> markers,
            BlockPos cameraPos,
            float yawDeg, float pitchDeg,
            double coneAngleDeg, double coneRange,
            long gameTimeTick) {

        public int sizeX() { return maxX - minX + 1; }
        public int sizeY() { return maxY - minY + 1; }
        public int sizeZ() { return maxZ - minZ + 1; }
        public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

        /** Persist this frame as a named "camera_snapshot" ScanFile. Uses
         *  {@code System.currentTimeMillis()} for the ScanFile's
         *  {@code timestampMs} to stay consistent with terrain scans —
         *  game tick is carried separately via {@link #gameTimeTick}. */
        public ScanFile toScanFile(String author) {
            return new ScanFile(author, System.currentTimeMillis(), "camera_snapshot",
                    minX, minY, minZ, maxX, maxY, maxZ,
                    points, markers);
        }

        /** Push the frame to a hologram projector as a composite (voxel
         *  base + marker overlay). Honors the projector's existing
         *  alphaMultiplier / color tint (those live on the BE's transform
         *  state — setComposite only touches content). */
        public void applyToComposite(HologramProjectorBlockEntity target) {
            int sx = sizeX(), sy = sizeY(), sz = sizeZ();

            // --- pack voxel palette + indexes ---
            LinkedHashMap<Integer, Integer> argbToSlot = new LinkedHashMap<>();
            byte[] indexes = new byte[sx * sy * sz]; // defaults to 0 = empty
            for (ScanFile.Point pt : points) {
                int lx = pt.x() - minX;
                int ly = pt.y() - minY;
                int lz = pt.z() - minZ;
                // Defensive: points live strictly inside the AABB by construction,
                // but a stray point from a mutated scan shouldn't crash the render.
                if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) continue;
                int argb = pt.rgb();
                Integer slot = argbToSlot.get(argb);
                if (slot == null) {
                    if (argbToSlot.size() >= 255) {
                        slot = nearestSlot(argb, argbToSlot);
                    } else {
                        slot = argbToSlot.size() + 1;
                        argbToSlot.put(argb, slot);
                    }
                }
                int linear = lx + ly * sx + lz * sx * sy;
                indexes[linear] = (byte) (slot & 0xFF);
            }
            int[] palette = new int[argbToSlot.size()];
            for (Map.Entry<Integer, Integer> e : argbToSlot.entrySet()) {
                palette[e.getValue() - 1] = e.getKey();
            }
            byte[] compressed = deflate(indexes);

            // --- marker arrays (voxel-local coords) ---
            int count = markers.size();
            float[] xs = new float[count];
            float[] ys = new float[count];
            float[] zs = new float[count];
            byte[] shapes = new byte[count];
            int[] colors = new int[count];
            float[] scaleXs = new float[count];
            float[] scaleYs = new float[count];
            float[] scaleZs = new float[count];
            float[] yaws    = new float[count];
            float[] pitches = new float[count];
            for (int i = 0; i < count; i++) {
                ScanFile.Marker m = markers.get(i);
                xs[i] = m.x() - minX;
                ys[i] = m.y() - minY;
                zs[i] = m.z() - minZ;
                shapes[i] = (byte) MarkerShape.fromString(m.shape()).ordinal();
                colors[i] = m.rgb();
                float s = m.scale();
                scaleXs[i] = s; scaleYs[i] = s; scaleZs[i] = s;
                // yaws[i] / pitches[i] default to 0f.
            }

            target.setComposite(sx, sy, sz, palette, compressed,
                    count, xs, ys, zs, shapes, colors,
                    scaleXs, scaleYs, scaleZs, yaws, pitches);
        }
    }

    /**
     * Build a Frame from the current world state inside the cone. Blocks
     * are sampled at render-time (always "now") — when called with a
     * historical entity list from the ring buffer, the voxel base is still
     * present-time. This is acceptable for the camera-snapshot use case:
     * blocks rarely change in the few ticks the ring buffer spans, and
     * the entity list is the part that matters for motion capture.
     *
     * @param entityList optional list of entity entries in FrustumScanner
     *                   frame shape (keys: uuid, type, category, isPlayer,
     *                   name, pos{x,y,z}, distance). Null = no markers.
     * @throws LuaException if the sampled cone exceeds per-axis or total
     *         voxel caps. Caller should translate the message verbatim.
     */
    public static Frame render(ServerLevel level, BlockPos cameraPos,
                                float yawDeg, float pitchDeg,
                                double coneAngleDeg, double coneRange,
                                @Nullable List<Map<String, Object>> entityList) throws LuaException {
        Vec3 look = FrustumScanner.lookVec(yawDeg, pitchDeg);
        double halfAngleCos = Math.cos(Math.toRadians(coneAngleDeg * 0.5));
        double rangeSq = coneRange * coneRange;
        int rangeInt = (int) Math.ceil(coneRange);

        int ox = cameraPos.getX(), oy = cameraPos.getY(), oz = cameraPos.getZ();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        List<ScanFile.Point> points = new ArrayList<>();

        // Cone sampling. We iterate the circumscribing AABB and discard
        // cells outside the cone (distance or angle). Bit wasteful at very
        // wide angles, but at the typical 60° config it's ~30% cone volume
        // vs 100% AABB — still cheaper than projecting a frustum polygon.
        for (int dx = -rangeInt; dx <= rangeInt; dx++) {
            for (int dy = -rangeInt; dy <= rangeInt; dy++) {
                for (int dz = -rangeInt; dz <= rangeInt; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue; // camera cell
                    double sqd = dx * dx + dy * dy + dz * dz;
                    if (sqd > rangeSq) continue;
                    double len = Math.sqrt(sqd);
                    double dot = (dx * look.x + dy * look.y + dz * look.z) / len;
                    if (dot < halfAngleCos) continue; // outside cone

                    mut.set(ox + dx, oy + dy, oz + dz);
                    BlockState st = level.getBlockState(mut);
                    if (st.isAir()) continue;

                    int rgb = resolveBlockColor(st, level, mut);
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(st.getBlock());
                    Map<String, Object> meta = id != null
                            ? Map.of("block", id.toString())
                            : Map.of();
                    int bx = mut.getX(), by = mut.getY(), bz = mut.getZ();
                    points.add(new ScanFile.Point(bx, by, bz, rgb, meta));
                    if (bx < minX) minX = bx;
                    if (by < minY) minY = by;
                    if (bz < minZ) minZ = bz;
                    if (bx > maxX) maxX = bx;
                    if (by > maxY) maxY = by;
                    if (bz > maxZ) maxZ = bz;
                }
            }
        }

        // Entity → markers (keep even when there are no voxels so a cone
        // full of mobs in an open field still produces a useful overlay).
        List<ScanFile.Marker> markers = new ArrayList<>();
        if (entityList != null) {
            for (Map<String, Object> e : entityList) {
                ScanFile.Marker m = entryToMarker(e);
                if (m == null) continue;
                markers.add(m);
                int bx = (int) Math.floor(m.x());
                int by = (int) Math.floor(m.y());
                int bz = (int) Math.floor(m.z());
                if (bx < minX) minX = bx;
                if (by < minY) minY = by;
                if (bz < minZ) minZ = bz;
                if (bx > maxX) maxX = bx;
                if (by > maxY) maxY = by;
                if (bz > maxZ) maxZ = bz;
            }
        }

        // Empty cone (no blocks, no entities): collapse to a 1-voxel AABB
        // at the camera pos so the Frame struct stays well-formed. The
        // hologram will render nothing, which is correct.
        if (points.isEmpty() && markers.isEmpty()) {
            minX = maxX = ox;
            minY = maxY = oy;
            minZ = maxZ = oz;
        }

        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        if (sx > MAX_VOXEL_DIM || sy > MAX_VOXEL_DIM || sz > MAX_VOXEL_DIM) {
            throw new LuaException("camera cone AABB " + sx + "x" + sy + "x" + sz
                    + " exceeds " + MAX_VOXEL_DIM + " per axis; reduce camera.coneRange "
                    + "or camera.coneAngleDegrees");
        }
        long volume = (long) sx * sy * sz;
        int cap = ModConfig.HOLOGRAM_SCAN_MAX_VOXELS.get();
        if (volume > cap) {
            throw new LuaException("camera cone volume " + volume
                    + " exceeds hologram.scanMaxVoxels (" + cap + "); reduce "
                    + "camera.coneRange or raise hologram.scanMaxVoxels");
        }

        return new Frame(minX, minY, minZ, maxX, maxY, maxZ,
                points, markers,
                cameraPos.immutable(), yawDeg, pitchDeg,
                coneAngleDeg, coneRange,
                level.getGameTime());
    }

    /** Pull a FrustumScanner-shaped entity list out of a ring-buffer frame
     *  map. Returns null when the frame has no entities key. */
    @SuppressWarnings("unchecked")
    @Nullable
    public static List<Map<String, Object>> extractEntityList(@Nullable Map<String, Object> frame) {
        if (frame == null) return null;
        Object raw = frame.get("entities");
        if (!(raw instanceof List<?> list)) return null;
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    // --- internals -----------------------------------------------------

    @Nullable
    @SuppressWarnings("unchecked")
    private static ScanFile.Marker entryToMarker(Map<String, Object> entry) {
        Object posObj = entry.get("pos");
        if (!(posObj instanceof Map<?, ?> posMap)) return null;
        Object xo = posMap.get("x"), yo = posMap.get("y"), zo = posMap.get("z");
        if (!(xo instanceof Number && yo instanceof Number && zo instanceof Number)) return null;
        float x = ((Number) xo).floatValue();
        float y = ((Number) yo).floatValue();
        float z = ((Number) zo).floatValue();

        String category = entry.get("category") instanceof String s ? s : "";
        boolean isPlayer = entry.get("isPlayer") instanceof Boolean b ? b : Boolean.FALSE;
        int color;
        String shape;
        if (isPlayer) {
            color = COLOR_PLAYER;
            shape = "sphere";
        } else {
            switch (category) {
                case "monster":                      color = COLOR_HOSTILE; shape = "cube"; break;
                case "creature":                     color = COLOR_PASSIVE; shape = "cube"; break;
                case "ambient":                      color = COLOR_AMBIENT; shape = "octahedron"; break;
                case "water_creature":
                case "underground_water_creature":
                case "water_ambient":                color = COLOR_WATER;   shape = "tetrahedron"; break;
                default:                             color = COLOR_OTHER;   shape = "cube"; break;
            }
        }
        return new ScanFile.Marker(x, y, z, color, shape, 1.0f);
    }

    private static int resolveBlockColor(BlockState st, ServerLevel level, BlockPos pos) {
        int rgb;
        try {
            rgb = st.getMapColor(level, pos).col;
        } catch (Throwable t) {
            rgb = 0x808080;
        }
        if (rgb == 0) rgb = 0x808080;
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    /** Palette-overflow fallback. Cone scans with many unique MapColors are
     *  rare (most biome palettes cluster around ~30 colors), but a camera
     *  pointed across multiple biomes could hit 255. */
    private static int nearestSlot(int targetArgb, LinkedHashMap<Integer, Integer> argbToSlot) {
        int target = targetArgb & 0xFFFFFF;
        int bestSlot = 1;
        int bestDist = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> e : argbToSlot.entrySet()) {
            int d = rgbDistance(target, e.getKey() & 0xFFFFFF);
            if (d < bestDist) { bestDist = d; bestSlot = e.getValue(); }
        }
        return bestSlot;
    }

    private static int rgbDistance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >>  8) & 0xFF) - ((b >>  8) & 0xFF);
        int db = ( a        & 0xFF) - ( b        & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    private static byte[] deflate(byte[] raw) {
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
        byte[] chunk = new byte[4096];
        while (!def.finished()) {
            int n = def.deflate(chunk);
            out.write(chunk, 0, n);
        }
        def.end();
        return out.toByteArray();
    }
}
