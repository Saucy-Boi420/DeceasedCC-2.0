package net.deceasedcraft.deceasedcc.server;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.network.CameraSnapshotRequestPacket;
import net.deceasedcraft.deceasedcc.network.CameraSnapshotResponsePacket;
import net.deceasedcraft.deceasedcc.network.DeceasedNetwork;
import net.deceasedcraft.deceasedcc.scan.ScanFile;
import net.deceasedcraft.deceasedcc.scan.ScanRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Phase 8.3 — server-side coordinator for camera-view snapshots.
 *
 * <p>Flow:
 * <ol>
 *   <li>Lua calls {@code cameraSaveSnapshot2D}.</li>
 *   <li>Peripheral calls {@link #requestSnapshot} here with the camera pos,
 *       requested resolution/FOV, and an event sink (controller's Lua
 *       event-relay).</li>
 *   <li>We pick the closest eligible {@link ServerPlayer} in the camera's
 *       level, send them a {@link CameraSnapshotRequestPacket}, and
 *       register a pending entry keyed by frameId.</li>
 *   <li>Client captures via {@code CameraFeedRenderer.captureTo} and
 *       replies with a {@link CameraSnapshotResponsePacket}.</li>
 *   <li>{@link #onResponse} persists the ScanFile and fires a
 *       {@code deceasedcc_snapshot_complete} event to the originating
 *       controller's attached computers (or
 *       {@code deceasedcc_snapshot_failed} on error/timeout).</li>
 * </ol>
 *
 * <p>Pending requests that don't get a response within
 * {@link #TIMEOUT_MS} ms fail out; the tick handler sweeps them.
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID)
public final class CameraSnapshotCoordinator {
    private CameraSnapshotCoordinator() {}

    public static final long TIMEOUT_MS = 3000L;

    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_FRAME_ID = new AtomicInteger(0);

    private record Pending(String snapshotName,
                            BlockPos cameraPos,
                            BiConsumer<String, Object[]> eventSink,
                            long expiryMs) {}

    /**
     * Dispatch a snapshot request to the closest eligible client in the
     * camera's dimension. Returns the snapshot name synchronously so the
     * Lua caller can use it (the actual ScanRegistry entry won't exist
     * until the client responds — Lua should wait on
     * {@code deceasedcc_snapshot_complete} before using it).
     *
     * @param eventSink called on completion or failure. Arguments:
     *                  ({@code name}) for complete,
     *                  ({@code name}, {@code error}) for failed.
     * @return the snapshot name if dispatched; null if no eligible client.
     */
    @Nullable
    public static String requestSnapshot(ServerLevel level, BlockPos cameraPos,
                                          float yaw, float pitch,
                                          int width, int height, float fov,
                                          BiConsumer<String, Object[]> eventSink) {
        ServerPlayer target = pickClosestPlayer(level, cameraPos);
        if (target == null) {
            eventSink.accept("deceasedcc_snapshot_failed", new Object[]{"", "no eligible client"});
            return null;
        }
        int frameId = NEXT_FRAME_ID.incrementAndGet();
        long tick = level.getGameTime();
        String name = "camera2d_" + frameId + "_" + tick;
        PENDING.put(frameId, new Pending(name, cameraPos, eventSink,
                System.currentTimeMillis() + TIMEOUT_MS));
        DeceasedNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> target),
                new CameraSnapshotRequestPacket(frameId, cameraPos, yaw, pitch,
                        width, height, fov));
        return name;
    }

    public static void onResponse(CameraSnapshotResponsePacket p) {
        Pending pending = PENDING.remove(p.frameId());
        if (pending == null) {
            // Stale response — already timed out or never registered.
            return;
        }
        if (!p.success()) {
            pending.eventSink.accept("deceasedcc_snapshot_failed",
                    new Object[]{pending.snapshotName, p.errorMsg()});
            return;
        }
        ScanFile file = new ScanFile(
                "camera_snapshot",
                System.currentTimeMillis(),
                "camera_snapshot_2d",
                pending.cameraPos.getX(), pending.cameraPos.getY(), pending.cameraPos.getZ(),
                pending.cameraPos.getX(), pending.cameraPos.getY(), pending.cameraPos.getZ(),
                Collections.emptyList(),
                Collections.emptyList(),
                p.compressedARGB(),
                p.width(), p.height());
        ScanRegistry.put(pending.snapshotName, file);
        pending.eventSink.accept("deceasedcc_snapshot_complete",
                new Object[]{pending.snapshotName});
    }

    /** Pick the closest player in the same level as {@code cameraPos}. */
    @Nullable
    private static ServerPlayer pickClosestPlayer(ServerLevel level, BlockPos cameraPos) {
        List<ServerPlayer> players = level.players();
        ServerPlayer best = null;
        double bestDistSq = Double.MAX_VALUE;
        double cx = cameraPos.getX() + 0.5;
        double cy = cameraPos.getY() + 0.5;
        double cz = cameraPos.getZ() + 0.5;
        for (ServerPlayer p : players) {
            double dx = p.getX() - cx;
            double dy = p.getY() - cy;
            double dz = p.getZ() - cz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = p;
            }
        }
        return best;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (PENDING.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Pending> e = it.next();
            if (e.getValue().expiryMs < now) {
                Pending pending = e.getValue();
                pending.eventSink.accept("deceasedcc_snapshot_failed",
                        new Object[]{pending.snapshotName, "timeout"});
                it.remove();
            }
        }
    }
}
