package net.deceasedcraft.deceasedcc.peripherals;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.blocks.entity.AdvancedNetworkControllerBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.AdvancedNetworkControllerBlockEntity.DeviceType;
import net.deceasedcraft.deceasedcc.blocks.entity.AdvancedNetworkControllerBlockEntity.LinkedDevice;
import net.deceasedcraft.deceasedcc.blocks.entity.CameraBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.ChunkRadarBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.EntityTrackerBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.HologramProjectorBlockEntity;
import net.deceasedcraft.deceasedcc.core.ModConfig;
import net.deceasedcraft.deceasedcc.scan.ScanFile;
import net.deceasedcraft.deceasedcc.scan.ScanRegistry;
import net.deceasedcraft.deceasedcc.util.CameraFrameRenderer;
import net.deceasedcraft.deceasedcc.util.FrustumScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Phase 6a.1 — unified peripheral for the Advanced Network Controller.
 *
 * <p>Shape:
 * <ul>
 *   <li>Generic device-list + id-based management methods at the top.</li>
 *   <li>Per-device-type prefix-grouped proxy methods below
 *       ({@code hologramSetImage}, {@code cameraGetFrame}, {@code turretGetStatus}, …).</li>
 * </ul>
 * Adding a new wireless device type requires: a {@link DeviceType} enum
 * value, an instanceof check in
 * {@link AdvancedNetworkControllerBlockEntity#detectType}, plus the
 * {@code foo*} proxy methods below. Everything else (limits, listing,
 * linking, unlinking, unload handling) works automatically.
 */
public class AdvancedNetworkControllerPeripheral implements IPeripheral {

    private final AdvancedNetworkControllerBlockEntity host;
    private final Set<IComputerAccess> attached = ConcurrentHashMap.newKeySet();

    /** Phase 7a — shared BiConsumer the controller BE hands to every
     *  linked scanner's peripheral as an upstreamRelay. Captures {@code this}
     *  so scanners can fire events through us without knowing our type.
     *  When no computers are attached, fan-out is a no-op — cheap to leave
     *  wired even for idle controllers. First arg prepended to each event
     *  is this computer's attachment name, matching CC's peripheral-event
     *  convention. */
    public final BiConsumer<String, Object[]> eventRelay = (event, args) -> {
        for (IComputerAccess c : attached) {
            try {
                Object[] full = new Object[args.length + 1];
                full[0] = c.getAttachmentName();
                System.arraycopy(args, 0, full, 1, args.length);
                c.queueEvent(event, full);
            } catch (Exception ignored) {}
        }
    };

    public AdvancedNetworkControllerPeripheral(AdvancedNetworkControllerBlockEntity host) {
        this.host = host;
    }

    @Override public String getType() { return "advanced_network_controller"; }

    @Override
    public void attach(IComputerAccess c) {
        attached.add(c);
        // Wire the BE's motion-event sink on first attach. The sink is a
        // fan-out to every attached computer, so re-wiring on each attach
        // is idempotent and keeps the BE ignorant of IComputerAccess.
        host.setMotionEventSink(this::fanOutEvent);
    }

    /** Last computer detaching hides every linked projector — mirrors the
     *  individual projector's detach behaviour so powering off the
     *  controlling setup cleans up its ghost displays. Also unhooks the
     *  motion event sink so the BE isn't holding a stale callback. */
    @Override
    public void detach(IComputerAccess c) {
        attached.remove(c);
        if (!attached.isEmpty()) return;
        host.setMotionEventSink(null);
        for (LinkedDevice d : host.linkedDevices()) {
            if (d.type() != DeviceType.HOLOGRAM_PROJECTOR) continue;
            BlockEntity be = host.resolveBE(indexOf(d) + 1, DeviceType.HOLOGRAM_PROJECTOR);
            if (be instanceof HologramProjectorBlockEntity p) {
                try { p.hologramPeripheral().hide(); } catch (Exception ignored) {}
            }
        }
    }

    /** BE calls this with the motion event + payload. We fan out to every
     *  currently-attached computer. Called from the server tick thread;
     *  queueEvent is documented as thread-safe on IComputerAccess. */
    private void fanOutEvent(String event, Map<String, Object> payload) {
        for (IComputerAccess c : attached) {
            try { c.queueEvent(event, c.getAttachmentName(), payload); }
            catch (Exception ignored) {}
        }
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof AdvancedNetworkControllerPeripheral o && o.host == this.host;
    }

    // ---- helpers -----------------------------------------------------

    private int indexOf(LinkedDevice target) {
        List<LinkedDevice> list = host.linkedDevices();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) return i;
        }
        return -1;
    }

    private static Map<String, Object> posMap(BlockPos p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", p.getX()); m.put("y", p.getY()); m.put("z", p.getZ());
        return m;
    }

    private HologramProjectorPeripheral requireHologram(int id) throws LuaException {
        BlockEntity be = host.resolveBE(id, DeviceType.HOLOGRAM_PROJECTOR);
        if (!(be instanceof HologramProjectorBlockEntity p)) {
            throw new LuaException("id " + id + " is not a loaded hologram projector "
                    + "(check listDevices() — may be wrong type, unloaded, or removed)");
        }
        return p.hologramPeripheral();
    }

    // =================================================================
    // generic controller API
    // =================================================================

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getPosition() {
        return posMap(host.getBlockPos());
    }

    @LuaFunction(mainThread = true)
    public final int getMaxConnections() {
        return ModConfig.ADV_NETWORK_MAX_CONNECTIONS.get();
    }

    @LuaFunction(mainThread = true)
    public final int getDeviceCount() {
        return host.deviceCount();
    }

    /** List every linked device with id / name / type / pos / loaded flag.
     *  {@code name} is CC-style ({@code chunk_radar_0}, etc.) — 0-based
     *  counter per type. {@code id} is 1-based and is what every other
     *  method on this peripheral takes. */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> listDevices() {
        List<LinkedDevice> list = host.linkedDevices();
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            LinkedDevice d = list.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", i + 1);
            entry.put("name", host.getDeviceName(i + 1));
            entry.put("type", d.type().luaName());
            entry.put("pos", posMap(d.pos()));
            entry.put("loaded", host.resolveBE(i + 1, d.type()) != null);
            out.add(entry);
        }
        return out;
    }

    /** Resolve a CC-style {@code type_N} device name back to a 1-based id.
     *  Returns -1 if no linked device has that name. Companion to
     *  {@link #listDevices}'s {@code name} field so scripts can round-trip
     *  "looks up name → drives method by id". */
    @LuaFunction(mainThread = true)
    public final int getDeviceIdByName(String name) {
        return host.getDeviceIdByName(name);
    }

    /** Full device info by name — same shape as a {@link #listDevices}
     *  entry. Returns nil if no match. */
    @LuaFunction(mainThread = true)
    @Nullable
    public final Map<String, Object> getDeviceByName(String name) {
        int id = host.getDeviceIdByName(name);
        if (id < 1) return null;
        LinkedDevice d = host.getDevice(id);
        if (d == null) return null;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("name", host.getDeviceName(id));
        entry.put("type", d.type().luaName());
        entry.put("pos", posMap(d.pos()));
        entry.put("loaded", host.resolveBE(id, d.type()) != null);
        return entry;
    }

    @LuaFunction(mainThread = true)
    public final boolean removeDevice(int id) {
        return host.unlinkDeviceById(id);
    }

    @LuaFunction(mainThread = true)
    public final String getDeviceType(int id) throws LuaException {
        LinkedDevice d = host.getDevice(id);
        if (d == null) throw new LuaException("no device with id " + id);
        return d.type().luaName();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getDevicePos(int id) throws LuaException {
        LinkedDevice d = host.getDevice(id);
        if (d == null) throw new LuaException("no device with id " + id);
        return posMap(d.pos());
    }

    @LuaFunction(mainThread = true)
    public final boolean isDeviceLoaded(int id) {
        LinkedDevice d = host.getDevice(id);
        return d != null && host.resolveBE(id, d.type()) != null;
    }

    // =================================================================
    // hologram proxy methods (prefix: hologram*)
    // =================================================================

    @LuaFunction(mainThread = true)
    public final void hologramSetImage(int id, Map<?, ?> imageTable) throws LuaException {
        requireHologram(id).setImage(imageTable);
    }

    @LuaFunction(mainThread = true)
    public final void hologramSetVoxelGrid(int id, Map<?, ?> gridTable) throws LuaException {
        requireHologram(id).setVoxelGrid(gridTable);
    }

    @LuaFunction(mainThread = true)
    public final void hologramSetEntityMarkers(int id, Map<?, ?> markersTable) throws LuaException {
        requireHologram(id).setEntityMarkers(markersTable);
    }

    /** Phase 7f — update entity markers on top of an existing voxel
     *  hologram without clearing it. Requires a voxel grid already loaded
     *  (e.g. via hologramStitchScans). Switches the projector to COMPOSITE
     *  mode. Cheap enough to call at 2–5 Hz for live mob tracking. */
    @LuaFunction(mainThread = true)
    public final void hologramUpdateMarkers(int id, Map<?, ?> markersTable) throws LuaException {
        requireHologram(id).updateMarkers(markersTable);
    }

    @LuaFunction(mainThread = true)
    public final void hologramSetComposite(int id, Map<?, ?> compositeTable) throws LuaException {
        requireHologram(id).setComposite(compositeTable);
    }

    @LuaFunction(mainThread = true)
    public final void hologramSetMode(int id, String mode) throws LuaException {
        requireHologram(id).setMode(mode);
    }

    @LuaFunction(mainThread = true)
    public final void hologramClear(int id) throws LuaException {
        requireHologram(id).clear();
    }

    @LuaFunction(mainThread = true)
    public final void hologramShow(int id) throws LuaException {
        requireHologram(id).show();
    }

    @LuaFunction(mainThread = true)
    public final void hologramHide(int id) throws LuaException {
        requireHologram(id).hide();
    }

    @LuaFunction(mainThread = true)
    public final boolean hologramIsVisible(int id) throws LuaException {
        return requireHologram(id).isVisible();
    }

    @LuaFunction(mainThread = true)
    public final void hologramSetScale(int id, double sx, double sy, double sz) throws LuaException {
        requireHologram(id).setScale(sx, sy, sz);
    }

    @LuaFunction(mainThread = true)
    public final void hologramSetOffset(int id, double dx, double dy, double dz) throws LuaException {
        requireHologram(id).setOffset(dx, dy, dz);
    }

    @LuaFunction(mainThread = true)
    public final void hologramSetRotation(int id, double yaw, double pitch, double roll) throws LuaException {
        requireHologram(id).setRotation(yaw, pitch, roll);
    }

    @LuaFunction(mainThread = true)
    public final void hologramSetColor(int id, int argb) throws LuaException {
        requireHologram(id).setColor(argb);
    }

    /** Global alpha multiplier (0.0 = fully transparent, 1.0 = fully
     *  opaque). When set, overrides the default translucent cap so
     *  per-voxel alpha (palette entries with explicit alpha bytes like
     *  "#80FFFFFF") shows through. */
    @LuaFunction(mainThread = true)
    public final void hologramSetAlpha(int id, double multiplier) throws LuaException {
        requireHologram(id).setAlpha(multiplier);
    }

    @LuaFunction(mainThread = true)
    public final void hologramClearAlpha(int id) throws LuaException {
        requireHologram(id).clearAlpha();
    }

    @LuaFunction(mainThread = true)
    public final double hologramGetAlpha(int id) throws LuaException {
        return requireHologram(id).getAlpha();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> hologramGetState(int id) throws LuaException {
        return requireHologram(id).getState();
    }

    // --- Phase 7c — render a saved ScanFile as a hologram voxel grid ---

    /**
     * Render a chunk-radar ScanFile as a 3D voxel hologram on the linked
     * projector. The hologram's voxel grid spans the scan's bounding box;
     * each scan point becomes one voxel cell; air cells stay empty.
     *
     * <p>{@code options} (all optional):
     * <ul>
     *   <li><b>palette</b> — {@code { ["minecraft:stone"] = "#808080", ... }}
     *       Per-block overrides. Keys are block registry names as returned
     *       by the radar; values are hex strings or 24-bit RGB ints.</li>
     *   <li><b>defaultPalette</b> — default {@code true}. When false, skip
     *       the built-in palette and fall back straight to the ScanFile's
     *       point rgb values.</li>
     *   <li><b>highlightBlocks</b> — {@code { {x, y, z, color}, ... }}.
     *       Per-position overrides with highest precedence. Use to mark a
     *       specific rack, door, or point of interest in a bright color.</li>
     *   <li><b>includeOnly</b> — list of block ids; only these render.</li>
     *   <li><b>excludeTypes</b> — list of block ids; these always skip.</li>
     * </ul>
     * Color-resolution precedence: highlightBlocks &gt; palette &gt;
     * defaultPalette &gt; point.rgb. Throws if the scan volume exceeds
     * {@code hologram.scanMaxVoxels} (config).
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> hologramSetFromScan(int hologramId, String scanName,
                                                          Optional<Map<?, ?>> optionsOpt) throws LuaException {
        if (scanName == null || scanName.isBlank()) throw new LuaException("scan name required");
        ScanFile scan = ScanRegistry.get(scanName);
        if (scan == null) throw new LuaException("no scan file named '" + scanName + "'");
        Map<?, ?> options = optionsOpt.orElse(null);

        // Phase 8.1 — 2D image kind: bypass the voxel pipeline and call
        // setImage directly. This is what makes "video replay" work — each
        // recorded frame is a ScanFile with an image payload; playback loops
        // call hologramSetFromScan on successive names.
        if (scan.hasImage()) {
            BlockEntity projBE = host.resolveBE(hologramId, DeviceType.HOLOGRAM_PROJECTOR);
            if (!(projBE instanceof HologramProjectorBlockEntity pbe)) {
                throw new LuaException("id " + hologramId + " is not a loaded hologram projector");
            }
            pbe.setImage(scan.imageW(), scan.imageH(), scan.compressedImage());
            pbe.setVisible(true);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scan", scanName);
            result.put("kind", scan.kind());
            result.put("imageWidth",  scan.imageW());
            result.put("imageHeight", scan.imageH());
            return result;
        }

        Map<String, Object> result = renderScansToProjector(hologramId,
                java.util.Collections.singletonList(scan), options);
        result.put("scan", scanName);
        return result;
    }

    /**
     * Phase 7d — stitch multiple ScanFiles into one hologram. The voxel
     * grid spans the UNION of all input scans' bounding boxes; any blocks
     * inside the union that aren't covered by any scan stay empty. Same
     * options shape as {@link #hologramSetFromScan}: {@code palette},
     * {@code defaultPalette}, {@code highlightBlocks}, {@code includeOnly},
     * {@code excludeTypes}.
     *
     * <p>Use this with multiple radar outputs in a chain to build one
     * unified base blueprint. If two scans contain the same world cell,
     * the LATER one in the input list wins (later scans paint over earlier).
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> hologramStitchScans(int hologramId, Map<?, ?> scanNames,
                                                          Optional<Map<?, ?>> optionsOpt) throws LuaException {
        if (scanNames == null || scanNames.isEmpty()) throw new LuaException("scanNames list required");
        Map<?, ?> options = optionsOpt.orElse(null);
        List<ScanFile> scans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Object v : scanNames.values()) {
            if (v == null) continue;
            String name = v.toString();
            ScanFile sf = ScanRegistry.get(name);
            if (sf == null) throw new LuaException("no scan file named '" + name + "'");
            scans.add(sf);
            names.add(name);
        }
        if (scans.isEmpty()) throw new LuaException("scanNames list resolved to no valid scans");
        Map<String, Object> result = renderScansToProjector(hologramId, scans, options);
        result.put("scans", names);
        return result;
    }

    /** Shared converter behind {@link #hologramSetFromScan} and
     *  {@link #hologramStitchScans}. Computes the union bounding box,
     *  validates against the voxel-count cap, packs palette + indexes,
     *  pushes via the projector's {@code setVoxelGrid}. */
    private Map<String, Object> renderScansToProjector(int hologramId, List<ScanFile> scans,
                                                        @Nullable Map<?, ?> options) throws LuaException {
        HologramProjectorPeripheral proj = requireHologram(hologramId);

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (ScanFile s : scans) {
            if (s.minX() < minX) minX = s.minX();
            if (s.minY() < minY) minY = s.minY();
            if (s.minZ() < minZ) minZ = s.minZ();
            if (s.maxX() > maxX) maxX = s.maxX();
            if (s.maxY() > maxY) maxY = s.maxY();
            if (s.maxZ() > maxZ) maxZ = s.maxZ();
        }
        int sx = maxX - minX + 1;
        int sy = maxY - minY + 1;
        int sz = maxZ - minZ + 1;
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new LuaException("scan union has an empty bounding box");
        }
        long total = (long) sx * sy * sz;
        int cap = ModConfig.HOLOGRAM_SCAN_MAX_VOXELS.get();
        if (total > cap) {
            throw new LuaException("union volume " + total + " exceeds hologram.scanMaxVoxels (" + cap
                    + "). Shrink the scan area, drop scans, or raise the cap.");
        }

        // ---- parse options ----
        Map<String, String> overrideBlockColor = new java.util.HashMap<>();
        Map<Long, String> overrideHighlight = new java.util.HashMap<>();
        Set<String> includeOnly = null;
        Set<String> exclude = null;
        boolean useDefault = true;
        boolean useAtlas = false;
        String cutoutMode = "transparent"; // "transparent" | "solid"
        if (options != null) {
            Object p = options.get("palette");
            if (p instanceof Map<?, ?> pm) {
                for (Map.Entry<?, ?> e : pm.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    overrideBlockColor.put(e.getKey().toString(), toHex(e.getValue()));
                }
            }
            Object hl = options.get("highlightBlocks");
            if (hl instanceof Map<?, ?> hlm) {
                for (Object row : hlm.values()) {
                    if (!(row instanceof Map<?, ?> rm)) continue;
                    Object xo = rm.get("x"), yo = rm.get("y"), zo = rm.get("z"), co = rm.get("color");
                    if (!(xo instanceof Number && yo instanceof Number && zo instanceof Number) || co == null) continue;
                    int x = ((Number) xo).intValue();
                    int y = ((Number) yo).intValue();
                    int z = ((Number) zo).intValue();
                    overrideHighlight.put(packXYZ(x, y, z), toHex(co));
                }
            }
            Object inc = options.get("includeOnly");
            if (inc instanceof Map<?, ?> im) {
                includeOnly = new HashSet<>();
                for (Object v : im.values()) if (v != null) includeOnly.add(v.toString());
            }
            Object ex = options.get("excludeTypes");
            if (ex instanceof Map<?, ?> em) {
                exclude = new HashSet<>();
                for (Object v : em.values()) if (v != null) exclude.add(v.toString());
            }
            Object dp = options.get("defaultPalette");
            if (dp instanceof Boolean db) useDefault = db;
            Object ua = options.get("useBlockAtlas");
            if (ua instanceof Boolean ub) useAtlas = ub;
            Object cm = options.get("cutoutMode");
            if (cm instanceof String cms) cutoutMode = cms.toLowerCase();
        }

        // ---- pack palette + indexes ----
        LinkedHashMap<String, Integer> colorToSlot = new LinkedHashMap<>();
        int[] indexes = new int[(int) total];
        int skippedFiltered = 0, skippedOOB = 0, totalPoints = 0;

        for (ScanFile scan : scans) {
            for (ScanFile.Point pt : scan.points()) {
                totalPoints++;
                String blockId = null;
                Object bm = pt.meta().get("block");
                if (bm instanceof String bs) blockId = bs;

                if (exclude != null && blockId != null && exclude.contains(blockId)) { skippedFiltered++; continue; }
                if (includeOnly != null && (blockId == null || !includeOnly.contains(blockId))) {
                    skippedFiltered++; continue;
                }

                int lx = pt.x() - minX;
                int ly = pt.y() - minY;
                int lz = pt.z() - minZ;
                if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) { skippedOOB++; continue; }

                String hex;
                String hlHex = overrideHighlight.get(packXYZ(pt.x(), pt.y(), pt.z()));
                if (hlHex != null) {
                    hex = hlHex;
                } else if (blockId != null && overrideBlockColor.containsKey(blockId)) {
                    hex = overrideBlockColor.get(blockId);
                } else if (useAtlas && blockId != null) {
                    // Native-block-color atlas: client-generated per-block
                    // averages. Falls through to default-palette / pointHex
                    // if the block isn't in the atlas (e.g. new mod loaded
                    // after atlas generation).
                    Integer atlasArgb =
                            net.deceasedcraft.deceasedcc.core.ServerBlockAtlas.get(blockId);
                    if (atlasArgb != null) {
                        int finalArgb = atlasArgb;
                        if ("solid".equals(cutoutMode)) {
                            // Force alpha to 255 so cutout blocks render
                            // as opaque cubes in the hologram.
                            finalArgb = 0xFF000000 | (atlasArgb & 0x00FFFFFF);
                        }
                        hex = String.format("#%08X", finalArgb);
                    } else if (useDefault) {
                        String builtin = BUILTIN_PALETTE.get(blockId);
                        if (builtin == null) builtin = patternMatch(blockId);
                        hex = builtin != null ? builtin : pointHex(pt);
                    } else {
                        hex = pointHex(pt);
                    }
                } else if (useDefault && blockId != null) {
                    String builtin = BUILTIN_PALETTE.get(blockId);
                    if (builtin == null) builtin = patternMatch(blockId);
                    hex = builtin != null ? builtin : pointHex(pt);
                } else {
                    hex = pointHex(pt);
                }

                Integer slot = colorToSlot.get(hex);
                if (slot == null) {
                    if (colorToSlot.size() >= 255) {
                        slot = nearestSlot(hex, colorToSlot);
                    } else {
                        slot = colorToSlot.size() + 1;
                        colorToSlot.put(hex, slot);
                    }
                }
                int linear = lx + ly * sx + lz * sx * sy;
                indexes[linear] = slot;
            }
        }

        Map<Integer, String> paletteLua = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : colorToSlot.entrySet()) {
            paletteLua.put(e.getValue(), e.getKey());
        }
        Map<Integer, Integer> indexesLua = new LinkedHashMap<>();
        for (int i = 0; i < indexes.length; i++) indexesLua.put(i + 1, indexes[i]);

        Map<String, Object> grid = new LinkedHashMap<>();
        grid.put("sizeX", sx); grid.put("sizeY", sy); grid.put("sizeZ", sz);
        grid.put("palette", paletteLua);
        grid.put("indexes", indexesLua);

        // Phase 8 — if any input scan carries markers (i.e. a
        // "camera_snapshot"), reconstruct the full composite so replay
        // shows the entity overlay that was captured with the voxels.
        int markerCount = 0;
        for (ScanFile s : scans) markerCount += s.markers().size();
        if (markerCount > 0) {
            Map<Integer, Map<String, Object>> markersLua = new LinkedHashMap<>();
            int mi = 1;
            for (ScanFile s : scans) {
                for (ScanFile.Marker m : s.markers()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("x", (double) (m.x() - minX));
                    row.put("y", (double) (m.y() - minY));
                    row.put("z", (double) (m.z() - minZ));
                    row.put("shape", m.shape());
                    row.put("color", String.format("#%08X", m.rgb()));
                    row.put("scale", (double) m.scale());
                    markersLua.put(mi++, row);
                }
            }
            Map<String, Object> composite = new LinkedHashMap<>();
            composite.put("voxelGrid", grid);
            composite.put("markers", markersLua);
            proj.setComposite(composite);
        } else {
            proj.setVoxelGrid(grid);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sizeX", sx); result.put("sizeY", sy); result.put("sizeZ", sz);
        result.put("paletteSize", colorToSlot.size());
        result.put("pointsRendered", totalPoints - skippedFiltered - skippedOOB);
        result.put("pointsFiltered", skippedFiltered);
        result.put("markerCount", markerCount);
        return result;
    }

    /** Format a ScanFile.Point's rgb as a hex string, preserving alpha
     *  if non-zero so per-voxel translucency from the scanner survives. */
    private static String pointHex(ScanFile.Point pt) {
        int rgb = pt.rgb();
        if ((rgb & 0xFF000000) != 0) return String.format("#%08X", rgb);
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    /** Accept "#RRGGBB", "#AARRGGBB", or an integer (24- or 32-bit ARGB)
     *  and normalize to a hex string with alpha preserved when present.
     *  Per-voxel alpha relies on this — if the user passes 0x80FFFFFF or
     *  "#80FFFFFF" we must keep the 0x80 byte so VoxelPalette renders the
     *  voxel as 50% transparent. */
    private static String toHex(Object v) {
        if (v instanceof String s) {
            if (s.startsWith("#")) return s;
            try {
                long parsed = Long.parseLong(s, 16);
                return s.length() >= 8
                        ? String.format("#%08X", (int) parsed)
                        : String.format("#%06X", (int) (parsed & 0xFFFFFF));
            } catch (NumberFormatException e) { return "#808080"; }
        }
        if (v instanceof Number n) {
            long val = n.longValue();
            if ((val & 0xFF000000L) != 0L) return String.format("#%08X", (int) val);
            return String.format("#%06X", (int) (val & 0xFFFFFF));
        }
        return "#808080";
    }

    /** Pack an int triple into a single long for HashMap keying. */
    private static long packXYZ(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (long) (z & 0x3FFFFFF);
    }

    /** Fallback for palette overflow: pick the slot whose RGB is closest
     *  in Euclidean distance. Rare path — typical scans stay well under
     *  255 distinct colors. */
    private static int nearestSlot(String hex, LinkedHashMap<String, Integer> colorToSlot) {
        int target = parseHexRGB(hex);
        int bestSlot = 1; int bestDist = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : colorToSlot.entrySet()) {
            int dist = rgbDistance(target, parseHexRGB(e.getKey()));
            if (dist < bestDist) { bestDist = dist; bestSlot = e.getValue(); }
        }
        return bestSlot;
    }

    private static int parseHexRGB(String hex) {
        try { return Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) & 0xFFFFFF; }
        catch (NumberFormatException e) { return 0x808080; }
    }

    private static int rgbDistance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >>  8) & 0xFF) - ((b >>  8) & 0xFF);
        int db = ( a        & 0xFF) - ( b        & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    /** Pattern-based fallback for blocks not in the explicit palette.
     *  Covers the long tail of modded variants (all oak/spruce/whatever
     *  planks, every vinery wine rack size, all glass colors) with a
     *  handful of suffix checks. Returns null if nothing matches. */
    @Nullable
    private static String patternMatch(String blockId) {
        if (blockId.contains("wine_rack"))                       return "#9C27B0"; // vinery + addons
        if (blockId.endsWith("_planks") || blockId.endsWith("_plank")) return "#C8A064";
        if (blockId.endsWith("_log") || blockId.endsWith("_wood") || blockId.endsWith("_stem")) return "#8B6E42";
        if (blockId.endsWith("_leaves"))                         return "#4CAF50";
        if (blockId.endsWith("_door") || blockId.endsWith("_trapdoor")) return "#8A6634";
        if (blockId.endsWith("_glass") || blockId.endsWith("_glass_pane")) return "#B8E1F2";
        if (blockId.endsWith("_concrete"))                       return "#5A5A5A";
        if (blockId.endsWith("_terracotta"))                     return "#A15A3A";
        if (blockId.endsWith("_wool"))                           return "#C8C8C8";
        if (blockId.endsWith("_stairs") || blockId.endsWith("_slab") || blockId.endsWith("_wall")) return null;
        return null;
    }

    /** Built-in palette covering blocks we expect to show up in most
     *  base/cellar scans. Unlisted blocks fall through to
     *  {@link #patternMatch} then to the ScanFile point's rgb (scanner-
     *  assigned). Keys are the exact {@code ResourceLocation.toString()}
     *  form. */
    private static final Map<String, String> BUILTIN_PALETTE;
    static {
        Map<String, String> m = new java.util.HashMap<>();
        // --- terrain ---
        m.put("minecraft:stone",        "#7F7F7F");
        m.put("minecraft:cobblestone",  "#6E6E6E");
        m.put("minecraft:deepslate",    "#3D3D3D");
        m.put("minecraft:cobbled_deepslate", "#484848");
        m.put("minecraft:granite",      "#8A5A44");
        m.put("minecraft:diorite",      "#B7B7B7");
        m.put("minecraft:andesite",     "#8A8A8A");
        m.put("minecraft:dirt",         "#6F4E37");
        m.put("minecraft:coarse_dirt",  "#7A5A3A");
        m.put("minecraft:grass_block",  "#4CAF50");
        m.put("minecraft:podzol",       "#5A3A20");
        m.put("minecraft:mycelium",     "#6A4E6E");
        m.put("minecraft:sand",         "#E6D8A4");
        m.put("minecraft:gravel",       "#8A8585");
        m.put("minecraft:bedrock",      "#1A1A1A");
        // --- wood ---
        m.put("minecraft:oak_planks",        "#C8A064");
        m.put("minecraft:spruce_planks",     "#7A5936");
        m.put("minecraft:birch_planks",      "#E6D4A0");
        m.put("minecraft:jungle_planks",     "#B88A5A");
        m.put("minecraft:acacia_planks",     "#C06B3A");
        m.put("minecraft:dark_oak_planks",   "#4A3420");
        m.put("minecraft:mangrove_planks",   "#7A3424");
        m.put("minecraft:cherry_planks",     "#E8B5BE");
        m.put("minecraft:oak_log",           "#8B6E42");
        m.put("minecraft:spruce_log",        "#4A3420");
        m.put("minecraft:birch_log",         "#D8CEB4");
        m.put("minecraft:dark_oak_log",      "#352214");
        // --- glass ---
        m.put("minecraft:glass",        "#B8E1F2");
        m.put("minecraft:glass_pane",   "#B8E1F2");
        m.put("minecraft:tinted_glass", "#1E1E1E");
        // --- fluids ---
        m.put("minecraft:water",        "#1976D2");
        m.put("minecraft:lava",         "#FF6F00");
        // --- ores ---
        m.put("minecraft:iron_ore",           "#C0C0C0");
        m.put("minecraft:deepslate_iron_ore", "#8A8A95");
        m.put("minecraft:gold_ore",           "#FFC107");
        m.put("minecraft:deepslate_gold_ore", "#C09030");
        m.put("minecraft:diamond_ore",        "#00BCD4");
        m.put("minecraft:deepslate_diamond_ore", "#009AA6");
        m.put("minecraft:emerald_ore",        "#50C878");
        m.put("minecraft:redstone_ore",       "#D32F2F");
        m.put("minecraft:coal_ore",           "#2A2A2A");
        m.put("minecraft:lapis_ore",          "#1E3A8A");
        m.put("minecraft:copper_ore",         "#C87533");
        m.put("minecraft:ancient_debris",     "#5A3030");
        // --- utility ---
        m.put("minecraft:chest",        "#A0661E");
        m.put("minecraft:barrel",       "#8A5E2E");
        m.put("minecraft:furnace",      "#4A4A4A");
        m.put("minecraft:crafting_table", "#8A5E2E");
        m.put("minecraft:bookshelf",    "#B08050");
        m.put("minecraft:torch",        "#FFD060");
        m.put("minecraft:door",         "#8A6634");
        // --- wine racks (Vinery) — common pattern `vinery:<wood>_wine_rack[_size]` ---
        // Set a uniform purple for every wine_rack variant via mod-prefix
        // matching below; this explicit entry covers the undecorated key.
        m.put("vinery:wine_rack",       "#9C27B0");
        BUILTIN_PALETTE = java.util.Collections.unmodifiableMap(m);
    }

    // =================================================================
    // camera proxy methods (prefix: camera*) — Phase 6c fills these in
    // =================================================================

    // -----------------------------------------------------------------
    // camera scanning (Phase 6c)
    // -----------------------------------------------------------------

    /**
     * Validate that the id is a linked camera (regardless of whether it's
     * currently loaded — a camera in an unloaded chunk still has a buffer,
     * it just doesn't grow until the chunk loads).
     */
    private void requireCameraLink(int id) throws LuaException {
        LinkedDevice d = host.getDevice(id);
        if (d == null) throw new LuaException("no device with id " + id);
        if (d.type() != DeviceType.CAMERA) {
            throw new LuaException("device " + id + " is " + d.type().luaName()
                    + ", not a camera");
        }
    }

    /** Most recent frame in the rolling buffer for the given camera id.
     *  Returns nil if the buffer is empty (e.g. the camera was just linked
     *  and no scan interval has elapsed yet, or the chunk is unloaded). */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> cameraGetFrame(int id) throws LuaException {
        requireCameraLink(id);
        return host.getLatestFrame(id);
    }

    /** Buffered frame from {@code ticksAgo} server ticks back. 0 = newest.
     *  Snaps to the nearest captured frame (cadence is
     *  {@code camera.captureIntervalTicks}). Returns nil if the requested
     *  depth exceeds buffer size. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> cameraGetFrameAt(int id, int ticksAgo) throws LuaException {
        requireCameraLink(id);
        return host.getFrameAt(id, Math.max(0, ticksAgo));
    }

    /** Number of frames currently buffered for this camera. */
    @LuaFunction(mainThread = true)
    public final int cameraGetBufferSize(int id) throws LuaException {
        requireCameraLink(id);
        return host.getBufferSize(id);
    }

    /** Current buffer window length in server ticks (== bufferSize ×
     *  captureIntervalTicks). Divide by 20 to get seconds. */
    @LuaFunction(mainThread = true)
    public final int cameraGetBufferDurationTicks(int id) throws LuaException {
        requireCameraLink(id);
        return host.getBufferDurationTicks(id);
    }

    /** Drop every buffered frame for this camera. Buffer refills on
     *  subsequent scan ticks. */
    @LuaFunction(mainThread = true)
    public final void cameraClearBuffer(int id) throws LuaException {
        requireCameraLink(id);
        host.clearBuffer(id);
    }

    /** Persist the camera's buffered frame at {@code ticksAgo} as a named
     *  ScanFile of kind {@code "camera_snapshot"}. Returns the auto-generated
     *  name so it can be round-tripped through {@code hologramSetFromScan}
     *  for later replay. Name format: {@code camera_<id>_<gameTick>}.
     *
     *  <p>Voxel data in the snapshot reflects the world AT SAVE TIME (not
     *  the capture tick) because the ring buffer only stores entities, not
     *  a terrain snapshot. For typical use cases — blocks rarely change
     *  between capture and save — this is indistinguishable from a true
     *  historical replay, and it keeps the buffer lightweight. */
    @LuaFunction(mainThread = true)
    public final String cameraSaveSnapshot(int id, int ticksAgo) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        int safeTicks = Math.max(0, ticksAgo);
        Map<String, Object> frame = host.getFrameAt(id, safeTicks);
        if (frame == null) {
            throw new LuaException("no buffered frame at ticksAgo=" + safeTicks
                    + " (buffer size: " + host.getBufferSize(id) + ")");
        }
        if (!(host.getLevel() instanceof ServerLevel sl)) {
            throw new LuaException("controller not in a server level");
        }
        double coneAngle = ModConfig.CAMERA_CONE_ANGLE_DEGREES.get();
        double range     = ModConfig.CAMERA_CONE_RANGE.get();
        CameraFrameRenderer.Frame rendered = CameraFrameRenderer.render(
                sl, cam.getBlockPos(), cam.getYawDeg(), cam.getPitchDeg(),
                coneAngle, range, CameraFrameRenderer.extractEntityList(frame));
        long captureTick = frame.get("timestamp") instanceof Number tn
                ? tn.longValue() : sl.getGameTime();
        String name = "camera_" + id + "_" + captureTick;
        String author = "controller@" + host.getBlockPos().getX() + ","
                + host.getBlockPos().getY() + "," + host.getBlockPos().getZ();
        ScanRegistry.put(name, rendered.toScanFile(author));
        return name;
    }

    /** Phase 8.1 — 2D CCTV render into the projector's image (setImage) mode.
     *  Voxel-projection based: resolution-agnostic cost (144p ≈ 720p in ms).
     *  See {@link HologramProjectorPeripheral#loadFromCamera2D} for opts shape. */
    @LuaFunction(mainThread = true)
    public final boolean hologramLoadFromCamera2D(int hologramId, int cameraId,
                                                   Optional<Map<?, ?>> optsMap) throws LuaException {
        BlockEntity projBE = host.resolveBE(hologramId, DeviceType.HOLOGRAM_PROJECTOR);
        if (!(projBE instanceof HologramProjectorBlockEntity proj)) {
            throw new LuaException("id " + hologramId + " is not a loaded hologram projector");
        }
        // Controller-mediated setup: both projector and camera must be on
        // the same controller. Peer pair not required — this path writes
        // the pair here as a side effect so the client-side renderer can
        // resolve the camera from the projector.
        CameraBlockEntity cam = requireCameraBE(cameraId);
        if (proj.getPairedCamera() == null ||
                !proj.getPairedCamera().equals(cam.getBlockPos())) {
            proj.setPairedCamera(cam.getBlockPos());
            cam.setPairedProjector(proj.getBlockPos());
        }
        if (optsMap.isPresent()) {
            HologramProjectorPeripheral.applyFeedOpts(proj, optsMap.get());
        }
        proj.setLiveCameraMode(true);
        proj.heartbeatLiveCamera();
        proj.setVisible(true);
        return true;
    }

    /** Phase 8.3 — save a 2D CCTV frame via client-render (textured,
     *  matches live-feed quality). Returns the snapshot name immediately;
     *  the actual ScanFile appears in ScanRegistry AFTER the client
     *  responds. Lua scripts should wait on the
     *  {@code deceasedcc_snapshot_complete} event before replaying via
     *  {@link #hologramSetFromScan}.
     *
     *  <p>opts table (all optional):
     *  <ul>
     *    <li>{@code width} / {@code height} — capture resolution.
     *        Capped by server config.</li>
     *    <li>{@code fov} — field of view in degrees.</li>
     *  </ul>
     *  Defaults come from the projector's sticky feedOpts if
     *  {@code hologramId} given, else 256×144 / 60°.
     *
     *  <p>Server picks the closest eligible client to render the frame.
     *  If no client is in range, fires {@code deceasedcc_snapshot_failed}
     *  and returns null. Timeout = 3 s.
     */
    @LuaFunction(mainThread = true)
    public final String cameraSaveSnapshot2D(int cameraId, Optional<Map<?, ?>> optsMap) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(cameraId);
        if (!(host.getLevel() instanceof ServerLevel sl)) {
            throw new LuaException("controller not in a server level");
        }
        int w = 256, h = 144;
        float fov = 60f;
        if (optsMap.isPresent()) {
            Map<?, ?> opts = optsMap.get();
            Object wo = opts.get("width");  if (wo instanceof Number n) w = n.intValue();
            Object ho = opts.get("height"); if (ho instanceof Number n) h = n.intValue();
            Object fo = opts.get("fov");    if (fo instanceof Number n) fov = n.floatValue();
            int maxW = net.deceasedcraft.deceasedcc.core.ModConfig.CAMERA_VIEW_MAX_WIDTH.get();
            int maxH = net.deceasedcraft.deceasedcc.core.ModConfig.CAMERA_VIEW_MAX_HEIGHT.get();
            double maxFov = net.deceasedcraft.deceasedcc.core.ModConfig.CAMERA_VIEW_MAX_FOV_DEGREES.get();
            if (w < 16 || w > maxW) throw new LuaException("width out of range [16, " + maxW + "]");
            if (h < 16 || h > maxH) throw new LuaException("height out of range [16, " + maxH + "]");
            if (fov < 10f || fov > maxFov) throw new LuaException("fov out of range [10, " + maxFov + "]");
        }
        String name = net.deceasedcraft.deceasedcc.server.CameraSnapshotCoordinator.requestSnapshot(
                sl, cam.getBlockPos(), cam.getYawDeg(), cam.getPitchDeg(),
                w, h, fov, eventRelay);
        if (name == null) throw new LuaException("no eligible client in range to capture");
        return name;
    }

    /** Controller-mediated mirror of the projector's {@code loadFromCamera}.
     *  Does NOT require camera↔projector pairing — the {@code cameraId} and
     *  {@code hologramId} are looked up directly via the controller's
     *  linked-device list. Uses the camera's CURRENT yaw/pitch + cone config. */
    @LuaFunction(mainThread = true)
    public final boolean hologramLoadFromCamera(int hologramId, int cameraId) throws LuaException {
        BlockEntity projBE = host.resolveBE(hologramId, DeviceType.HOLOGRAM_PROJECTOR);
        if (!(projBE instanceof HologramProjectorBlockEntity proj)) {
            throw new LuaException("id " + hologramId + " is not a loaded hologram projector");
        }
        CameraBlockEntity cam = requireCameraBE(cameraId);
        if (!(host.getLevel() instanceof ServerLevel sl)) {
            throw new LuaException("controller not in a server level");
        }
        double coneAngle = ModConfig.CAMERA_CONE_ANGLE_DEGREES.get();
        double range     = ModConfig.CAMERA_CONE_RANGE.get();
        Map<String, Object> frame = FrustumScanner.scan(sl, cam.getBlockPos(),
                cam.getYawDeg(), cam.getPitchDeg(), coneAngle, range);
        CameraFrameRenderer.Frame rendered = CameraFrameRenderer.render(
                sl, cam.getBlockPos(), cam.getYawDeg(), cam.getPitchDeg(),
                coneAngle, range, CameraFrameRenderer.extractEntityList(frame));
        rendered.applyToComposite(proj);
        proj.setVisible(true);
        return true;
    }

    // -----------------------------------------------------------------
    // Phase 6d.1 — direction control
    // -----------------------------------------------------------------

    /** Resolve a camera BE by id, throwing if it's not loaded / wrong type. */
    private CameraBlockEntity requireCameraBE(int id) throws LuaException {
        requireCameraLink(id);
        BlockEntity be = host.resolveBE(id, DeviceType.CAMERA);
        if (!(be instanceof CameraBlockEntity cam)) {
            throw new LuaException("camera " + id + " is not currently loaded "
                    + "(chunk unloaded or block broken)");
        }
        return cam;
    }

    /** MC yaw convention: yaw=0 looks +Z, yaw=90 looks -X. Pitch positive =
     *  looking down. Computes the (yaw, pitch) that would make the camera's
     *  lens point from its block centre at the given world coord. */
    private static float[] aimAt(BlockPos cameraPos, double tx, double ty, double tz) {
        Vec3 centre = Vec3.atCenterOf(cameraPos);
        double dx = tx - centre.x;
        double dy = ty - centre.y;
        double dz = tz - centre.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        return new float[]{ yaw, pitch };
    }

    /** Set the camera's yaw/pitch directly (degrees). Third arg kept for
     *  Lua-API compatibility with the original Phase 6 spec shape but the
     *  camera has no roll axis — value is ignored. */
    @LuaFunction(mainThread = true)
    public final void cameraSetDirection(int id, double yaw, double pitch, double roll) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        cam.setDirection((float) yaw, (float) pitch);
    }

    /** Current yaw + pitch (degrees). Roll is always 0 — included so Lua
     *  can destructure {yaw, pitch, roll} symmetrically. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> cameraGetDirection(int id) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("yaw", cam.getYawDeg());
        out.put("pitch", cam.getPitchDeg());
        out.put("roll", 0.0);
        return out;
    }

    /** Aim at a world-space coord (e.g. a mob's position). */
    @LuaFunction(mainThread = true)
    public final void cameraLookAt(int id, double x, double y, double z) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        float[] yp = aimAt(cam.getBlockPos(), x, y, z);
        cam.setDirection(yp[0], yp[1]);
    }

    /** Aim at the centre of a block at (bx, by, bz). Convenience for
     *  integer block coords from radar/tracker results. */
    @LuaFunction(mainThread = true)
    public final void cameraLookAtBlock(int id, int bx, int by, int bz) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        float[] yp = aimAt(cam.getBlockPos(), bx + 0.5, by + 0.5, bz + 0.5);
        cam.setDirection(yp[0], yp[1]);
    }

    /** Snap back to yaw=0 / pitch=0 (lens points +Z horizontally). */
    @LuaFunction(mainThread = true)
    public final void cameraResetDirection(int id) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        cam.setDirection(0f, 0f);
    }

    // -----------------------------------------------------------------
    // Phase 6d.2 — lock-on / tracking
    // -----------------------------------------------------------------

    /** Lock the camera onto a live entity by UUID. Resolve immediately so
     *  we can throw a useful error if the entity isn't loaded. Once
     *  locked, the controller re-aims every tick by re-fetching the
     *  entity's current eye position — a despawn or chunk-unload keeps
     *  the lock at last-known coords until Lua calls
     *  {@code cameraClearLockTarget}.
     *
     *  <p>Typical pairing:
     *  <pre>
     *    local frame = ctrl.cameraGetFrame(id)
     *    if frame and #frame.entities > 0 then
     *      ctrl.cameraLockOnto(id, frame.entities[1].uuid)
     *    end
     *  </pre>
     */
    @LuaFunction(mainThread = true)
    public final void cameraLockOnto(int id, String entityUuid) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        UUID uuid;
        try { uuid = UUID.fromString(entityUuid); }
        catch (IllegalArgumentException ex) { throw new LuaException("invalid entity UUID: " + entityUuid); }
        if (!(host.getLevel() instanceof ServerLevel sl)) {
            throw new LuaException("camera controller is not in a server level");
        }
        Entity e = sl.getEntity(uuid);
        if (e == null) {
            throw new LuaException("no entity with UUID " + entityUuid + " currently loaded"
                    + " (hint: pull the uuid from cameraGetFrame's entities array)");
        }
        Vec3 eye = e.getEyePosition();
        cam.setLockTargetEntity(uuid, eye.x, eye.y, eye.z);
    }

    /** Lock onto a fixed world coord. Useful for panning to a door,
     *  window, or loot spot regardless of what's there. */
    @LuaFunction(mainThread = true)
    public final void cameraLockOntoBlock(int id, int bx, int by, int bz) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        cam.setLockTarget(bx + 0.5, by + 0.5, bz + 0.5);
    }

    /** Lock onto an exact floating-point coord. Convenience for Lua code
     *  that's already computed a precise aim point. */
    @LuaFunction(mainThread = true)
    public final void cameraLockOntoPos(int id, double x, double y, double z) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        cam.setLockTarget(x, y, z);
    }

    @LuaFunction(mainThread = true)
    public final void cameraClearLockTarget(int id) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        cam.clearLockTarget();
    }

    @LuaFunction(mainThread = true)
    public final boolean cameraIsLocked(int id) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        return cam.hasLockTarget();
    }

    /** Current lock target, or nil if not locked. Includes the entity
     *  UUID when the lock is entity-tracking so Lua can distinguish
     *  "locked onto mob X" from "locked onto static coord". */
    @LuaFunction(mainThread = true)
    @Nullable
    public final Map<String, Object> cameraGetLockTarget(int id) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        if (!cam.hasLockTarget()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", cam.getLockX());
        out.put("y", cam.getLockY());
        out.put("z", cam.getLockZ());
        UUID uuid = cam.getLockedEntityUUID();
        out.put("entityUuid", uuid == null ? null : uuid.toString());
        out.put("isEntity", uuid != null);
        return out;
    }

    // -----------------------------------------------------------------
    // Phase 6d.3 — motion trigger (deceasedcc_motion event)
    // -----------------------------------------------------------------

    /** Toggle the controller's per-scan motion diff for this camera.
     *  When enabled, the controller fires a {@code deceasedcc_motion}
     *  event whenever the filter's matching entity set changes. Event
     *  args: {@code (computerAttachmentName, payload)} where payload =
     *  {@code { cameraId, transition, count, entities }}. */
    @LuaFunction(mainThread = true)
    public final void cameraSetMotionTriggerEnabled(int id, boolean on) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        cam.setMotionTriggerEnabled(on);
    }

    @LuaFunction(mainThread = true)
    public final boolean cameraIsMotionTriggerEnabled(int id) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        return cam.isMotionTriggerEnabled();
    }

    /** Configure the motion filter. All keys are optional — omitting a
     *  key leaves that component unchanged. Explicit {@code nil} on a
     *  whitelist key clears it (= "allow all in that dimension"). Valid
     *  keys and shapes:
     *  <pre>
     *    categories   = {"hostile", "player", "passive", ... }  -- or nil
     *    includeTypes = {"minecraft:zombie", ...}               -- or nil
     *    excludeTypes = {"minecraft:armor_stand", ...}          -- or {}
     *    minDistance  = 2.0                                     -- blocks
     *    maxDistance  = 20.0                                    -- ≤0 = ∞
     *    triggers     = {"enter"} | {"enter","leave"} | {"enter","leave","present"}
     *  </pre>
     *  Category names: "hostile", "passive", "ambient", "water", "misc",
     *  "player" (pseudo), or any literal MobCategory name for forward-compat.
     */
    @LuaFunction(mainThread = true)
    public final void cameraSetMotionFilter(int id, Map<?, ?> filter) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        applyFilter(cam, filter);
    }

    /** Full filter snapshot as a Lua table. {@code maxDistance} comes
     *  back as -1 when unbounded (Lua has no Double.MAX_VALUE analogue). */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> cameraGetMotionFilter(int id) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        return cam.getFilterMap();
    }

    /** Reset the filter to broadest-possible defaults (no whitelists,
     *  no excludes, 0..∞ distance, triggers = {"enter"}). Leaves the
     *  enabled flag alone so scripts can re-configure without a pause. */
    @LuaFunction(mainThread = true)
    public final void cameraClearMotionFilter(int id) throws LuaException {
        CameraBlockEntity cam = requireCameraBE(id);
        cam.resetFilter();
    }

    /** Filter parser. Kept static to keep the option set trivially
     *  reviewable — every key handled is visible in one block. Unknown
     *  keys are silently ignored for forward-compat with future options. */
    private static void applyFilter(CameraBlockEntity cam, @Nullable Map<?, ?> filter) throws LuaException {
        if (filter == null) { cam.resetFilter(); return; }

        if (filter.containsKey("categories")) {
            Object v = filter.get("categories");
            if (v == null) cam.setFilterCategories(null);
            else if (v instanceof Map<?, ?> m) cam.setFilterCategories(toStringSet(m));
            else throw new LuaException("'categories' must be a list of strings or nil");
        }
        if (filter.containsKey("includeTypes")) {
            Object v = filter.get("includeTypes");
            if (v == null) cam.setFilterIncludeTypes(null);
            else if (v instanceof Map<?, ?> m) cam.setFilterIncludeTypes(toStringSet(m));
            else throw new LuaException("'includeTypes' must be a list of strings or nil");
        }
        if (filter.containsKey("excludeTypes")) {
            Object v = filter.get("excludeTypes");
            if (v == null) cam.setFilterExcludeTypes(new HashSet<>());
            else if (v instanceof Map<?, ?> m) cam.setFilterExcludeTypes(toStringSet(m));
            else throw new LuaException("'excludeTypes' must be a list of strings or nil");
        }

        double minD = cam.getFilterMinDistance();
        double maxD = cam.getFilterMaxDistance();
        boolean distDirty = false;
        if (filter.containsKey("minDistance")) {
            Object v = filter.get("minDistance");
            if (v instanceof Number n) { minD = n.doubleValue(); distDirty = true; }
            else if (v != null) throw new LuaException("'minDistance' must be a number");
        }
        if (filter.containsKey("maxDistance")) {
            Object v = filter.get("maxDistance");
            if (v instanceof Number n) {
                double d = n.doubleValue();
                maxD = d <= 0 ? Double.MAX_VALUE : d;
                distDirty = true;
            } else if (v != null) throw new LuaException("'maxDistance' must be a number");
        }
        if (distDirty) cam.setFilterDistance(minD, maxD);

        if (filter.containsKey("triggers")) {
            Object v = filter.get("triggers");
            if (v == null) cam.setTriggers(null); // fall back to default {"enter"}
            else if (v instanceof Map<?, ?> m) cam.setTriggers(toStringSet(m));
            else throw new LuaException("'triggers' must be a list of strings or nil");
        }
    }

    /** Lua arrays arrive as Map<Integer-like-key, Object>; iterate values
     *  and stringify. Sets preserve insertion order via LinkedHashSet for
     *  debuggability but the filter matcher doesn't care about order. */
    private static Set<String> toStringSet(Map<?, ?> luaTable) {
        Set<String> out = new LinkedHashSet<>();
        for (Object v : luaTable.values()) {
            if (v == null) continue;
            out.add(v.toString());
        }
        return out;
    }

    // =================================================================
    // chunk_radar proxy methods (prefix: radar*) — Phase 7a
    // =================================================================

    private ChunkRadarPeripheralAccessor requireRadar(int id) throws LuaException {
        LinkedDevice d = host.getDevice(id);
        if (d == null) throw new LuaException("no device with id " + id);
        if (d.type() != DeviceType.CHUNK_RADAR) {
            throw new LuaException("device " + id + " is " + d.type().luaName() + ", not a chunk_radar");
        }
        BlockEntity be = host.resolveBE(id, DeviceType.CHUNK_RADAR);
        if (!(be instanceof ChunkRadarBlockEntity radar)) {
            throw new LuaException("chunk_radar " + id + " is not currently loaded");
        }
        return new ChunkRadarPeripheralAccessor(radar.radarPeripheral());
    }

    private EntityTrackerPeripheralAccessor requireTracker(int id) throws LuaException {
        LinkedDevice d = host.getDevice(id);
        if (d == null) throw new LuaException("no device with id " + id);
        if (d.type() != DeviceType.ENTITY_TRACKER) {
            throw new LuaException("device " + id + " is " + d.type().luaName() + ", not an entity_tracker");
        }
        BlockEntity be = host.resolveBE(id, DeviceType.ENTITY_TRACKER);
        if (!(be instanceof EntityTrackerBlockEntity tracker)) {
            throw new LuaException("entity_tracker " + id + " is not currently loaded");
        }
        return new EntityTrackerPeripheralAccessor(tracker.trackerPeripheral());
    }

    /** Wrapper used only so the {@code requireRadar(id).peripheral()} style
     *  reads natively in the proxy methods below. */
    private record ChunkRadarPeripheralAccessor(net.deceasedcraft.deceasedcc.peripherals.ChunkRadarPeripheral peripheral) {}
    private record EntityTrackerPeripheralAccessor(net.deceasedcraft.deceasedcc.peripherals.EntityTrackerPeripheral peripheral) {}

    @LuaFunction(mainThread = true)
    public final Map<String, Object> radarGetPosition(int id) throws LuaException {
        return requireRadar(id).peripheral().getPosition();
    }

    @LuaFunction(mainThread = true)
    public final int radarGetScanRadius(int id) throws LuaException {
        return requireRadar(id).peripheral().getScanRadius();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> radarScan(int id, int radius) throws LuaException {
        return requireRadar(id).peripheral().scan(radius);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> radarScanOres(int id, int radius) throws LuaException {
        return requireRadar(id).peripheral().scanOres(radius);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> radarScanMod(int id, int radius, String modid) throws LuaException {
        return requireRadar(id).peripheral().scanMod(radius, modid);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> radarScanForBlock(int id, int radius, String blockId) throws LuaException {
        return requireRadar(id).peripheral().scanForBlock(radius, blockId);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> radarScanEmpty(int id, int radius) throws LuaException {
        return requireRadar(id).peripheral().scanEmpty(radius);
    }

    /** Start an async terrain scan over an absolute AABB. The controller
     *  relays the resulting {@code radar_scan_complete} event to every
     *  attached computer — caller doesn't need to attach directly to the
     *  radar. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> radarScanArea(int id, int x1, int y1, int z1,
                                                    int x2, int y2, int z2, String name) throws LuaException {
        return requireRadar(id).peripheral().scanArea(x1, y1, z1, x2, y2, z2, name);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> radarRescan(int id, String name) throws LuaException {
        return requireRadar(id).peripheral().rescan(name);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> radarGetScanProgress(int id) throws LuaException {
        return requireRadar(id).peripheral().getScanProgress();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> radarGetLastScan(int id) throws LuaException {
        return requireRadar(id).peripheral().getLastScan();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> radarInspect(int id, int x, int y, int z) throws LuaException {
        return requireRadar(id).peripheral().inspect(x, y, z);
    }

    @LuaFunction(mainThread = true)
    public final boolean radarHasLineOfSight(int id, double tx, double ty, double tz) throws LuaException {
        return requireRadar(id).peripheral().hasLineOfSight(tx, ty, tz);
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, String> radarListFiles(int id) throws LuaException {
        return requireRadar(id).peripheral().listFiles();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> radarReadFile(int id, String name) throws LuaException {
        return requireRadar(id).peripheral().readFile(name);
    }

    @LuaFunction(mainThread = true)
    public final boolean radarDeleteFile(int id, String name) throws LuaException {
        return requireRadar(id).peripheral().deleteFile(name);
    }

    // =================================================================
    // entity_tracker proxy methods (prefix: tracker*) — Phase 7a
    // =================================================================

    @LuaFunction(mainThread = true)
    public final Map<String, Object> trackerGetPosition(int id) throws LuaException {
        return requireTracker(id).peripheral().getPosition();
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> trackerGetEntities(int id, int radius) throws LuaException {
        return requireTracker(id).peripheral().getEntities(radius);
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> trackerGetHostile(int id, int radius) throws LuaException {
        return requireTracker(id).peripheral().getHostile(radius);
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> trackerGetPlayers(int id, int radius) throws LuaException {
        return requireTracker(id).peripheral().getPlayers(radius);
    }

    @LuaFunction(mainThread = true)
    @Nullable
    public final Map<String, Object> trackerGetNearest(int id, int radius) throws LuaException {
        return requireTracker(id).peripheral().getNearest(radius);
    }

    /** Scan AABB → ScanFile (named). Useful for pairing with a radar's
     *  scanArea over the same box — call both, stitch on the hologram. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> trackerScanArea(int id, int x1, int y1, int z1,
                                                      int x2, int y2, int z2, String name) throws LuaException {
        return requireTracker(id).peripheral().scanArea(x1, y1, z1, x2, y2, z2, name);
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, String> trackerListFiles(int id) throws LuaException {
        return requireTracker(id).peripheral().listFiles();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> trackerReadFile(int id, String name) throws LuaException {
        return requireTracker(id).peripheral().readFile(name);
    }

    @LuaFunction(mainThread = true)
    public final boolean trackerDeleteFile(int id, String name) throws LuaException {
        return requireTracker(id).peripheral().deleteFile(name);
    }

    @LuaFunction(mainThread = true)
    public final void trackerWatchEntity(int id, String uuid) throws LuaException {
        requireTracker(id).peripheral().watchEntity(uuid);
    }

    @LuaFunction(mainThread = true)
    public final void trackerClearWatch(int id) throws LuaException {
        requireTracker(id).peripheral().clearWatch();
    }

    @LuaFunction(mainThread = true)
    public final void trackerWatchArea(int id, int x1, int y1, int z1,
                                        int x2, int y2, int z2, String name) throws LuaException {
        requireTracker(id).peripheral().watchArea(x1, y1, z1, x2, y2, z2, name);
    }

    @LuaFunction(mainThread = true)
    public final void trackerWatchPoint(int id, int x, int y, int z, int radius, String name) throws LuaException {
        requireTracker(id).peripheral().watchPoint(x, y, z, radius, name);
    }

    @LuaFunction(mainThread = true)
    public final void trackerWatchRelative(int id, int dx1, int dy1, int dz1,
                                            int dx2, int dy2, int dz2, String name) throws LuaException {
        requireTracker(id).peripheral().watchRelative(dx1, dy1, dz1, dx2, dy2, dz2, name);
    }

    @LuaFunction(mainThread = true)
    public final boolean trackerRemoveAreaWatch(int id, String name) throws LuaException {
        return requireTracker(id).peripheral().removeAreaWatch(name);
    }

    @LuaFunction(mainThread = true)
    public final void trackerClearAreaWatches(int id) throws LuaException {
        requireTracker(id).peripheral().clearAreaWatches();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> trackerListAreaWatches(int id) throws LuaException {
        return requireTracker(id).peripheral().listAreaWatches();
    }

    // =================================================================
    // turret proxy methods (prefix: turret*)
    // =================================================================

    private net.deceasedcraft.deceasedcc.turrets.TurretMountPeripheral requireTurret(int id) throws LuaException {
        LinkedDevice d = host.getDevice(id);
        if (d == null) throw new LuaException("no device with id " + id);
        if (d.type() != DeviceType.TURRET) {
            throw new LuaException("device " + id + " is " + d.type().luaName() + ", not a turret");
        }
        BlockEntity be = host.resolveBE(id, DeviceType.TURRET);
        if (!(be instanceof net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity mount)) {
            throw new LuaException("turret " + id + " is not currently loaded");
        }
        return mount.mountPeripheral();
    }

    @LuaFunction(mainThread = true)
    public final boolean turretIsComputerControlled(int id) throws LuaException {
        return requireTurret(id).isComputerControlled();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> turretGetStatus(int id) throws LuaException {
        return requireTurret(id).getStatus();
    }

    @LuaFunction(mainThread = true)
    public final void turretSetEnabled(int id, boolean enabled) throws LuaException {
        requireTurret(id).setEnabled(enabled);
    }

    @LuaFunction(mainThread = true)
    public final void turretSetManualMode(int id, boolean manual) throws LuaException {
        requireTurret(id).setManualMode(manual);
    }

    @LuaFunction(mainThread = true)
    public final boolean turretFire(int id) throws LuaException {
        return requireTurret(id).fire();
    }

    @LuaFunction(mainThread = true)
    public final void turretSetAim(int id, double yaw, double pitch) throws LuaException {
        requireTurret(id).setAim(yaw, pitch);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> turretAimAtPoint(int id, double x, double y, double z) throws LuaException {
        return requireTurret(id).aimAtPoint(x, y, z);
    }

    @LuaFunction(mainThread = true)
    public final void turretForceTarget(int id, String uuid) throws LuaException {
        requireTurret(id).forceTarget(uuid);
    }

    @LuaFunction(mainThread = true)
    public final void turretClearTarget(int id) throws LuaException {
        requireTurret(id).clearTarget();
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> turretGetKillLog(int id) throws LuaException {
        return requireTurret(id).getKillLog();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> turretGetEffectiveStats(int id) throws LuaException {
        return requireTurret(id).getEffectiveStats();
    }

    @LuaFunction(mainThread = true)
    public final void turretSetAimMode(int id, String mode) throws LuaException {
        requireTurret(id).setAimMode(mode);
    }

    @LuaFunction(mainThread = true)
    public final void turretSetFireRate(int id, int ticks) throws LuaException {
        requireTurret(id).setFireRate(ticks);
    }

    @LuaFunction(mainThread = true)
    public final void turretSetTargetFilter(int id, String mode) throws LuaException {
        requireTurret(id).setTargetFilter(mode);
    }

    @LuaFunction(mainThread = true)
    public final void turretSetFriendlyFire(int id, boolean ff) throws LuaException {
        requireTurret(id).setFriendlyFire(ff);
    }

    @LuaFunction(mainThread = true)
    public final void turretAddTargetType(int id, String entityId) throws LuaException {
        requireTurret(id).addTargetType(entityId);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> turretGetDurability(int id) throws LuaException {
        return requireTurret(id).getDurability();
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> turretGetAmmoDetails(int id) throws LuaException {
        return requireTurret(id).getAmmoDetails();
    }

    @LuaFunction(mainThread = true)
    public final String turretGetTargetFilter(int id) throws LuaException {
        return requireTurret(id).getTargetFilter();
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, String> turretGetTargetTypes(int id) throws LuaException {
        return requireTurret(id).getTargetTypes();
    }

    @LuaFunction(mainThread = true)
    public final void turretSetAutoHunt(int id, boolean hunt) throws LuaException {
        requireTurret(id).setAutoHunt(hunt);
    }

    @LuaFunction(mainThread = true)
    public final boolean turretIsAutoHunting(int id) throws LuaException {
        return requireTurret(id).isAutoHunting();
    }
}
