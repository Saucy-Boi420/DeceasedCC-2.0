package net.deceasedcraft.deceasedcc.blocks.entity;

import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.deceasedcraft.deceasedcc.core.ModConfig;
import net.deceasedcraft.deceasedcc.peripherals.AdvancedNetworkControllerPeripheral;
import net.deceasedcraft.deceasedcc.peripherals.ChunkRadarPeripheral;
import net.deceasedcraft.deceasedcc.peripherals.EntityTrackerPeripheral;
import net.deceasedcraft.deceasedcc.peripherals.PeripheralBlockEntity;
import net.deceasedcraft.deceasedcc.util.FrustumScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Phase 6a.1 — BE for
 * {@link net.deceasedcraft.deceasedcc.blocks.AdvancedNetworkControllerBlock}.
 *
 * <p>Holds an ordered list of {@link LinkedDevice} entries (pos + cached
 * device type). Device type is detected at link time — if a user breaks a
 * turret and places a different block at that pos, the controller will
 * try to route turret methods to a non-turret BE and throw. They can
 * {@code removeDevice} + re-link the new block if needed.
 *
 * <p>1-based {@code id} is the list index + 1 so Lua can iterate naturally.
 * Removing a device shifts subsequent ids.
 */
public class AdvancedNetworkControllerBlockEntity extends PeripheralBlockEntity {
    private static final String TAG_DEVICES = "linkedDevices";
    private static final String TAG_POS  = "pos";
    private static final String TAG_TYPE = "type";

    /** Supported device types. Extending: add a value + detect instance in
     *  {@link #detectType(BlockEntity)} + wire proxy methods on the
     *  peripheral. */
    public enum DeviceType {
        TURRET,
        CAMERA,
        HOLOGRAM_PROJECTOR,
        CHUNK_RADAR,
        ENTITY_TRACKER;

        public String luaName() {
            switch (this) {
                case TURRET: return "turret";
                case CAMERA: return "camera";
                case HOLOGRAM_PROJECTOR: return "hologram_projector";
                case CHUNK_RADAR: return "chunk_radar";
                case ENTITY_TRACKER: return "entity_tracker";
                default: return "unknown";
            }
        }
    }

    public record LinkedDevice(BlockPos pos, DeviceType type) {}

    private AdvancedNetworkControllerPeripheral peripheralRef;
    private final List<LinkedDevice> linkedDevices = new ArrayList<>();

    /** Phase 7b — reverse index from a linked device's world pos to its
     *  controller's world pos. Lets the Linking Tool's CHAIN mode resolve
     *  "what controller is THIS device attached to?" in O(1) without
     *  iterating every loaded BE.
     *
     *  <p>Transient — rebuilt on {@link #load} and updated on link/unlink.
     *  Keyed by BlockPos only; dimension collisions are theoretically
     *  possible but vanishingly unlikely in practice for single-player
     *  worlds. If we ever ship a server build we'd key by
     *  {@code ResourceKey<Level>} + BlockPos. */
    private static final Map<BlockPos, BlockPos> DEVICE_TO_CONTROLLER = new java.util.concurrent.ConcurrentHashMap<>();

    /** Resolve which controller (if any) a given device block position is
     *  linked to. Returns null if unlinked or if the owning controller has
     *  been unloaded since the index was last populated. */
    @Nullable
    public static BlockPos findControllerForDevice(BlockPos devicePos) {
        return DEVICE_TO_CONTROLLER.get(devicePos.immutable());
    }

    // Phase 6c — per-camera rolling frame buffer. Keyed by 1-based camera id
    // (same id scheme as linkedDevices). Cleared when a camera is unlinked.
    // Transient — not NBT-persisted; buffer rebuilds as the controller ticks.
    private final Map<Integer, ArrayDeque<Map<String, Object>>> cameraBuffers = new HashMap<>();
    private int scanTickCounter = 0;

    // Phase 6d.3 — motion trigger state. Transient; rebuilds at next scan
    // tick after world load. Keyed by 1-based camera id. Value maps each
    // entity UUID seen in the previous scan to its full frame-entry, so
    // "leave" events can fire with details (type/name/pos) even though the
    // entity is no longer in the current scan.
    private final Map<Integer, Map<UUID, Map<String, Object>>> lastSeenEntities = new HashMap<>();

    /** Callback the controller's Peripheral injects so the BE can fire
     *  Lua events (queueEvent on each attached computer) without knowing
     *  about the IComputerAccess set directly. */
    @Nullable private BiConsumer<String, Map<String, Object>> motionEventSink;

    public void setMotionEventSink(@Nullable BiConsumer<String, Map<String, Object>> sink) {
        this.motionEventSink = sink;
    }

    public AdvancedNetworkControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ADVANCED_NETWORK_CONTROLLER.get(), pos, state);
    }

    @Override
    protected IPeripheral createPeripheral() {
        peripheralRef = new AdvancedNetworkControllerPeripheral(this);
        return peripheralRef;
    }

    // ---- link management --------------------------------------------

    public List<LinkedDevice> linkedDevices() {
        return Collections.unmodifiableList(linkedDevices);
    }

    public int deviceCount() {
        return linkedDevices.size();
    }

    /** Returns 1-based id if linked, -1 if already linked (same pos),
     *  or -2 if at capacity. Capacity comes from ModConfig. */
    public int linkDevice(BlockPos pos, DeviceType type, int maxCap) {
        BlockPos immut = pos.immutable();
        for (int i = 0; i < linkedDevices.size(); i++) {
            if (linkedDevices.get(i).pos().equals(immut)) return -1; // already linked
        }
        if (linkedDevices.size() >= maxCap) return -2;
        linkedDevices.add(new LinkedDevice(immut, type));
        DEVICE_TO_CONTROLLER.put(immut, worldPosition.immutable()); // Phase 7b — reverse index
        setChanged();
        return linkedDevices.size();
    }

    public boolean unlinkDeviceById(int oneBasedId) {
        int idx = oneBasedId - 1;
        if (idx < 0 || idx >= linkedDevices.size()) return false;
        LinkedDevice removed = linkedDevices.remove(idx);
        DEVICE_TO_CONTROLLER.remove(removed.pos()); // Phase 7b — reverse index
        // Buffer ids are 1-based and tied to index. Removing a device at idx
        // shifts every subsequent id down by 1, so rebuild the buffer map.
        cameraBuffers.clear();
        cameraBuffers.putAll(shiftById(cameraBuffers, oneBasedId));
        lastSeenEntities.clear();
        lastSeenEntities.putAll(shiftById(lastSeenEntities, oneBasedId));
        // Phase 7a — clear upstream relay on the scanner being detached so
        // it doesn't keep firing events into a controller it's no longer
        // linked to. Best-effort: if the BE is unloaded we skip (the relay
        // will naturally not re-attach on serverTick because the device
        // isn't in linkedDevices any more).
        if (level != null && !level.isClientSide && level.isLoaded(removed.pos())) {
            BlockEntity be = level.getBlockEntity(removed.pos());
            if (be instanceof ChunkRadarBlockEntity radar) {
                radar.radarPeripheral().setUpstreamRelay(null);
            } else if (be instanceof EntityTrackerBlockEntity tracker) {
                tracker.trackerPeripheral().setUpstreamRelay(null);
            }
        }
        setChanged();
        return true;
    }

    /** Drop the entry at {@code removedId} and shift subsequent ids down
     *  by one so the map keys stay aligned with the list indices. */
    private static <V> Map<Integer, V> shiftById(Map<Integer, V> src, int removedId) {
        Map<Integer, V> out = new HashMap<>();
        for (Map.Entry<Integer, V> e : src.entrySet()) {
            int oldId = e.getKey();
            if (oldId == removedId) continue;
            int newId = oldId > removedId ? oldId - 1 : oldId;
            out.put(newId, e.getValue());
        }
        return out;
    }

    @Nullable
    public LinkedDevice getDevice(int oneBasedId) {
        int idx = oneBasedId - 1;
        if (idx < 0 || idx >= linkedDevices.size()) return null;
        return linkedDevices.get(idx);
    }

    /** CC-style peripheral name for a linked device: {@code type_N} where
     *  N is the 0-based index of this device among all linked devices of
     *  the same type. First chunk_radar = "chunk_radar_0", second =
     *  "chunk_radar_1", etc. Mirrors how ComputerCraft auto-numbers
     *  monitors / printers / modems on a wired network. Returns null if
     *  the id is out of range. */
    @Nullable
    public String getDeviceName(int oneBasedId) {
        LinkedDevice d = getDevice(oneBasedId);
        if (d == null) return null;
        int perTypeIdx = 0;
        for (int i = 0; i < oneBasedId - 1; i++) {
            if (linkedDevices.get(i).type() == d.type()) perTypeIdx++;
        }
        return d.type().luaName() + "_" + perTypeIdx;
    }

    /** Reverse of {@link #getDeviceName}: resolve a {@code type_N} name
     *  back to a 1-based id. Returns -1 if no device has that name. */
    public int getDeviceIdByName(String name) {
        if (name == null) return -1;
        int under = name.lastIndexOf('_');
        if (under < 0) return -1;
        String typeName = name.substring(0, under);
        int wantedIdx;
        try { wantedIdx = Integer.parseInt(name.substring(under + 1)); }
        catch (NumberFormatException e) { return -1; }
        int seen = 0;
        for (int i = 0; i < linkedDevices.size(); i++) {
            LinkedDevice d = linkedDevices.get(i);
            if (d.type().luaName().equals(typeName)) {
                if (seen == wantedIdx) return i + 1;
                seen++;
            }
        }
        return -1;
    }

    /** Resolve + type-check a linked device's BE. Returns null if not
     *  loaded or not the expected type. */
    @Nullable
    public BlockEntity resolveBE(int oneBasedId, DeviceType expected) {
        LinkedDevice d = getDevice(oneBasedId);
        if (d == null || d.type() != expected || level == null) return null;
        return level.getBlockEntity(d.pos());
    }

    // ---- camera scanning (Phase 6c) ---------------------------------

    /**
     * Called every server tick by the block's ticker. Drives the per-camera
     * frustum-scan cadence + ring-buffer maintenance, and prunes any links
     * whose device block has been broken or replaced.
     */
    public void serverTick(ServerLevel level) {
        // Phase 6c.1 — auto-detach. Iterate in reverse so that when we
        // unlink an id, subsequent indices remain valid. Unlinking shifts
        // later ids down by 1; unlinkDeviceById also rebuilds the camera
        // buffer map to match.
        for (int i = linkedDevices.size() - 1; i >= 0; i--) {
            LinkedDevice d = linkedDevices.get(i);
            if (!level.isLoaded(d.pos())) continue; // unloaded chunk — pause, not prune
            BlockEntity be = level.getBlockEntity(d.pos());
            DeviceType actual = detectType(be);
            if (actual == null || actual != d.type()) {
                // Block was broken or replaced with a non-device.
                unlinkDeviceById(i + 1);
            }
        }

        // Phase 7a — idempotently wire upstream event relays on every
        // linked scanner. Runs each tick so post-load / freshly-linked
        // scanners get hooked up without any explicit registration path.
        // Cheap: one volatile write per scanner. Fan-out through the
        // relay is also a no-op when no computers are attached.
        //
        // Phase 7f fix — also drive chunk_radar scan jobs from here.
        // Without this, a radar in a loaded-but-non-ticking chunk (far
        // from the player, outside the ~32-block tick radius) never
        // advances its ChunkScanJob. The controller is always near the
        // computer so it IS ticking — using it as a "scan driver" lets
        // distant radars complete their scans regardless of player pos.
        if (peripheralRef != null) {
            for (LinkedDevice d : linkedDevices) {
                if (d.type() != DeviceType.CHUNK_RADAR && d.type() != DeviceType.ENTITY_TRACKER) continue;
                BlockEntity be = level.getBlockEntity(d.pos());
                if (be instanceof ChunkRadarBlockEntity radar) {
                    radar.radarPeripheral().setUpstreamRelay(peripheralRef.eventRelay);
                    radar.stepActiveJob(level);
                } else if (be instanceof EntityTrackerBlockEntity tracker) {
                    tracker.trackerPeripheral().setUpstreamRelay(peripheralRef.eventRelay);
                }
            }
        }

        // Phase 6d.2 — per-tick lock re-aim. Runs every tick (not gated by
        // scan cadence) so tracking feels responsive. For entity locks we
        // resolve UUID → live Entity each tick and refresh the target
        // coord before re-aiming. A small angular-delta threshold keeps
        // block-update packet spam down when the target is stationary.
        for (int i = 0; i < linkedDevices.size(); i++) {
            LinkedDevice d = linkedDevices.get(i);
            if (d.type() != DeviceType.CAMERA) continue;
            BlockEntity be = level.getBlockEntity(d.pos());
            if (!(be instanceof CameraBlockEntity cam) || !cam.hasLockTarget()) continue;

            if (cam.isLockingEntity()) {
                Entity e = level.getEntity(cam.getLockedEntityUUID());
                if (e != null && e.isAlive()) {
                    Vec3 aimPt = e.getEyePosition();
                    cam.updateLockTargetCoord(aimPt.x, aimPt.y, aimPt.z);
                }
                // Entity despawned / unloaded: keep last-known coord. Lua
                // can detect via the entity missing from cameraGetFrame
                // and choose to clearLockTarget manually.
            }

            float[] yp = aimFrom(d.pos(), cam.getLockX(), cam.getLockY(), cam.getLockZ());
            float dyaw   = Math.abs(shortAngularDelta(yp[0], cam.getYawDeg()));
            float dpitch = Math.abs(yp[1] - cam.getPitchDeg());
            if (dyaw > LOCK_REAIM_EPSILON_DEG || dpitch > LOCK_REAIM_EPSILON_DEG) {
                cam.setDirection(yp[0], yp[1]);
            }
        }

        scanTickCounter++;
        int interval = ModConfig.CAMERA_CAPTURE_INTERVAL_TICKS.get();
        if (scanTickCounter % interval != 0) return;
        int maxBuffer = Math.max(1, (ModConfig.CAMERA_BUFFER_DURATION_SECONDS.get() * 20) / interval);
        double coneAngle = ModConfig.CAMERA_CONE_ANGLE_DEGREES.get();
        double range     = ModConfig.CAMERA_CONE_RANGE.get();

        for (int i = 0; i < linkedDevices.size(); i++) {
            LinkedDevice d = linkedDevices.get(i);
            if (d.type() != DeviceType.CAMERA) continue;
            BlockEntity be = level.getBlockEntity(d.pos());
            if (!(be instanceof CameraBlockEntity cam)) continue;
            Map<String, Object> frame = FrustumScanner.scan(
                    level, d.pos(), cam.getYawDeg(), cam.getPitchDeg(), coneAngle, range);
            int cameraId = i + 1;
            ArrayDeque<Map<String, Object>> buf = cameraBuffers.computeIfAbsent(
                    cameraId, k -> new ArrayDeque<>());
            buf.addLast(frame);
            while (buf.size() > maxBuffer) buf.removeFirst();

            // Phase 6d.3 — motion trigger. Diff the filter-matching subset
            // against the previous scan for this camera. We do this at
            // scan cadence (not every tick) because it's paired with the
            // frustum output — computing enter/leave more often than we
            // actually capture would either double-scan or lie.
            if (cam.isMotionTriggerEnabled()) diffAndFireMotionEvents(cameraId, cam, frame);
        }
    }

    /** Acceptable angular error before triggering another block-update
     *  packet. 1° is tight enough that tracking looks locked-on, coarse
     *  enough that a near-stationary target stops spamming updates. */
    private static final float LOCK_REAIM_EPSILON_DEG = 1.0f;

    /** Shortest signed angular delta in degrees: (a − b) wrapped to
     *  [-180, 180]. Needed because raw subtraction on yaw 359° vs 1°
     *  would suggest a 358° delta rather than the true 2°. */
    private static float shortAngularDelta(float a, float b) {
        return ((a - b) % 360f + 540f) % 360f - 180f;
    }

    /** Compute (yaw, pitch) that aims a camera at block centre at the
     *  given world coord. MC yaw convention: yaw=0 → +Z, yaw=90 → -X;
     *  pitch positive = looking down. Mirrors
     *  {@code AdvancedNetworkControllerPeripheral#aimAt} — kept separate
     *  here to avoid a circular import from the BE into the peripheral. */
    private static float[] aimFrom(BlockPos cameraPos, double tx, double ty, double tz) {
        double cx = cameraPos.getX() + 0.5;
        double cy = cameraPos.getY() + 0.5;
        double cz = cameraPos.getZ() + 0.5;
        double dx = tx - cx;
        double dy = ty - cy;
        double dz = tz - cz;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        return new float[]{ yaw, pitch };
    }

    // ----- motion diff + filter matching -----

    @SuppressWarnings("unchecked")
    private void diffAndFireMotionEvents(int cameraId, CameraBlockEntity cam, Map<String, Object> frame) {
        // Build this scan's filter-passing UUID→entry map.
        Map<UUID, Map<String, Object>> current = new LinkedHashMap<>();
        Object rawList = frame.get("entities");
        if (rawList instanceof List<?> list) {
            for (Object obj : list) {
                if (!(obj instanceof Map<?, ?> raw)) continue;
                Map<String, Object> entry = (Map<String, Object>) raw;
                if (!matchesFilter(entry, cam)) continue;
                Object uuidObj = entry.get("uuid");
                if (!(uuidObj instanceof String uuidStr)) continue;
                try { current.put(UUID.fromString(uuidStr), entry); }
                catch (IllegalArgumentException ignored) {}
            }
        }

        Map<UUID, Map<String, Object>> lastSeen = lastSeenEntities.getOrDefault(cameraId, Collections.emptyMap());

        Set<String> trg = cam.getTriggers();

        if (trg.contains("enter")) {
            List<Map<String, Object>> entered = new ArrayList<>();
            for (Map.Entry<UUID, Map<String, Object>> e : current.entrySet()) {
                if (!lastSeen.containsKey(e.getKey())) entered.add(e.getValue());
            }
            if (!entered.isEmpty()) fireMotionEvent(cameraId, "enter", entered);
        }
        if (trg.contains("leave")) {
            List<Map<String, Object>> left = new ArrayList<>();
            for (Map.Entry<UUID, Map<String, Object>> e : lastSeen.entrySet()) {
                if (!current.containsKey(e.getKey())) left.add(e.getValue());
            }
            if (!left.isEmpty()) fireMotionEvent(cameraId, "leave", left);
        }
        if (trg.contains("present") && !current.isEmpty()) {
            fireMotionEvent(cameraId, "present", new ArrayList<>(current.values()));
        }

        lastSeenEntities.put(cameraId, current);
    }

    private void fireMotionEvent(int cameraId, String transition, List<Map<String, Object>> entities) {
        if (motionEventSink == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cameraId", cameraId);
        payload.put("transition", transition);
        payload.put("count", entities.size());
        payload.put("entities", entities);
        motionEventSink.accept("deceasedcc_motion", payload);
    }

    /** True if this frustum-scan entry passes the camera's filter. Filter
     *  components compose:
     *    categories (null=any)  ∧  includeTypes (null=any)  ∧
     *    NOT excludeTypes  ∧  distance ∈ [min, max]
     *  An empty whitelist (non-null but no members) is meaningful: it
     *  matches nothing. Lua can use {@code categories = {}} as a kill-switch. */
    private static boolean matchesFilter(Map<String, Object> entry, CameraBlockEntity cam) {
        Object distObj = entry.get("distance");
        if (distObj instanceof Number n) {
            double d = n.doubleValue();
            if (d < cam.getFilterMinDistance() || d > cam.getFilterMaxDistance()) return false;
        }

        String type = entry.get("type") instanceof String s ? s : null;
        Set<String> excl = cam.getFilterExcludeTypes();
        if (type != null && !excl.isEmpty() && excl.contains(type)) return false;

        Set<String> incl = cam.getFilterIncludeTypes();
        if (incl != null && (type == null || !incl.contains(type))) return false;

        Set<String> cats = cam.getFilterCategories();
        if (cats != null) {
            if (cats.isEmpty()) return false; // explicit "match nothing"
            String category = entry.get("category") instanceof String c ? c : null;
            Boolean isPlayer = entry.get("isPlayer") instanceof Boolean b ? b : Boolean.FALSE;
            boolean any = false;
            for (String wanted : cats) {
                if (categoryMatches(category, isPlayer, wanted)) { any = true; break; }
            }
            if (!any) return false;
        }

        return true;
    }

    /** Map Lua-friendly category names to MC's MobCategory names.
     *  "player" is a pseudo-category that matches Player entities
     *  regardless of their MobCategory (which is MISC in vanilla).
     *  Unknown names fall back to a literal case-insensitive match
     *  against the entity's mcCategory, so forward-compat with future
     *  MC categories doesn't require a code change. */
    private static boolean categoryMatches(@Nullable String mcCategory, Boolean isPlayer, String wanted) {
        String w = wanted == null ? "" : wanted.toLowerCase();
        if (w.equals("player")) return Boolean.TRUE.equals(isPlayer);
        if (mcCategory == null) return false;
        String m = mcCategory.toLowerCase();
        switch (w) {
            case "hostile": return m.equals("monster");
            case "passive": return m.equals("creature");
            case "ambient": return m.equals("ambient");
            case "water":   return m.contains("water") || m.contains("axolotl");
            case "misc":    return m.equals("misc");
            default:        return m.equals(w);
        }
    }

    /** Most recent frame for a camera, or null if the buffer is empty. */
    @Nullable
    public Map<String, Object> getLatestFrame(int cameraId) {
        ArrayDeque<Map<String, Object>> buf = cameraBuffers.get(cameraId);
        return buf == null || buf.isEmpty() ? null : buf.peekLast();
    }

    /**
     * Fetch a buffered frame by how-long-ago-in-server-ticks it was
     * captured. 0 = most recent, interval = one frame back, etc. Returns
     * null when the requested depth exceeds buffer size.
     */
    @Nullable
    public Map<String, Object> getFrameAt(int cameraId, int ticksAgo) {
        ArrayDeque<Map<String, Object>> buf = cameraBuffers.get(cameraId);
        if (buf == null || buf.isEmpty()) return null;
        int interval = Math.max(1, ModConfig.CAMERA_CAPTURE_INTERVAL_TICKS.get());
        int stepsAgo = Math.max(0, ticksAgo / interval);
        int idxFromEnd = stepsAgo;   // 0 = last
        int size = buf.size();
        if (idxFromEnd >= size) return null;
        // Iterate from tail.
        int target = size - 1 - idxFromEnd;
        int i = 0;
        for (Map<String, Object> f : buf) {
            if (i == target) return f;
            i++;
        }
        return null;
    }

    public int getBufferSize(int cameraId) {
        ArrayDeque<Map<String, Object>> buf = cameraBuffers.get(cameraId);
        return buf == null ? 0 : buf.size();
    }

    public int getBufferDurationTicks(int cameraId) {
        int size = getBufferSize(cameraId);
        return size * Math.max(1, ModConfig.CAMERA_CAPTURE_INTERVAL_TICKS.get());
    }

    public void clearBuffer(int cameraId) {
        ArrayDeque<Map<String, Object>> buf = cameraBuffers.get(cameraId);
        if (buf != null) buf.clear();
    }

    // -----------------------------------------------------------------

    /**
     * Detect what kind of device is at the given BE. Returns {@code null}
     * if the block entity isn't one we recognise as linkable. Extensible
     * entry point — add new types here and in {@link DeviceType}.
     */
    @Nullable
    public static DeviceType detectType(BlockEntity be) {
        if (be == null) return null;
        if (be instanceof HologramProjectorBlockEntity) return DeviceType.HOLOGRAM_PROJECTOR;
        if (be instanceof CameraBlockEntity)            return DeviceType.CAMERA;
        if (be instanceof ChunkRadarBlockEntity)        return DeviceType.CHUNK_RADAR;
        if (be instanceof EntityTrackerBlockEntity)     return DeviceType.ENTITY_TRACKER;
        // Only the advanced turret (TurretMountBlockEntity) exposes a CC
        // surface — the basic turret has no peripheral and cannot be linked.
        // Matched by class name to avoid importing turret-package classes.
        String cls = be.getClass().getSimpleName();
        if (cls.equals("TurretMountBlockEntity")) {
            return DeviceType.TURRET;
        }
        return null;
    }

    // ---- NBT --------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ListTag list = new ListTag();
        for (LinkedDevice d : linkedDevices) {
            CompoundTag entry = new CompoundTag();
            entry.putLong(TAG_POS, d.pos().asLong());
            entry.putString(TAG_TYPE, d.type().name());
            list.add(entry);
        }
        tag.put(TAG_DEVICES, list);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        linkedDevices.clear();
        if (tag.contains(TAG_DEVICES, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_DEVICES, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                BlockPos pos = BlockPos.of(entry.getLong(TAG_POS));
                DeviceType type;
                try { type = DeviceType.valueOf(entry.getString(TAG_TYPE)); }
                catch (IllegalArgumentException ignored) { continue; }
                linkedDevices.add(new LinkedDevice(pos, type));
                DEVICE_TO_CONTROLLER.put(pos, worldPosition.immutable()); // Phase 7b — rebuild reverse index
            }
        }
    }

    /** Purge this controller's entries from the static reverse index when
     *  the block is broken or the chunk unloads. Without this, the index
     *  would hold stale device→controller pointers that CHAIN mode would
     *  resolve to a non-existent controller. */
    @Override
    public void setRemoved() {
        super.setRemoved();
        BlockPos me = worldPosition.immutable();
        DEVICE_TO_CONTROLLER.values().removeIf(controllerPos -> controllerPos.equals(me));
    }
}
