package net.deceasedcraft.deceasedcc.blocks.entity;

import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.deceasedcraft.deceasedcc.peripherals.CameraPeripheral;
import net.deceasedcraft.deceasedcc.peripherals.PeripheralBlockEntity;
import net.deceasedcraft.deceasedcc.util.HologramLinkRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CameraBlockEntity extends PeripheralBlockEntity {
    private static final String TAG_YAW   = "yawDeg";
    private static final String TAG_PITCH = "pitchDeg";
    // Phase 6d.2 — lock target
    private static final String TAG_LOCKED_X = "lockedX";
    private static final String TAG_LOCKED_Y = "lockedY";
    private static final String TAG_LOCKED_Z = "lockedZ";
    private static final String TAG_LOCKED_UUID = "lockedUuid";
    // Phase 6d.3 — motion trigger. Null-or-absent list tags encode
    // "no whitelist, allow everything" so zero-config enables broad
    // detection without forcing Lua callers to enumerate every mob.
    private static final String TAG_MOTION_ENABLED   = "motionEnabled";
    private static final String TAG_FILTER_CATS      = "filterCategories";
    private static final String TAG_FILTER_INCLUDE   = "filterIncludeTypes";
    private static final String TAG_FILTER_EXCLUDE   = "filterExcludeTypes";
    private static final String TAG_FILTER_MIN_DIST  = "filterMinDistance";
    private static final String TAG_FILTER_MAX_DIST  = "filterMaxDistance";
    private static final String TAG_TRIGGERS         = "triggers";
    // Phase 8 — paired hologram projector pos. Peer-to-peer pairing
    // (separate from controller linking). The counterpart projector BE
    // stores a matching pairedCameraPos. Either side's setRemoved clears
    // both sides so no dangling references survive a break.
    private static final String TAG_PAIRED_PROJ      = "pairedProjectorPos";

    private CameraPeripheral peripheralRef;
    // Direction the camera's lens is aimed. Persisted to NBT so placement
    // state (look-back-at-placer) survives world reload. Mutated by Phase 6d.1
    // setDirection / lookAt / lookAtBlock, and by 6d.2 lock tracking each tick.
    private float yawDeg   = 0.0f;
    private float pitchDeg = 0.0f;

    // --- client-side display smoothing (Phase 6c.1 swivel) ---
    //     Not NBT-persisted. Renderer lerps current → target each frame so
    //     setDirection calls animate instead of snapping. First render snaps
    //     current = target so the initial head doesn't spin in from 0.
    public float displayYaw   = 0.0f;
    public float displayPitch = 0.0f;
    public boolean displayInit = false;

    // --- Phase 6d.2 lock-on target ---
    // When lockedEntityUUID is non-null the controller re-resolves the
    // entity each tick and refreshes x/y/z. When it's null the lock is
    // a static world coordinate (lockOntoBlock). Either way, hasLock
    // gates whether the controller does any per-tick re-aim at all.
    private boolean hasLock = false;
    private double lockedTargetX = 0.0;
    private double lockedTargetY = 0.0;
    private double lockedTargetZ = 0.0;
    @org.jetbrains.annotations.Nullable private UUID lockedEntityUUID = null;

    // --- Phase 6d.3 motion trigger — Lua-driven, no hardcoded policy ---
    // All four filter components are composed together:
    //   categories  AND  (includeTypes ∨ includeTypes==null)  AND
    //   NOT excludeTypes AND distance∈[min,max]
    // null on a whitelist means "allow anything in this dimension"; an
    // empty whitelist means "allow nothing" — that's intentional so Lua
    // can enable motionTrigger with {} and get a no-op until configured.
    private boolean motionTriggerEnabled = false;
    @org.jetbrains.annotations.Nullable private Set<String> filterCategories   = null;
    @org.jetbrains.annotations.Nullable private Set<String> filterIncludeTypes = null;
    private Set<String> filterExcludeTypes = new HashSet<>();
    private double filterMinDistance = 0.0;
    private double filterMaxDistance = Double.MAX_VALUE;
    // Which transitions fire the Lua event. Default {"enter"} so a player
    // flipping motionTriggerEnabled=true without touching filter gets the
    // canonical motion-sensor behaviour.
    private Set<String> triggers = new LinkedHashSet<>(Collections.singletonList("enter"));

    // --- Phase 8 — paired projector (bidirectional camera↔projector) ---
    @org.jetbrains.annotations.Nullable
    private BlockPos pairedProjectorPos = null;

    public CameraBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CAMERA.get(), pos, state);
    }

    @Override
    protected IPeripheral createPeripheral() {
        peripheralRef = new CameraPeripheral(this);
        peripheralRef.seedDirection(yawDeg, pitchDeg);
        return peripheralRef;
    }

    public CameraPeripheral cameraPeripheral() {
        peripheral();
        return peripheralRef;
    }

    /** Set the lens aim. Used at placement (via {@code CameraBlock#setPlacedBy})
     *  and from Lua via {@code advanced_network_controller.cameraSetDirection}
     *  / {@code cameraLookAt} / {@code cameraLookAtBlock}.
     *
     *  <p>Pushes a block update so tracking clients receive the new
     *  yaw/pitch via {@link #getUpdatePacket}. Without this the CameraBER
     *  would keep rendering the head at the old direction because the
     *  client BE never learns about the mutation. */
    public void setDirection(float yaw, float pitch) {
        this.yawDeg = yaw;
        this.pitchDeg = pitch;
        if (peripheralRef != null) peripheralRef.seedDirection(yaw, pitch);
        setChanged();
        if (level != null && !level.isClientSide) {
            // Flags bit 1 = send to client. The state doesn't actually
            // change — we just need the client chunk-diff to include
            // this BE's updated NBT.
            BlockState st = getBlockState();
            level.sendBlockUpdated(worldPosition, st, st, 2);
        }
    }

    /** Back-compat alias — {@link net.deceasedcraft.deceasedcc.blocks.CameraBlock#setPlacedBy}
     *  still calls the old name. */
    public void setInitialAim(float yaw, float pitch) {
        setDirection(yaw, pitch);
    }

    public float getYawDeg()   { return yawDeg; }
    public float getPitchDeg() { return pitchDeg; }

    // --- 6d.2 lock API ---
    public boolean hasLockTarget() { return hasLock; }
    public double  getLockX()      { return lockedTargetX; }
    public double  getLockY()      { return lockedTargetY; }
    public double  getLockZ()      { return lockedTargetZ; }
    @org.jetbrains.annotations.Nullable
    public UUID    getLockedEntityUUID() { return lockedEntityUUID; }
    public boolean isLockingEntity()     { return lockedEntityUUID != null; }

    /** Static world-coord lock. Clears any prior entity-UUID lock so a
     *  `lockOntoBlock` call after `lockOnto(entity)` doesn't keep chasing
     *  the mob. Controller drives the re-aim each tick. */
    public void setLockTarget(double x, double y, double z) {
        this.hasLock = true;
        this.lockedEntityUUID = null;
        this.lockedTargetX = x;
        this.lockedTargetY = y;
        this.lockedTargetZ = z;
        setChanged();
    }

    /** Entity-tracking lock. Controller resolves UUID→Entity each tick,
     *  updates x/y/z from the entity's live position, and re-aims. If
     *  the entity despawns or unloads, the lock stays at last-known
     *  coord (no automatic clear — Lua decides). */
    public void setLockTargetEntity(UUID uuid, double currentX, double currentY, double currentZ) {
        this.hasLock = true;
        this.lockedEntityUUID = uuid;
        this.lockedTargetX = currentX;
        this.lockedTargetY = currentY;
        this.lockedTargetZ = currentZ;
        setChanged();
    }

    /** Update the cached target coord without clearing the UUID. Used by
     *  the controller's per-tick re-resolve when tracking an entity. */
    public void updateLockTargetCoord(double x, double y, double z) {
        if (!hasLock) return;
        this.lockedTargetX = x;
        this.lockedTargetY = y;
        this.lockedTargetZ = z;
        // Deliberately no setChanged() — this runs every tick while
        // locked onto a moving entity; marking the BE dirty every tick
        // would pointlessly force NBT rewrites. The coord is only needed
        // at runtime; on reload we'll re-derive from the UUID anyway.
    }

    public void clearLockTarget() {
        this.hasLock = false;
        this.lockedEntityUUID = null;
        setChanged();
    }

    // --- 6d.3 motion trigger API ---
    public boolean isMotionTriggerEnabled() { return motionTriggerEnabled; }

    @org.jetbrains.annotations.Nullable
    public Set<String> getFilterCategories()   { return filterCategories; }
    @org.jetbrains.annotations.Nullable
    public Set<String> getFilterIncludeTypes() { return filterIncludeTypes; }
    public Set<String> getFilterExcludeTypes() { return filterExcludeTypes; }
    public double      getFilterMinDistance()  { return filterMinDistance; }
    public double      getFilterMaxDistance()  { return filterMaxDistance; }
    public Set<String> getTriggers()           { return triggers; }

    public void setMotionTriggerEnabled(boolean on) {
        this.motionTriggerEnabled = on;
        setChanged();
    }

    public void setFilterCategories(@org.jetbrains.annotations.Nullable Set<String> cats) {
        this.filterCategories = cats == null ? null : new LinkedHashSet<>(cats);
        setChanged();
    }

    public void setFilterIncludeTypes(@org.jetbrains.annotations.Nullable Set<String> types) {
        this.filterIncludeTypes = types == null ? null : new LinkedHashSet<>(types);
        setChanged();
    }

    public void setFilterExcludeTypes(Set<String> types) {
        this.filterExcludeTypes = types == null ? new HashSet<>() : new HashSet<>(types);
        setChanged();
    }

    public void setFilterDistance(double min, double max) {
        this.filterMinDistance = Math.max(0.0, min);
        this.filterMaxDistance = Math.max(this.filterMinDistance, max);
        setChanged();
    }

    public void setTriggers(Set<String> trg) {
        // Defensive copy + normalise to lowercase; only keep known values.
        Set<String> out = new LinkedHashSet<>();
        if (trg != null) {
            for (String s : trg) {
                if (s == null) continue;
                String v = s.toLowerCase();
                if (v.equals("enter") || v.equals("leave") || v.equals("present")) out.add(v);
            }
        }
        if (out.isEmpty()) out.add("enter"); // fall back to canonical default
        this.triggers = out;
        setChanged();
    }

    // --- Phase 8 — pair API ---

    @org.jetbrains.annotations.Nullable
    public BlockPos getPairedProjector() { return pairedProjectorPos; }

    /** Record this camera's paired projector. Updates the transient
     *  {@link HologramLinkRegistry} so either side can resolve its
     *  counterpart without iterating loaded BEs. Callers should also
     *  write the reciprocal pairing on the projector side — the
     *  {@code LinkingToolItem} does both in one transaction. */
    public void setPairedProjector(@org.jetbrains.annotations.Nullable BlockPos proj) {
        if (pairedProjectorPos != null && !pairedProjectorPos.equals(proj)) {
            HologramLinkRegistry.unpairByCamera(worldPosition);
        }
        this.pairedProjectorPos = proj == null ? null : proj.immutable();
        if (pairedProjectorPos != null) {
            HologramLinkRegistry.pair(worldPosition, pairedProjectorPos);
        }
        setChanged();
    }

    public void clearPairedProjector() {
        if (pairedProjectorPos == null) return;
        HologramLinkRegistry.unpairByCamera(worldPosition);
        this.pairedProjectorPos = null;
        setChanged();
    }

    /** Package-private helper called from
     *  {@link HologramProjectorBlockEntity#setRemoved} when the projector
     *  has already cleaned up the registry entry. Just drops the pointer
     *  and marks dirty — no cross-unpair recursion. */
    void clearPairedProjectorSilently() {
        this.pairedProjectorPos = null;
        setChanged();
    }

    /** Reset filter to defaults: no whitelists, no excludes, 0..∞ distance,
     *  triggers = ["enter"]. Leaves motionTriggerEnabled untouched. */
    public void resetFilter() {
        this.filterCategories   = null;
        this.filterIncludeTypes = null;
        this.filterExcludeTypes = new HashSet<>();
        this.filterMinDistance  = 0.0;
        this.filterMaxDistance  = Double.MAX_VALUE;
        this.triggers           = new LinkedHashSet<>(Collections.singletonList("enter"));
        setChanged();
    }

    /** Lua-ready snapshot of the current filter so scripts can round-trip
     *  getMotionFilter → setMotionFilter for debugging or persistence. */
    public Map<String, Object> getFilterMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories",   filterCategories   == null ? null : new ArrayList<>(filterCategories));
        out.put("includeTypes", filterIncludeTypes == null ? null : new ArrayList<>(filterIncludeTypes));
        out.put("excludeTypes", new ArrayList<>(filterExcludeTypes));
        out.put("minDistance",  filterMinDistance);
        out.put("maxDistance",  filterMaxDistance == Double.MAX_VALUE ? -1.0 : filterMaxDistance);
        out.put("triggers",     new ArrayList<>(triggers));
        return out;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putFloat(TAG_YAW, yawDeg);
        tag.putFloat(TAG_PITCH, pitchDeg);
        if (hasLock) {
            tag.putDouble(TAG_LOCKED_X, lockedTargetX);
            tag.putDouble(TAG_LOCKED_Y, lockedTargetY);
            tag.putDouble(TAG_LOCKED_Z, lockedTargetZ);
            if (lockedEntityUUID != null) tag.putUUID(TAG_LOCKED_UUID, lockedEntityUUID);
        }
        tag.putBoolean(TAG_MOTION_ENABLED, motionTriggerEnabled);
        // Absent list ⇒ "no whitelist (allow all)"; empty list ⇒ "allow
        // nothing". Preserve that distinction by only writing the tag when
        // the set is non-null.
        if (filterCategories   != null) tag.put(TAG_FILTER_CATS,    stringList(filterCategories));
        if (filterIncludeTypes != null) tag.put(TAG_FILTER_INCLUDE, stringList(filterIncludeTypes));
        tag.put(TAG_FILTER_EXCLUDE, stringList(filterExcludeTypes));
        tag.putDouble(TAG_FILTER_MIN_DIST, filterMinDistance);
        tag.putDouble(TAG_FILTER_MAX_DIST, filterMaxDistance);
        tag.put(TAG_TRIGGERS, stringList(triggers));
        if (pairedProjectorPos != null) {
            tag.putLong(TAG_PAIRED_PROJ, pairedProjectorPos.asLong());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(TAG_YAW))   this.yawDeg   = tag.getFloat(TAG_YAW);
        if (tag.contains(TAG_PITCH)) this.pitchDeg = tag.getFloat(TAG_PITCH);
        // Phase 6d.2 — lock target
        if (tag.contains(TAG_LOCKED_X) && tag.contains(TAG_LOCKED_Y) && tag.contains(TAG_LOCKED_Z)) {
            this.lockedTargetX = tag.getDouble(TAG_LOCKED_X);
            this.lockedTargetY = tag.getDouble(TAG_LOCKED_Y);
            this.lockedTargetZ = tag.getDouble(TAG_LOCKED_Z);
            this.lockedEntityUUID = tag.hasUUID(TAG_LOCKED_UUID) ? tag.getUUID(TAG_LOCKED_UUID) : null;
            this.hasLock = true;
        } else {
            this.hasLock = false;
            this.lockedEntityUUID = null;
        }
        // Phase 6d.3 — motion trigger
        this.motionTriggerEnabled = tag.getBoolean(TAG_MOTION_ENABLED);
        this.filterCategories   = tag.contains(TAG_FILTER_CATS,    Tag.TAG_LIST)
                ? readStringSet(tag.getList(TAG_FILTER_CATS,    Tag.TAG_STRING))
                : null;
        this.filterIncludeTypes = tag.contains(TAG_FILTER_INCLUDE, Tag.TAG_LIST)
                ? readStringSet(tag.getList(TAG_FILTER_INCLUDE, Tag.TAG_STRING))
                : null;
        this.filterExcludeTypes = tag.contains(TAG_FILTER_EXCLUDE, Tag.TAG_LIST)
                ? readStringSet(tag.getList(TAG_FILTER_EXCLUDE, Tag.TAG_STRING))
                : new HashSet<>();
        if (tag.contains(TAG_FILTER_MIN_DIST)) this.filterMinDistance = tag.getDouble(TAG_FILTER_MIN_DIST);
        if (tag.contains(TAG_FILTER_MAX_DIST)) {
            double max = tag.getDouble(TAG_FILTER_MAX_DIST);
            this.filterMaxDistance = max <= 0 ? Double.MAX_VALUE : max;
        }
        if (tag.contains(TAG_TRIGGERS, Tag.TAG_LIST)) {
            Set<String> loaded = readStringSet(tag.getList(TAG_TRIGGERS, Tag.TAG_STRING));
            if (!loaded.isEmpty()) this.triggers = loaded;
        }
        if (tag.contains(TAG_PAIRED_PROJ)) {
            this.pairedProjectorPos = BlockPos.of(tag.getLong(TAG_PAIRED_PROJ));
            HologramLinkRegistry.pair(worldPosition, pairedProjectorPos);
        } else {
            this.pairedProjectorPos = null;
        }

        if (peripheralRef != null) peripheralRef.seedDirection(yawDeg, pitchDeg);
        // NOTE: do NOT reset displayInit here. load() fires every time the
        // client receives a server update packet; resetting would cause an
        // instant snap every update, killing the BER's per-frame lerp.
        // First-time placement snap is handled by displayInit's default
        // (false) on freshly-constructed client BEs.
    }

    // ---- NBT helpers for string-set serialization --------------------
    private static ListTag stringList(Set<String> src) {
        ListTag list = new ListTag();
        for (String s : src) if (s != null) list.add(StringTag.valueOf(s));
        return list;
    }

    private static Set<String> readStringSet(ListTag list) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) out.add(list.getString(i));
        return out;
    }

    // -------------------------------------------------------------------
    // Client-sync plumbing — forwards NBT (yaw/pitch + whatever else
    // saveAdditional writes) to tracking clients on chunk load and via
    // setInitialAim's level.sendBlockUpdated. Without these overrides
    // the client BE stays at yawDeg=0 / pitchDeg=0 no matter what the
    // server computed at placement.
    // -------------------------------------------------------------------

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) load(tag);
    }

    /** Phase 8 — break the bidirectional camera↔projector pair when the
     *  camera block is removed. Clears the registry and the counterpart
     *  projector's field so the contract "either side breaking cleanly
     *  unpairs the other" holds. Skips on client side — pair state is
     *  server-authoritative. */
    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide && pairedProjectorPos != null) {
            BlockPos proj = pairedProjectorPos;
            HologramLinkRegistry.unpairByCamera(worldPosition);
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(proj);
            if (be instanceof HologramProjectorBlockEntity hp) {
                hp.clearPairedCameraSilently();
            }
        }
        super.setRemoved();
    }
}
