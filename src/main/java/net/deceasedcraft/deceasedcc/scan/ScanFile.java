package net.deceasedcraft.deceasedcc.scan;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One named scan record produced by a radar, tracker, or camera-frame
 * snapshot. Coordinates are ABSOLUTE world coords so files can round-trip
 * through {@link ScanRegistry} persistence without rebinding when loaded.
 *
 * <p>{@code points} carries voxel cells (terrain or cone-sampled blocks).
 * {@code markers} (Phase 8) carries entity overlays for
 * {@code "camera_snapshot"} kinds so a replayed snapshot reconstructs the
 * composite voxel+markers view the camera produced live.</p>
 *
 * <p>{@code nbt} on each Point is the captured block-entity NBT for terrain
 * scans (present for chests, wine racks, barrels, shulker boxes, etc.) or
 * null otherwise. Lua scripts see it under {@code entry.meta.nbt}.</p>
 */
public record ScanFile(String author, long timestampMs, String kind,
                       int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                       List<Point> points, List<Marker> markers,
                       byte @Nullable [] compressedImage, int imageW, int imageH) {

    public ScanFile {
        points  = List.copyOf(points);
        markers = markers == null ? List.of() : List.copyOf(markers);
        // compressedImage stays nullable — null means "no image payload on
        // this scan" (the common case for terrain + 3D camera_snapshot kinds).
    }

    /** Back-compat constructor for pre-Phase-8 call sites (terrain scans
     *  from radar / tracker). Defaults markers to an empty list + no image. */
    public ScanFile(String author, long timestampMs, String kind,
                    int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                    List<Point> points) {
        this(author, timestampMs, kind, minX, minY, minZ, maxX, maxY, maxZ,
                points, List.of(), null, 0, 0);
    }

    /** Phase 8 constructor — terrain scans with optional entity markers
     *  (camera_snapshot kind). Image payload defaults to null. */
    public ScanFile(String author, long timestampMs, String kind,
                    int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                    List<Point> points, List<Marker> markers) {
        this(author, timestampMs, kind, minX, minY, minZ, maxX, maxY, maxZ,
                points, markers, null, 0, 0);
    }

    public boolean hasImage() {
        return compressedImage != null && imageW > 0 && imageH > 0;
    }

    public Map<String, Object> toLuaHeader() {
        Map<String, Object> h = new HashMap<>();
        h.put("author", author);
        h.put("timestamp", timestampMs);
        h.put("kind", kind);
        h.put("min", Map.of("x", minX, "y", minY, "z", minZ));
        h.put("max", Map.of("x", maxX, "y", maxY, "z", maxZ));
        h.put("count", points.size());
        h.put("markerCount", markers.size());
        if (hasImage()) {
            h.put("imageWidth",  imageW);
            h.put("imageHeight", imageH);
        }
        return h;
    }

    public Map<String, Object> toLuaFull() {
        Map<Integer, Map<String, Object>> entries = new java.util.LinkedHashMap<>();
        int i = 1;
        for (Point p : points) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("x", p.x());
            row.put("y", p.y());
            row.put("z", p.z());
            row.put("rgb", String.format("#%06X", p.rgb() & 0xFFFFFF));
            if (!p.meta().isEmpty() || p.nbt() != null) {
                Map<String, Object> meta = new HashMap<>(p.meta());
                if (p.nbt() != null) meta.put("nbt", nbtToLua(p.nbt()));
                row.put("meta", meta);
            }
            entries.put(i++, row);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(toLuaHeader());
        out.put("entries", Collections.unmodifiableMap(entries));
        if (!markers.isEmpty()) {
            Map<Integer, Map<String, Object>> mks = new java.util.LinkedHashMap<>();
            int j = 1;
            for (Marker m : markers) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("x", m.x()); row.put("y", m.y()); row.put("z", m.z());
                row.put("rgb", String.format("#%08X", m.rgb()));
                row.put("shape", m.shape());
                row.put("scale", m.scale());
                mks.put(j++, row);
            }
            out.put("markers", Collections.unmodifiableMap(mks));
        }
        return out;
    }

    // ---- NBT serialization ------------------------------------------------

    public CompoundTag toNbt() {
        CompoundTag t = new CompoundTag();
        t.putString("author", author);
        t.putLong("ts", timestampMs);
        t.putString("kind", kind);
        t.putInt("minX", minX); t.putInt("minY", minY); t.putInt("minZ", minZ);
        t.putInt("maxX", maxX); t.putInt("maxY", maxY); t.putInt("maxZ", maxZ);
        ListTag list = new ListTag();
        for (Point p : points) list.add(p.toNbt());
        t.put("points", list);
        if (!markers.isEmpty()) {
            ListTag mks = new ListTag();
            for (Marker m : markers) mks.add(m.toNbt());
            t.put("markers", mks);
        }
        if (hasImage()) {
            // Store as ByteArrayTag + int dims. NBT caps byte arrays at ~2 GB
            // which is fine for any frame we'd produce (720p compressed ≈
            // 300 KB). ScanRegistry SavedData disk write is the real bottleneck.
            t.putByteArray("imgData", compressedImage);
            t.putInt("imgW", imageW);
            t.putInt("imgH", imageH);
        }
        return t;
    }

    public static ScanFile fromNbt(CompoundTag t) {
        List<Point> pts = new java.util.ArrayList<>();
        ListTag list = t.getList("points", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            pts.add(Point.fromNbt(list.getCompound(i)));
        }
        List<Marker> mks = new java.util.ArrayList<>();
        if (t.contains("markers", Tag.TAG_LIST)) {
            ListTag mkl = t.getList("markers", Tag.TAG_COMPOUND);
            for (int i = 0; i < mkl.size(); i++) {
                mks.add(Marker.fromNbt(mkl.getCompound(i)));
            }
        }
        byte[] img = t.contains("imgData", Tag.TAG_BYTE_ARRAY) ? t.getByteArray("imgData") : null;
        int iw = t.contains("imgW") ? t.getInt("imgW") : 0;
        int ih = t.contains("imgH") ? t.getInt("imgH") : 0;
        return new ScanFile(
                t.getString("author"), t.getLong("ts"), t.getString("kind"),
                t.getInt("minX"), t.getInt("minY"), t.getInt("minZ"),
                t.getInt("maxX"), t.getInt("maxY"), t.getInt("maxZ"),
                pts, mks, img, iw, ih);
    }

    public record Point(int x, int y, int z, int rgb,
                        Map<String, Object> meta,
                        @Nullable CompoundTag nbt) {
        public Point {
            meta = meta == null ? Map.of() : Map.copyOf(meta);
        }

        /** Back-compat constructor with no NBT capture. */
        public Point(int x, int y, int z, int rgb, Map<String, Object> meta) {
            this(x, y, z, rgb, meta, null);
        }

        public CompoundTag toNbt() {
            CompoundTag t = new CompoundTag();
            t.putInt("x", x); t.putInt("y", y); t.putInt("z", z);
            t.putInt("rgb", rgb);
            if (!meta.isEmpty()) {
                CompoundTag m = new CompoundTag();
                for (Map.Entry<String, Object> e : meta.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof String s)        m.putString(e.getKey(), s);
                    else if (v instanceof Integer i)  m.putInt(e.getKey(), i);
                    else if (v instanceof Long l)     m.putLong(e.getKey(), l);
                    else if (v instanceof Float f)    m.putFloat(e.getKey(), f);
                    else if (v instanceof Double d)   m.putDouble(e.getKey(), d);
                    else if (v instanceof Boolean b)  m.putBoolean(e.getKey(), b);
                    // Skip non-primitives — nbt is stored in a dedicated slot below.
                }
                t.put("meta", m);
            }
            if (nbt != null) t.put("nbt", nbt);
            return t;
        }

        public static Point fromNbt(CompoundTag t) {
            Map<String, Object> m = new HashMap<>();
            if (t.contains("meta", Tag.TAG_COMPOUND)) {
                CompoundTag mt = t.getCompound("meta");
                for (String k : mt.getAllKeys()) {
                    Tag child = mt.get(k);
                    if (child instanceof net.minecraft.nbt.StringTag) m.put(k, child.getAsString());
                    else if (child instanceof net.minecraft.nbt.IntTag i) m.put(k, i.getAsInt());
                    else if (child instanceof net.minecraft.nbt.LongTag l) m.put(k, l.getAsLong());
                    else if (child instanceof net.minecraft.nbt.FloatTag f) m.put(k, (double) f.getAsFloat());
                    else if (child instanceof net.minecraft.nbt.DoubleTag d) m.put(k, d.getAsDouble());
                    else if (child instanceof net.minecraft.nbt.ByteTag b) m.put(k, b.getAsByte() != 0);
                }
            }
            CompoundTag nbt = t.contains("nbt", Tag.TAG_COMPOUND) ? t.getCompound("nbt") : null;
            return new Point(t.getInt("x"), t.getInt("y"), t.getInt("z"),
                    t.getInt("rgb"), m, nbt);
        }
    }

    /** Phase 8 — entity-overlay marker. Floats (not ints) because live
     *  entity positions are sub-block precision; markers need to render
     *  between voxel cells to look correct relative to the voxel cone. */
    public record Marker(float x, float y, float z, int rgb, String shape, float scale) {
        public Marker {
            if (shape == null || shape.isEmpty()) shape = "cube";
            if (scale <= 0.0f) scale = 1.0f;
        }

        public CompoundTag toNbt() {
            CompoundTag t = new CompoundTag();
            t.putFloat("x", x); t.putFloat("y", y); t.putFloat("z", z);
            t.putInt("rgb", rgb);
            t.putString("shape", shape);
            t.putFloat("scale", scale);
            return t;
        }

        public static Marker fromNbt(CompoundTag t) {
            return new Marker(
                    t.getFloat("x"), t.getFloat("y"), t.getFloat("z"),
                    t.getInt("rgb"),
                    t.contains("shape") ? t.getString("shape") : "cube",
                    t.contains("scale") ? t.getFloat("scale") : 1.0f);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object nbtToLua(Tag tag) {
        if (tag instanceof CompoundTag ct) {
            Map<String, Object> out = new HashMap<>();
            for (String key : ct.getAllKeys()) {
                Tag child = ct.get(key);
                if (child != null) out.put(key, nbtToLua(child));
            }
            return out;
        }
        if (tag instanceof ListTag lt) {
            Map<Integer, Object> out = new HashMap<>();
            for (int i = 0; i < lt.size(); i++) {
                Tag child = lt.get(i);
                if (child != null) out.put(i + 1, nbtToLua(child));
            }
            return out;
        }
        if (tag instanceof net.minecraft.nbt.ByteTag b)   return (int) b.getAsByte();
        if (tag instanceof net.minecraft.nbt.ShortTag s)  return (int) s.getAsShort();
        if (tag instanceof net.minecraft.nbt.IntTag i)    return i.getAsInt();
        if (tag instanceof net.minecraft.nbt.LongTag l)   return l.getAsLong();
        if (tag instanceof net.minecraft.nbt.FloatTag f)  return (double) f.getAsFloat();
        if (tag instanceof net.minecraft.nbt.DoubleTag d) return d.getAsDouble();
        return tag.getAsString();
    }
}
