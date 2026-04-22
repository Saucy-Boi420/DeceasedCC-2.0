package net.deceasedcraft.deceasedcc.core;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-dimension activation registry for turrets. A turret only runs its full
 * targeting/sentry/fire pipeline when it's "active": either a player is inside
 * its own effective range, or a player is inside the range of any turret whose
 * activation sphere chains into this one (daisy-chain via union-find/BFS on
 * the link graph). Same-dimension only — portals do not propagate activation.
 *
 * <p>The recompute runs once every {@link ModConfig#TURRET_ACTIVATION_RECOMPUTE_TICKS}
 * server ticks. The link graph is rebuilt only when registrations or ranges
 * change; player checks and BFS run every cycle.</p>
 *
 * <p>Single-threaded by construction — every public method must be called from
 * the server thread (BE.onLoad, BE.setRemoved, BE.serverTick, ServerTickEvent).
 * No concurrent collections needed.</p>
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID)
public final class TurretRegistry {

    public static final class TurretNode {
        public final BlockPos pos;
        public int effectiveRange;
        public final boolean isAdvanced;
        public long lastSeenTick;

        TurretNode(BlockPos pos, int effectiveRange, boolean isAdvanced) {
            this.pos = pos.immutable();
            this.effectiveRange = effectiveRange;
            this.isAdvanced = isAdvanced;
        }
    }

    private static final class DimState {
        final Map<BlockPos, TurretNode> nodes = new HashMap<>();
        boolean linkGraphDirty = true;
        final Map<BlockPos, List<BlockPos>> adjacency = new HashMap<>();
        final Map<BlockPos, Boolean> activationCache = new HashMap<>();
    }

    private static final Map<ResourceKey<Level>, DimState> DIMS = new HashMap<>();
    private static int ticksSinceRecompute = 0;

    private TurretRegistry() {}

    private static DimState dim(ResourceKey<Level> key) {
        return DIMS.computeIfAbsent(key, k -> new DimState());
    }

    public static void register(ServerLevel sl, BlockPos pos, int effectiveRange, boolean advanced) {
        DimState s = dim(sl.dimension());
        BlockPos imm = pos.immutable();
        TurretNode prev = s.nodes.get(imm);
        if (prev != null && prev.effectiveRange == effectiveRange && prev.isAdvanced == advanced) {
            return;
        }
        s.nodes.put(imm, new TurretNode(imm, effectiveRange, advanced));
        s.linkGraphDirty = true;
    }

    public static void unregister(ServerLevel sl, BlockPos pos) {
        DimState s = DIMS.get(sl.dimension());
        if (s == null) return;
        if (s.nodes.remove(pos) != null) {
            s.linkGraphDirty = true;
            s.activationCache.remove(pos);
            s.adjacency.remove(pos);
        }
    }

    public static void refreshRange(ServerLevel sl, BlockPos pos, int newRange) {
        DimState s = DIMS.get(sl.dimension());
        if (s == null) return;
        TurretNode n = s.nodes.get(pos);
        if (n != null && n.effectiveRange != newRange) {
            n.effectiveRange = newRange;
            s.linkGraphDirty = true;
        }
    }

    /** Snapshot of every registered turret position in a dimension. Used by
     *  {@link net.deceasedcraft.deceasedcc.turrets.TurretAggroSuppressor} to
     *  sweep nearby mobs and clear navigation targets pointed at turrets. */
    public static Collection<BlockPos> positionsInDim(ServerLevel sl) {
        DimState s = DIMS.get(sl.dimension());
        if (s == null || s.nodes.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(s.nodes.keySet());
    }

    /** Returns true if the BE should run its full tick pipeline. Defaults to
     *  true for unknown turrets so a freshly-loaded BE doesn't go silent
     *  during the first recompute window. */
    public static boolean isActive(ServerLevel sl, BlockPos pos) {
        DimState s = DIMS.get(sl.dimension());
        if (s == null) return true;
        Boolean cached = s.activationCache.get(pos);
        return cached == null || cached;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        int interval = Math.max(1, ModConfig.TURRET_ACTIVATION_RECOMPUTE_TICKS.get());
        if (++ticksSinceRecompute < interval) return;
        ticksSinceRecompute = 0;
        MinecraftServer server = ev.getServer();
        if (server == null) return;
        for (ServerLevel sl : server.getAllLevels()) {
            DimState s = DIMS.get(sl.dimension());
            if (s == null || s.nodes.isEmpty()) continue;
            recompute(sl, s);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent ev) {
        DIMS.clear();
        ticksSinceRecompute = 0;
    }

    private static void recompute(ServerLevel sl, DimState s) {
        if (s.linkGraphDirty) {
            rebuildLinkGraph(s);
            s.linkGraphDirty = false;
        }
        Set<BlockPos> active = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        for (TurretNode n : s.nodes.values()) {
            boolean hasPlayer = sl.getNearestPlayer(
                    n.pos.getX() + 0.5, n.pos.getY() + 0.5, n.pos.getZ() + 0.5,
                    n.effectiveRange, false) != null;
            if (hasPlayer) {
                if (active.add(n.pos)) {
                    queue.add(n.pos);
                    depth.put(n.pos, 0);
                }
            }
        }
        int maxDepth = Math.max(1, ModConfig.TURRET_MAX_CHAIN_DEPTH.get());
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            int d = depth.get(cur);
            if (d >= maxDepth) continue;
            List<BlockPos> neighbors = s.adjacency.get(cur);
            if (neighbors == null) continue;
            for (BlockPos nb : neighbors) {
                if (active.add(nb)) {
                    depth.put(nb, d + 1);
                    queue.add(nb);
                }
            }
        }
        s.activationCache.clear();
        long now = sl.getGameTime();
        for (TurretNode n : s.nodes.values()) {
            s.activationCache.put(n.pos, active.contains(n.pos));
            n.lastSeenTick = now;
        }
    }

    private static void rebuildLinkGraph(DimState s) {
        s.adjacency.clear();
        TurretNode[] arr = s.nodes.values().toArray(new TurretNode[0]);
        for (TurretNode n : arr) s.adjacency.put(n.pos, new ArrayList<>());
        for (int i = 0; i < arr.length; i++) {
            TurretNode a = arr[i];
            for (int j = i + 1; j < arr.length; j++) {
                TurretNode b = arr[j];
                long dx = a.pos.getX() - b.pos.getX();
                long dy = a.pos.getY() - b.pos.getY();
                long dz = a.pos.getZ() - b.pos.getZ();
                long sq = dx * dx + dy * dy + dz * dz;
                long thr = (long) a.effectiveRange + (long) b.effectiveRange;
                if (sq <= thr * thr) {
                    s.adjacency.get(a.pos).add(b.pos);
                    s.adjacency.get(b.pos).add(a.pos);
                }
            }
        }
    }

    // --- Observability hooks (used by /deceasedcc debug in Stage 6) ---

    public static int countRegistered(ResourceKey<Level> dim) {
        DimState s = DIMS.get(dim);
        return s == null ? 0 : s.nodes.size();
    }

    public static int countActive(ResourceKey<Level> dim) {
        DimState s = DIMS.get(dim);
        if (s == null) return 0;
        int n = 0;
        for (boolean v : s.activationCache.values()) if (v) n++;
        return n;
    }

    public static Collection<TurretNode> snapshot(ResourceKey<Level> dim) {
        DimState s = DIMS.get(dim);
        if (s == null) return Collections.emptyList();
        return new ArrayList<>(s.nodes.values());
    }

    public static int linkedCount(ResourceKey<Level> dim, BlockPos pos) {
        DimState s = DIMS.get(dim);
        if (s == null) return 0;
        List<BlockPos> nbrs = s.adjacency.get(pos);
        return nbrs == null ? 0 : nbrs.size();
    }
}
