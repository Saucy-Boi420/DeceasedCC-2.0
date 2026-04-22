package net.deceasedcraft.deceasedcc.util;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 8 — transient bidirectional camera↔projector pairing index.
 *
 * <p>The authoritative state lives in each BE's NBT (see
 * {@code CameraBlockEntity.pairedProjectorPos} and
 * {@code HologramProjectorBlockEntity.pairedCameraPos}). This registry
 * exists so either side can resolve its counterpart in O(1) without
 * iterating every loaded BE. Repopulated from BE load() calls; updated
 * on pair / unpair operations.
 *
 * <p>Bidirectional: setting a link from A to B also sets B to A. Either
 * side breaking (setRemoved) clears both entries. Same pattern as
 * {@link net.deceasedcraft.deceasedcc.blocks.entity.AdvancedNetworkControllerBlockEntity}'s
 * DEVICE_TO_CONTROLLER reverse index — the camera↔projector relationship
 * is peer-to-peer, not device-to-controller, so it needs its own map.
 */
public final class HologramLinkRegistry {
    private HologramLinkRegistry() {}

    private static final ConcurrentHashMap<BlockPos, BlockPos> CAMERA_TO_PROJECTOR = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, BlockPos> PROJECTOR_TO_CAMERA = new ConcurrentHashMap<>();

    /** Record a bidirectional pair. Overwrites any prior pairing on either
     *  side — callers are responsible for unpairing the previous counterpart
     *  first if they want to avoid dangling half-pairs. */
    public static void pair(BlockPos camera, BlockPos projector) {
        BlockPos c = camera.immutable();
        BlockPos p = projector.immutable();
        CAMERA_TO_PROJECTOR.put(c, p);
        PROJECTOR_TO_CAMERA.put(p, c);
    }

    /** Clear a pairing by its camera side. Also removes the reverse entry
     *  if it still pointed at this camera. */
    public static void unpairByCamera(BlockPos camera) {
        BlockPos c = camera.immutable();
        BlockPos p = CAMERA_TO_PROJECTOR.remove(c);
        if (p != null) PROJECTOR_TO_CAMERA.remove(p, c);
    }

    /** Clear a pairing by its projector side. */
    public static void unpairByProjector(BlockPos projector) {
        BlockPos p = projector.immutable();
        BlockPos c = PROJECTOR_TO_CAMERA.remove(p);
        if (c != null) CAMERA_TO_PROJECTOR.remove(c, p);
    }

    @Nullable
    public static BlockPos getProjectorForCamera(BlockPos camera) {
        return CAMERA_TO_PROJECTOR.get(camera.immutable());
    }

    @Nullable
    public static BlockPos getCameraForProjector(BlockPos projector) {
        return PROJECTOR_TO_CAMERA.get(projector.immutable());
    }
}
