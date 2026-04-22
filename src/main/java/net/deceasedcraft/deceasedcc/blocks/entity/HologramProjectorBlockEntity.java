package net.deceasedcraft.deceasedcc.blocks.entity;

import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.deceasedcraft.deceasedcc.network.DeceasedNetwork;
import net.deceasedcraft.deceasedcc.network.HologramClearPacket;
import net.deceasedcraft.deceasedcc.network.HologramDataPacket;
import net.deceasedcraft.deceasedcc.network.HologramMarkersPacket;
import net.deceasedcraft.deceasedcc.network.HologramTransformPacket;
import net.deceasedcraft.deceasedcc.network.HologramVisibilityPacket;
import net.deceasedcraft.deceasedcc.network.HologramVoxelPacket;
import net.deceasedcraft.deceasedcc.peripherals.HologramProjectorPeripheral;
import net.deceasedcraft.deceasedcc.peripherals.PeripheralBlockEntity;
import net.deceasedcraft.deceasedcc.util.HologramLinkRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Phase 2 — stores the current hologram content + transform, broadcasts
 * changes to clients tracking this chunk.
 *
 * <p>Content payloads (image / voxel / marker) are NOT persisted across
 * world reload per hologram.txt §Section 2. Transform and color ARE
 * persisted — so a projector that was rotated/tinted before unload keeps
 * those settings, just goes dark until a Lua script re-sends image data.
 */
public class HologramProjectorBlockEntity extends PeripheralBlockEntity {

    public enum HologramMode {
        IDLE,
        MODE_2D,
        MODE_3D_CULLED,
        MODE_3D_FULL,
        MARKERS,
        COMPOSITE
    }

    private HologramProjectorPeripheral peripheralRef;

    // --- content (transient, not NBT-persisted) ---
    private HologramMode mode = HologramMode.IDLE;
    private int imageWidth  = 0;
    private int imageHeight = 0;
    private byte @Nullable [] compressedImage;   // deflated ARGB bytes
    private boolean visible = false;

    // --- Phase 3 voxel content (transient) ---
    private int voxSizeX = 0, voxSizeY = 0, voxSizeZ = 0;
    private int @Nullable [] voxPalette;              // ARGB per palette slot
    private byte @Nullable [] voxCompressedIndexes;   // deflated index bytes (0 = empty)

    // --- Phase 5 markers content (transient) ---
    //     Marker coords live in the same voxel-local space as the voxel
    //     grid (if any) — in MARKERS mode the grid size fields below
    //     define that space; in COMPOSITE mode markers inherit the voxel
    //     grid's sizes.
    private int markersSizeX = 0, markersSizeY = 0, markersSizeZ = 0;
    private int markersCount = 0;
    private float @Nullable [] markerXs, markerYs, markerZs;
    private byte @Nullable [] markerShapes;     // MarkerShape ordinal
    private int @Nullable [] markerColors;      // packed ARGB per marker
    private float @Nullable [] markerScaleXs, markerScaleYs, markerScaleZs;
    private float @Nullable [] markerYaws, markerPitches;

    // --- transform (NBT-persisted) ---
    private double scaleX = 1.0, scaleY = 1.0, scaleZ = 1.0;
    private double offsetX = 0.0, offsetY = 0.5, offsetZ = 0.0;
    private double yawDeg = 0.0, pitchDeg = 0.0, rollDeg = 0.0;
    // True once the Lua script has explicitly called setRotation. Stays
    // false until then; cleared every time new content is uploaded so
    // fresh uploads default to a world-grid-aligned pose. User policy —
    // "hologram should align with the world grid unless rotation is
    // explicitly defined in the script".
    private boolean rotationExplicit = false;
    private int colorARGB = 0xFFFFFFFF;

    // Phase 7d — global alpha multiplier for renderer output.
    //   -1.0  ⇒ "use the legacy MAX_HOLO_ALPHA / MAX_HOLO_VOXEL_ALPHA caps".
    //    0..1 ⇒ scale per-pixel/per-voxel alpha by this multiplier, NO cap.
    //   With a multiplier of 1.0, palette entries with alpha <255 ("#80FFFFFF"
    //   etc.) render as their declared translucency — that's the per-voxel
    //   "glass-like" mechanism the Lua side asked for.
    private float alphaMultiplier = -1.0f;

    // Phase 8 — paired camera pos. Peer-to-peer pairing; the counterpart
    // camera BE stores a matching pairedProjectorPos. NBT-persisted so
    // pairing survives world reload without re-linking.
    @Nullable
    private BlockPos pairedCameraPos = null;

    // Phase 8.2 — live-camera mode. When true, clients render the paired
    // camera's POV locally to a FBO and bind that as the hologram quad's
    // texture. Set by loadFromCamera2D; cleared by setImage / clearContent /
    // clearLiveCamera. Transient — reload resets to false so a world
    // restart doesn't keep rendering a feed nobody asked for.
    private boolean liveCameraMode = false;
    /** Heartbeat timestamp updated on every loadFromCamera2D call. If Lua
     *  stops calling (script terminated, world unloaded, whatever), the
     *  server ticker auto-clears liveCameraMode after
     *  {@link #LIVE_CAMERA_HEARTBEAT_TIMEOUT_MS} ms so the client stops
     *  capturing + the hologram goes dark. Server-side only. */
    private long lastLiveCameraHeartbeatMs = 0L;
    public static final long LIVE_CAMERA_HEARTBEAT_TIMEOUT_MS = 3000L;

    // Phase 8.3 — sticky per-projector feed opts. Set by
    // loadFromCamera2D(opts) + hologramLoadFromCamera2D(opts), broadcast
    // via HologramTransformPacket so clients size their FBO accordingly.
    // Transient — default to 256×144 / 60° on fresh BE creation.
    private int feedWidth = 256;
    private int feedHeight = 144;
    private float feedFov = 60.0f;

    // Phase 10 — content-update rate limit. Server-side timestamp tracking
    // silently coalesces spam (setImage/setVoxelGrid/setComposite/setMarkers
    // loops). See ModConfig.HOLOGRAM_MAX_UPDATES_PER_SECOND.
    private long lastContentUpdateMs = 0L;

    /** Returns true if the caller should proceed with a content update
     *  (updates the timestamp), false if it should silently drop.
     *  Cooldown = 1000 / MAX_UPDATES_PER_SECOND ms. */
    private boolean acceptContentUpdate() {
        long now = System.currentTimeMillis();
        int maxPerSec = net.deceasedcraft.deceasedcc.core.ModConfig.HOLOGRAM_MAX_UPDATES_PER_SECOND.get();
        long minIntervalMs = 1000L / Math.max(1, maxPerSec);
        if (now - lastContentUpdateMs < minIntervalMs) return false;
        lastContentUpdateMs = now;
        return true;
    }

    public HologramProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HOLOGRAM_PROJECTOR.get(), pos, state);
    }

    /** Phase 7e — when the projector is broken (or its chunk unloads),
     *  push a HologramClearPacket so clients in render range drop the
     *  cached entry. Without this the HologramClientCache keeps the
     *  last-seen voxel grid indefinitely and the renderer draws a
     *  "ghost" hologram even though the block is gone. Harmless on
     *  chunk unload — NEAR distributor skips players out of range,
     *  and the ChunkEvent.Watch resync re-sends state on reload.
     *
     *  <p>Phase 8 — also break the bidirectional camera↔projector pair.
     *  Clears our entry from the registry and nulls the paired camera's
     *  pairedProjectorPos field so no dangling references survive. */
    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel sl) {
            try {
                PacketDistributor.TargetPoint tp = new PacketDistributor.TargetPoint(
                        worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                        192.0, sl.dimension());
                DeceasedNetwork.CHANNEL.send(PacketDistributor.NEAR.with(() -> tp),
                        new HologramClearPacket(worldPosition));
            } catch (Exception ignored) {}
            if (pairedCameraPos != null) {
                BlockPos cam = pairedCameraPos;
                HologramLinkRegistry.unpairByProjector(worldPosition);
                var be = sl.getBlockEntity(cam);
                if (be instanceof CameraBlockEntity cameraBE) {
                    cameraBE.clearPairedProjectorSilently();
                }
            }
        }
        super.setRemoved();
    }

    @Override
    protected IPeripheral createPeripheral() {
        peripheralRef = new HologramProjectorPeripheral(this);
        return peripheralRef;
    }

    public HologramProjectorPeripheral hologramPeripheral() {
        peripheral();
        return peripheralRef;
    }

    // ---- getters (used by peripheral getState + client cache fallback) -----

    public HologramMode hologramMode() { return mode; }
    public boolean isVisible() { return visible; }
    public int imageWidth()  { return imageWidth; }
    public int imageHeight() { return imageHeight; }
    public byte @Nullable [] compressedImage() { return compressedImage; }

    public int voxelSizeX() { return voxSizeX; }
    public int voxelSizeY() { return voxSizeY; }
    public int voxelSizeZ() { return voxSizeZ; }
    public int @Nullable [] voxelPalette() { return voxPalette; }
    public byte @Nullable [] voxelCompressedIndexes() { return voxCompressedIndexes; }

    public int markersCount()  { return markersCount; }
    public int markersSizeX()  { return markersSizeX; }
    public int markersSizeY()  { return markersSizeY; }
    public int markersSizeZ()  { return markersSizeZ; }

    public double getScaleX() { return scaleX; }
    public double getScaleY() { return scaleY; }
    public double getScaleZ() { return scaleZ; }
    public double getOffsetX() { return offsetX; }
    public double getOffsetY() { return offsetY; }
    public double getOffsetZ() { return offsetZ; }
    public double getYawDeg()   { return yawDeg; }
    public double getPitchDeg() { return pitchDeg; }
    public double getRollDeg()  { return rollDeg; }
    public int getColorARGB()   { return colorARGB; }
    public float getAlphaMultiplier() { return alphaMultiplier; }

    public boolean isLiveCameraMode() { return liveCameraMode; }
    public int getFeedWidth()  { return feedWidth; }
    public int getFeedHeight() { return feedHeight; }
    public float getFeedFov()  { return feedFov; }

    /** Update the sticky feed opts on the projector. Broadcasts via
     *  transform packet so clients resize their FBO to match. Called by
     *  loadFromCamera2D when Lua passes an opts table. */
    public void setFeedOpts(int width, int height, float fov) {
        if (this.feedWidth == width && this.feedHeight == height
                && this.feedFov == fov) return;
        this.feedWidth = width;
        this.feedHeight = height;
        this.feedFov = fov;
        setChanged();
        broadcastTransform();
    }

    /** Enable/disable live-camera rendering for this projector. Changes are
     *  broadcast via the transform packet so clients update their render
     *  path accordingly. */
    public void setLiveCameraMode(boolean on) {
        if (this.liveCameraMode == on) return;
        this.liveCameraMode = on;
        setChanged();
        broadcastTransform();
    }

    /** Refresh the live-camera heartbeat. Lua calls this (via
     *  loadFromCamera2D) to prove the script is still alive. The server
     *  ticker auto-clears liveCameraMode if no heartbeat for
     *  {@link #LIVE_CAMERA_HEARTBEAT_TIMEOUT_MS} ms. */
    public void heartbeatLiveCamera() {
        this.lastLiveCameraHeartbeatMs = System.currentTimeMillis();
    }

    /** Ticker called once per server tick (wired via
     *  {@code HologramProjectorBlock.getTicker}). Clears liveCameraMode
     *  when the heartbeat expires so a terminated Lua script doesn't
     *  leave the hologram stuck in live-render mode. */
    public static void serverTick(net.minecraft.world.level.Level level,
                                   BlockPos pos,
                                   net.minecraft.world.level.block.state.BlockState state,
                                   HologramProjectorBlockEntity be) {
        if (!be.liveCameraMode) return;
        long now = System.currentTimeMillis();
        if (now - be.lastLiveCameraHeartbeatMs > LIVE_CAMERA_HEARTBEAT_TIMEOUT_MS) {
            be.setLiveCameraMode(false);
        }
    }

    // --- Phase 8 — pair API ---

    @Nullable
    public BlockPos getPairedCamera() { return pairedCameraPos; }

    /** Record this projector's paired camera + register in the transient
     *  link registry. Callers are responsible for writing the reciprocal
     *  pairing on the camera side — the {@code LinkingToolItem} does both
     *  atomically. Broadcasts via transform packet so clients know the
     *  pair pos (needed for live-camera rendering's camera BE lookup). */
    public void setPairedCamera(@Nullable BlockPos cam) {
        if (pairedCameraPos != null && !pairedCameraPos.equals(cam)) {
            HologramLinkRegistry.unpairByProjector(worldPosition);
        }
        this.pairedCameraPos = cam == null ? null : cam.immutable();
        if (pairedCameraPos != null) {
            HologramLinkRegistry.pair(pairedCameraPos, worldPosition);
        }
        setChanged();
        broadcastTransform();
    }

    public void clearPairedCamera() {
        if (pairedCameraPos == null) return;
        HologramLinkRegistry.unpairByProjector(worldPosition);
        this.pairedCameraPos = null;
        setChanged();
        broadcastTransform();
    }

    /** Package-private helper: clear our paired-camera field without
     *  re-crossing the unpair boundary. Called from
     *  {@link CameraBlockEntity#setRemoved} — the camera has already
     *  cleaned up the registry entry, so we just need to drop our
     *  pointer + mark dirty. */
    void clearPairedCameraSilently() {
        this.pairedCameraPos = null;
        setChanged();
    }

    // ---- mutators called by the peripheral ---------------------------------

    private void defaultWorldAlignedIfNoExplicit() {
        if (!rotationExplicit) resetRotationForNewContent();
    }

    public void setImage(int w, int h, byte[] compressed) {
        if (!acceptContentUpdate()) return;
        defaultWorldAlignedIfNoExplicit();
        // A static image upload implicitly turns off live-camera mode — the
        // user wants to show THIS image, not a camera feed.
        if (this.liveCameraMode) {
            this.liveCameraMode = false;
            broadcastTransform();
        }
        this.imageWidth = w;
        this.imageHeight = h;
        this.compressedImage = compressed;
        // A projector can only show ONE content kind at a time — uploading a
        // 2D image evicts any previously-held voxel grid OR marker overlay.
        this.voxSizeX = this.voxSizeY = this.voxSizeZ = 0;
        this.voxPalette = null;
        this.voxCompressedIndexes = null;
        this.markersCount = 0;
        this.markerXs = null; this.markerYs = null; this.markerZs = null;
        this.markerShapes = null; this.markerColors = null;
        this.markerScaleXs = null; this.markerScaleYs = null; this.markerScaleZs = null;
        this.markerYaws = null; this.markerPitches = null;
        this.mode = HologramMode.MODE_2D;
        broadcastData();
    }

    public void setVoxelGrid(HologramMode voxelMode, int sx, int sy, int sz,
                              int[] palette, byte[] compressedIndexes) {
        if (!acceptContentUpdate()) return;
        defaultWorldAlignedIfNoExplicit();
        if (voxelMode != HologramMode.MODE_3D_CULLED && voxelMode != HologramMode.MODE_3D_FULL) {
            voxelMode = HologramMode.MODE_3D_CULLED;
        }
        // Evict the 2D payload and markers overlay — voxel-grid-only mode
        // (use setComposite to upload voxel + markers atomically).
        this.imageWidth = 0;
        this.imageHeight = 0;
        this.compressedImage = null;
        this.markersCount = 0;
        this.markerXs = null; this.markerYs = null; this.markerZs = null;
        this.markerShapes = null; this.markerColors = null;
        this.markerScaleXs = null; this.markerScaleYs = null; this.markerScaleZs = null;
        this.markerYaws = null; this.markerPitches = null;
        this.voxSizeX = sx;
        this.voxSizeY = sy;
        this.voxSizeZ = sz;
        this.voxPalette = palette;
        this.voxCompressedIndexes = compressedIndexes;
        this.mode = voxelMode;
        broadcastVoxel();
    }

    public void setMarkers(int sx, int sy, int sz, int count,
                            float[] xs, float[] ys, float[] zs,
                            byte[] shapes, int[] colors,
                            float[] scaleXs, float[] scaleYs, float[] scaleZs,
                            float[] yaws, float[] pitches) {
        if (!acceptContentUpdate()) return;
        defaultWorldAlignedIfNoExplicit();
        // Markers-only mode evicts both 2D image and voxel data.
        this.imageWidth = 0;
        this.imageHeight = 0;
        this.compressedImage = null;
        this.voxSizeX = this.voxSizeY = this.voxSizeZ = 0;
        this.voxPalette = null;
        this.voxCompressedIndexes = null;
        this.markersSizeX = sx;
        this.markersSizeY = sy;
        this.markersSizeZ = sz;
        this.markersCount = count;
        this.markerXs = xs; this.markerYs = ys; this.markerZs = zs;
        this.markerShapes = shapes;
        this.markerColors = colors;
        this.markerScaleXs = scaleXs; this.markerScaleYs = scaleYs; this.markerScaleZs = scaleZs;
        this.markerYaws = yaws; this.markerPitches = pitches;
        this.mode = HologramMode.MARKERS;
        broadcastMarkers();
    }

    /** Phase 7f live-entity overlay. Updates the marker layer without
     *  evicting the current voxel grid — used by scripts that stitched a
     *  blueprint and now want to fly live mob positions over it. Mode
     *  switches to COMPOSITE so the renderer draws both the voxel grid
     *  AND the markers. No-op if no voxel data is loaded (can't composite
     *  against nothing; caller should use {@link #setMarkers} instead). */
    public void setCompositeMarkers(int sx, int sy, int sz, int count,
                                     float[] xs, float[] ys, float[] zs,
                                     byte[] shapes, int[] colors,
                                     float[] scaleXs, float[] scaleYs, float[] scaleZs,
                                     float[] yaws, float[] pitches) {
        if (!acceptContentUpdate()) return;
        if (voxPalette == null || voxCompressedIndexes == null) return;
        this.markersSizeX = sx;
        this.markersSizeY = sy;
        this.markersSizeZ = sz;
        this.markersCount = count;
        this.markerXs = xs; this.markerYs = ys; this.markerZs = zs;
        this.markerShapes = shapes;
        this.markerColors = colors;
        this.markerScaleXs = scaleXs; this.markerScaleYs = scaleYs; this.markerScaleZs = scaleZs;
        this.markerYaws = yaws; this.markerPitches = pitches;
        this.mode = HologramMode.COMPOSITE;
        broadcastMarkers();
    }

    public void setComposite(int vSx, int vSy, int vSz, int[] vPalette, byte[] vCompressed,
                              int count, float[] xs, float[] ys, float[] zs,
                              byte[] shapes, int[] colors,
                              float[] scaleXs, float[] scaleYs, float[] scaleZs,
                              float[] yaws, float[] pitches) {
        if (!acceptContentUpdate()) return;
        defaultWorldAlignedIfNoExplicit();
        // Atomic voxel + markers upload. 2D image evicted, markers share
        // the voxel grid's coordinate space.
        this.imageWidth = 0;
        this.imageHeight = 0;
        this.compressedImage = null;
        this.voxSizeX = vSx; this.voxSizeY = vSy; this.voxSizeZ = vSz;
        this.voxPalette = vPalette;
        this.voxCompressedIndexes = vCompressed;
        this.markersSizeX = vSx;
        this.markersSizeY = vSy;
        this.markersSizeZ = vSz;
        this.markersCount = count;
        this.markerXs = xs; this.markerYs = ys; this.markerZs = zs;
        this.markerShapes = shapes;
        this.markerColors = colors;
        this.markerScaleXs = scaleXs; this.markerScaleYs = scaleYs; this.markerScaleZs = scaleZs;
        this.markerYaws = yaws; this.markerPitches = pitches;
        this.mode = HologramMode.COMPOSITE;
        broadcastVoxel();
        broadcastMarkers();
    }

    public void clearContent() {
        this.mode = HologramMode.IDLE;
        // Fresh canvas — drop the "user explicitly rotated it" sticky flag.
        this.rotationExplicit = false;
        this.imageWidth = 0;
        this.imageHeight = 0;
        this.compressedImage = null;
        if (this.liveCameraMode) {
            this.liveCameraMode = false;
            broadcastTransform();
        }
        this.voxSizeX = this.voxSizeY = this.voxSizeZ = 0;
        this.voxPalette = null;
        this.voxCompressedIndexes = null;
        this.markersSizeX = this.markersSizeY = this.markersSizeZ = 0;
        this.markersCount = 0;
        this.markerXs = null; this.markerYs = null; this.markerZs = null;
        this.markerShapes = null;
        this.markerColors = null;
        this.markerScaleXs = null; this.markerScaleYs = null; this.markerScaleZs = null;
        this.markerYaws = null; this.markerPitches = null;
        this.visible = false;
        broadcastClear();
    }

    public void setVisible(boolean v) {
        if (this.visible == v) return;
        this.visible = v;
        broadcastVisibility();
    }

    public void setScale(double sx, double sy, double sz) {
        this.scaleX = sx;
        this.scaleY = sy;
        this.scaleZ = sz;
        setChanged();
        broadcastTransform();
    }

    public void setOffset(double dx, double dy, double dz) {
        this.offsetX = dx;
        // dy clamp per hologram.txt — projection must never intersect the block.
        this.offsetY = Math.max(0.5, dy);
        this.offsetZ = dz;
        setChanged();
        broadcastTransform();
    }

    public void setRotation(double yaw, double pitch, double roll) {
        this.yawDeg = yaw;
        this.pitchDeg = pitch;
        this.rollDeg = roll;
        this.rotationExplicit = true;
        setChanged();
        broadcastTransform();
    }

    /** Clear the "explicit" flag and snap rotation back to world-grid-aligned
     *  (0,0,0). Called from content-upload paths so a fresh upload starts
     *  world-aligned unless the script then calls setRotation. */
    private void resetRotationForNewContent() {
        if (this.rotationExplicit || yawDeg != 0.0 || pitchDeg != 0.0 || rollDeg != 0.0) {
            this.yawDeg = 0.0;
            this.pitchDeg = 0.0;
            this.rollDeg = 0.0;
            this.rotationExplicit = false;
            broadcastTransform();
        }
    }

    public void setColor(int argb) {
        this.colorARGB = argb;
        setChanged();
        broadcastTransform();
    }

    /** Set the global alpha multiplier. Range 0.0–1.0 = explicit user
     *  control (no cap). Pass -1.0 to revert to the legacy translucent
     *  cap behavior (MAX_HOLO_ALPHA=180 for 2D, =60 for voxels). */
    public void setAlphaMultiplier(float multiplier) {
        if (multiplier < 0) this.alphaMultiplier = -1.0f;
        else this.alphaMultiplier = Math.min(1.0f, multiplier);
        setChanged();
        broadcastTransform();
    }

    /**
     * Switch the active rendering mode without mutating content. If the new
     * mode is one of the 3D variants and voxel data is already loaded, we
     * re-broadcast so clients rebuild their mesh under the new meshing
     * strategy (CULLED ↔ FULL produce different quad sets). Switching to
     * {@link HologramMode#MODE_2D} while holding voxel data just sets the
     * mode; the voxels stay on the server until either a new 2D image
     * arrives or {@code clear()} is called — the renderer will ignore them
     * because the mode no longer matches.
     */
    public void setHologramMode(HologramMode newMode) {
        if (newMode == this.mode) return;
        boolean wasVoxel = this.mode == HologramMode.MODE_3D_CULLED
                        || this.mode == HologramMode.MODE_3D_FULL;
        boolean willVoxel = newMode == HologramMode.MODE_3D_CULLED
                         || newMode == HologramMode.MODE_3D_FULL;
        this.mode = newMode;
        // Re-broadcast voxel packet only when both old and new are 3D and
        // we have data loaded — that's the only transition where clients
        // must re-mesh. Switching to IDLE/2D without content needs no
        // packet; it becomes effective when new content is uploaded.
        if (wasVoxel && willVoxel && voxCompressedIndexes != null && voxPalette != null) {
            broadcastVoxel();
        }
    }

    // ---- network broadcast helpers -----------------------------------------

    private void broadcastData() {
        if (!(level instanceof ServerLevel sl) || compressedImage == null) return;
        HologramDataPacket pk = new HologramDataPacket(
                worldPosition, HologramMode.MODE_2D, imageWidth, imageHeight, compressedImage);
        PacketDistributor.TargetPoint tp = new PacketDistributor.TargetPoint(
                worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                192.0, sl.dimension());
        DeceasedNetwork.CHANNEL.send(PacketDistributor.NEAR.with(() -> tp), pk);
    }

    private void broadcastVoxel() {
        if (!(level instanceof ServerLevel sl) || voxCompressedIndexes == null || voxPalette == null) return;
        HologramVoxelPacket pk = new HologramVoxelPacket(
                worldPosition, mode, voxSizeX, voxSizeY, voxSizeZ, voxPalette, voxCompressedIndexes);
        PacketDistributor.TargetPoint tp = new PacketDistributor.TargetPoint(
                worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                192.0, sl.dimension());
        DeceasedNetwork.CHANNEL.send(PacketDistributor.NEAR.with(() -> tp), pk);
    }

    private void broadcastMarkers() {
        if (!(level instanceof ServerLevel sl) || markerXs == null) return;
        HologramMarkersPacket pk = new HologramMarkersPacket(
                worldPosition, mode,
                markersSizeX, markersSizeY, markersSizeZ, markersCount,
                markerXs, markerYs, markerZs,
                markerShapes, markerColors,
                markerScaleXs, markerScaleYs, markerScaleZs,
                markerYaws, markerPitches);
        PacketDistributor.TargetPoint tp = new PacketDistributor.TargetPoint(
                worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                192.0, sl.dimension());
        DeceasedNetwork.CHANNEL.send(PacketDistributor.NEAR.with(() -> tp), pk);
    }

    private void broadcastVisibility() {
        if (!(level instanceof ServerLevel sl)) return;
        PacketDistributor.TargetPoint tp = new PacketDistributor.TargetPoint(
                worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                192.0, sl.dimension());
        DeceasedNetwork.CHANNEL.send(PacketDistributor.NEAR.with(() -> tp),
                new HologramVisibilityPacket(worldPosition, visible));
    }

    private void broadcastTransform() {
        if (!(level instanceof ServerLevel sl)) return;
        PacketDistributor.TargetPoint tp = new PacketDistributor.TargetPoint(
                worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                192.0, sl.dimension());
        DeceasedNetwork.CHANNEL.send(PacketDistributor.NEAR.with(() -> tp),
                new HologramTransformPacket(worldPosition,
                        (float) scaleX, (float) scaleY, (float) scaleZ,
                        (float) offsetX, (float) offsetY, (float) offsetZ,
                        (float) yawDeg, (float) pitchDeg, (float) rollDeg,
                        colorARGB,
                        alphaMultiplier,
                        liveCameraMode,
                        pairedCameraPos == null
                                ? HologramTransformPacket.NO_PAIR
                                : pairedCameraPos.asLong(),
                        feedWidth, feedHeight, feedFov));
    }

    private void broadcastClear() {
        if (!(level instanceof ServerLevel sl)) return;
        PacketDistributor.TargetPoint tp = new PacketDistributor.TargetPoint(
                worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                192.0, sl.dimension());
        DeceasedNetwork.CHANNEL.send(PacketDistributor.NEAR.with(() -> tp),
                new HologramClearPacket(worldPosition));
    }

    /** Send the full current state to a single just-joined player. Called
     *  by {@code ChunkEvent.Watch} (registered in DeceasedCC common setup)
     *  so clients entering render range get the hologram immediately, not
     *  only on the next scripted mutation. */
    public void resyncTo(ServerPlayer player) {
        if (compressedImage != null) {
            DeceasedNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new HologramDataPacket(worldPosition, HologramMode.MODE_2D,
                            imageWidth, imageHeight, compressedImage));
        } else if (voxCompressedIndexes != null && voxPalette != null) {
            DeceasedNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new HologramVoxelPacket(worldPosition, mode,
                            voxSizeX, voxSizeY, voxSizeZ, voxPalette, voxCompressedIndexes));
        }
        // Markers payload — independent of voxel/image; COMPOSITE sends
        // both voxel (above) AND markers (here).
        if (markerXs != null && markersCount > 0) {
            DeceasedNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new HologramMarkersPacket(worldPosition, mode,
                            markersSizeX, markersSizeY, markersSizeZ, markersCount,
                            markerXs, markerYs, markerZs,
                            markerShapes, markerColors,
                            markerScaleXs, markerScaleYs, markerScaleZs,
                            markerYaws, markerPitches));
        }
        DeceasedNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new HologramTransformPacket(worldPosition,
                        (float) scaleX, (float) scaleY, (float) scaleZ,
                        (float) offsetX, (float) offsetY, (float) offsetZ,
                        (float) yawDeg, (float) pitchDeg, (float) rollDeg,
                        colorARGB,
                        alphaMultiplier,
                        liveCameraMode,
                        pairedCameraPos == null
                                ? HologramTransformPacket.NO_PAIR
                                : pairedCameraPos.asLong(),
                        feedWidth, feedHeight, feedFov));
        DeceasedNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new HologramVisibilityPacket(worldPosition, visible));
    }

    // ---- NBT ---------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putDouble("sx", scaleX); tag.putDouble("sy", scaleY); tag.putDouble("sz", scaleZ);
        tag.putDouble("ox", offsetX); tag.putDouble("oy", offsetY); tag.putDouble("oz", offsetZ);
        tag.putDouble("rYaw", yawDeg); tag.putDouble("rPit", pitchDeg); tag.putDouble("rRol", rollDeg);
        tag.putInt("color", colorARGB);
        tag.putFloat("alphaMultiplier", alphaMultiplier);
        if (pairedCameraPos != null) tag.putLong("pairedCameraPos", pairedCameraPos.asLong());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("sx")) { scaleX = tag.getDouble("sx"); scaleY = tag.getDouble("sy"); scaleZ = tag.getDouble("sz"); }
        if (tag.contains("ox")) { offsetX = tag.getDouble("ox"); offsetY = tag.getDouble("oy"); offsetZ = tag.getDouble("oz"); }
        if (tag.contains("rYaw")) { yawDeg = tag.getDouble("rYaw"); pitchDeg = tag.getDouble("rPit"); rollDeg = tag.getDouble("rRol"); }
        if (tag.contains("color")) colorARGB = tag.getInt("color");
        if (tag.contains("alphaMultiplier")) alphaMultiplier = tag.getFloat("alphaMultiplier");
        if (tag.contains("pairedCameraPos")) {
            this.pairedCameraPos = BlockPos.of(tag.getLong("pairedCameraPos"));
            HologramLinkRegistry.pair(pairedCameraPos, worldPosition);
        } else {
            this.pairedCameraPos = null;
        }
    }
}
