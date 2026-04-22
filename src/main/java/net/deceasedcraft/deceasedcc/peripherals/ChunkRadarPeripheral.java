package net.deceasedcraft.deceasedcc.peripherals;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.blocks.entity.ChunkRadarBlockEntity;
import net.deceasedcraft.deceasedcc.core.ModConfig;
import net.deceasedcraft.deceasedcc.scan.ScanFile;
import net.deceasedcraft.deceasedcc.scan.ScanRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Phase 3 — Chunk Radar peripheral. Exposes {@code chunk_radar} to CC.
 *
 * <p>Key invariants:
 * <ul>
 *   <li>Scans run on {@link ScanThread}, never on the server tick thread.</li>
 *   <li>A scan captures a defensive snapshot of the affected block positions
 *   on the main thread, then the iteration/classification happens off-thread.
 *   This avoids concurrent modification of the chunk storage.</li>
 *   <li>Per-peripheral cooldown: we only allow one actual scan every
 *   {@code chunkRadar.cooldownSeconds} seconds. Requests inside the cooldown
 *   window return the cached result flagged {@code stale = true}.</li>
 * </ul>
 */
public class ChunkRadarPeripheral implements IPeripheral {
    // Forge ore tag. We intentionally use the Forge "ores" umbrella tag, not
    // specific ore tags, so modded ores that participate in the convention
    // are picked up.
    private static final TagKey<Block> FORGE_ORES = BlockTags.create(new ResourceLocation("forge", "ores"));

    private final ChunkRadarBlockEntity host;
    private final AtomicReference<CachedScan> cache = new AtomicReference<>(null);
    private final Set<IComputerAccess> attached = ConcurrentHashMap.newKeySet();
    // Phase 7a — event relay. When this radar is linked to an
    // AdvancedNetworkController, the controller registers itself here so
    // events fired via fireJobComplete also fan out to computers attached
    // to the controller. Null when nobody is relaying.
    @Nullable private volatile BiConsumer<String, Object[]> upstreamRelay;
    private volatile long lastScanEpochMs = 0L;
    private volatile boolean scanInFlight = false;

    public ChunkRadarPeripheral(ChunkRadarBlockEntity host) {
        this.host = host;
    }

    @Override public void attach(IComputerAccess c) { attached.add(c); }
    @Override public void detach(IComputerAccess c) { attached.remove(c); }

    /** Set or clear the upstream event relay. A non-null relay receives
     *  every CC event this peripheral fires, in the same order as its
     *  directly-attached computers. Idempotent — re-setting to the same
     *  BiConsumer is fine. */
    public void setUpstreamRelay(@Nullable BiConsumer<String, Object[]> relay) {
        this.upstreamRelay = relay;
    }

    /** Called by {@link ChunkRadarBlockEntity#serverTick} when an incremental
     *  terrain scan finishes — broadcasts a CC event so waiting scripts can
     *  proceed with {@code os.pullEvent("radar_scan_complete")}. */
    public void fireJobComplete(ChunkScanJob job) {
        Object[] args = { job.jobId, job.name, job.pointsSoFar() };
        for (IComputerAccess c : attached) {
            c.queueEvent("radar_scan_complete", args);
        }
        BiConsumer<String, Object[]> relay = upstreamRelay;
        if (relay != null) {
            try { relay.accept("radar_scan_complete", args); } catch (Exception ignored) {}
        }
    }

    @Override
    public String getType() {
        return "chunk_radar";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof ChunkRadarPeripheral o && o.host == this.host;
    }

    @LuaFunction(mainThread = true)
    public final int getScanRadius() {
        return ModConfig.CHUNK_RADAR_MAX_RADIUS.get();
    }

    /** Absolute world position of this radar block. Scripts need this to
     *  compute an AABB centred on the radar for {@link #scanArea}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getPosition() {
        var p = host.getBlockPos();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", p.getX());
        out.put("y", p.getY());
        out.put("z", p.getZ());
        return out;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getLastScan() throws LuaException {
        CachedScan c = cache.get();
        if (c == null) throw new LuaException("no scan recorded yet");
        return c.toLuaTable(true);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> scan(int radius) throws LuaException {
        return runScan(clamp(radius), null, false);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> scanOres(int radius) throws LuaException {
        return runScan(clamp(radius), null, true);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> scanMod(int radius, String modid) throws LuaException {
        if (modid == null || modid.isBlank()) throw new LuaException("modid required");
        return runScan(clamp(radius), modid, false);
    }

    /** Scan and return only positions matching a specific block registry name. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> scanForBlock(int radius, String blockId) throws LuaException {
        if (blockId == null || blockId.isBlank()) throw new LuaException("blockId required");
        Map<String, Object> full = runScan(clamp(radius), null, false);
        Map<Integer, Object> filtered = new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<Integer, Map<String, Object>> entries = (Map<Integer, Map<String, Object>>) full.get("entries");
        int i = 1;
        for (Map<String, Object> e : entries.values()) {
            if (blockId.equals(e.get("block"))) filtered.put(i++, e);
        }
        full.put("entries", filtered);
        full.put("count", filtered.size());
        return full;
    }

    /** Return relative positions of air / replaceable blocks only. Useful for
     *  quick line-of-sight checks against a known target area. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> scanEmpty(int radius) throws LuaException {
        int r = clamp(radius);
        Level level = host.getLevel();
        if (level == null || level.isClientSide) throw new LuaException("no server level");
        BlockPos origin = host.getBlockPos();
        Map<Integer, Map<String, Object>> out = new HashMap<>();
        int idx = 1;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos p = origin.offset(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("x", x); row.put("y", y); row.put("z", z);
                        out.put(idx++, row);
                    }
                }
            }
        }
        Map<String, Object> res = new HashMap<>();
        res.put("radius", r);
        res.put("count", out.size());
        res.put("entries", out);
        return res;
    }

    // --- Area scan + shared file API (v1.2.0+) -------------------------------

    /**
     * Start an asynchronous terrain scan over an absolute-coord AABB. Returns
     * immediately with a job id; the actual scan is paced across server ticks
     * at {@code ModConfig.CHUNK_RADAR_MAX_BLOCKS_PER_TICK} blocks per tick so large areas
     * (e.g. 3-chunk radius = ~310k candidate positions) don't stall the
     * server.
     *
     * <p>When the scan finishes, a CC event {@code radar_scan_complete} is
     * fired with {@code (jobId, name, pointCount)}. Lua pattern:
     * <pre>
     *   local r = radar.scanArea(x1,y1,z1, x2,y2,z2, "map")
     *   repeat
     *     local _, id = os.pullEvent("radar_scan_complete")
     *   until id == r.jobId
     * </pre>
     * Only one job runs per radar at a time — a second call while one is
     * active throws.
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> scanArea(int x1, int y1, int z1, int x2, int y2, int z2, String name) throws LuaException {
        if (name == null || name.isBlank()) throw new LuaException("file name required");
        Level level = host.getLevel();
        if (level == null || level.isClientSide) throw new LuaException("no server level");
        if (host.hasActiveJob()) throw new LuaException("another scan is already running on this radar");

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        String jobId = java.util.UUID.randomUUID().toString().substring(0, 8);
        var origin = host.getBlockPos();
        var job = new ChunkScanJob(jobId, name,
                "chunk_radar@" + origin.toShortString(),
                minX, minY, minZ, maxX, maxY, maxZ);
        host.startJob(job);

        long total = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        int perTick;
        try {
            perTick = net.deceasedcraft.deceasedcc.core.ModConfig.CHUNK_RADAR_MAX_BLOCKS_PER_TICK.get();
        } catch (Throwable t) {
            perTick = ChunkScanJob.DEFAULT_BLOCKS_PER_TICK;
        }
        long estTicks = (total + perTick - 1) / perTick;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        out.put("name", name);
        out.put("status", "scanning");
        out.put("totalBlocks", total);
        out.put("estimatedTicks", estTicks);
        out.put("min", Map.of("x", minX, "y", minY, "z", minZ));
        out.put("max", Map.of("x", maxX, "y", maxY, "z", maxZ));
        return out;
    }

    /** Current progress of the active job, or {@code {status = "idle"}}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getScanProgress() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!host.hasActiveJob()) { out.put("status", "idle"); return out; }
        // Pull from the BE - the job itself lives there.
        // We only expose enough for scripts to show a progress bar; the
        // completion event carries the final count.
        out.put("status", "scanning");
        return out;
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, String> listFiles() {
        Map<Integer, String> out = new LinkedHashMap<>();
        int i = 1;
        for (String n : ScanRegistry.names()) out.put(i++, n);
        return out;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> readFile(String name) throws LuaException {
        if (name == null) throw new LuaException("name required");
        ScanFile f = ScanRegistry.get(name);
        if (f == null) throw new LuaException("no scan file named '" + name + "'");
        return f.toLuaFull();
    }

    @LuaFunction(mainThread = true)
    public final boolean deleteFile(String name) throws LuaException {
        if (name == null) throw new LuaException("name required");
        return ScanRegistry.remove(name);
    }

    /** Single-block synchronous read — returns block id, mod id, and the
     *  BlockEntity NBT as a nested Lua table. Cheap enough to call per-rack
     *  in a wine-cellar refresh loop (no tick pacing needed). */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> inspect(int x, int y, int z) throws LuaException {
        Level level = host.getLevel();
        if (level == null || level.isClientSide) throw new LuaException("no server level");
        BlockPos p = new BlockPos(x, y, z);
        var s = level.getBlockState(p);
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(s.getBlock());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", x); out.put("y", y); out.put("z", z);
        out.put("block", id == null ? "minecraft:air" : id.toString());
        out.put("mod", id == null ? "minecraft" : id.getNamespace());
        out.put("air", s.isAir());
        var be = level.getBlockEntity(p);
        if (be != null) {
            CompoundTag t = be.saveWithoutMetadata();
            if (t != null && !t.isEmpty()) out.put("nbt", nbtToLua(t));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object nbtToLua(net.minecraft.nbt.Tag tag) {
        if (tag instanceof CompoundTag ct) {
            Map<String, Object> o = new java.util.HashMap<>();
            for (String k : ct.getAllKeys()) {
                var c = ct.get(k);
                if (c != null) o.put(k, nbtToLua(c));
            }
            return o;
        }
        if (tag instanceof net.minecraft.nbt.ListTag lt) {
            Map<Integer, Object> o = new java.util.HashMap<>();
            for (int i = 0; i < lt.size(); i++) o.put(i + 1, nbtToLua(lt.get(i)));
            return o;
        }
        if (tag instanceof net.minecraft.nbt.ByteTag b)   return (int) b.getAsByte();
        if (tag instanceof net.minecraft.nbt.ShortTag s)  return (int) s.getAsShort();
        if (tag instanceof net.minecraft.nbt.IntTag i)    return i.getAsInt();
        if (tag instanceof net.minecraft.nbt.LongTag l)   return l.getAsLong();
        if (tag instanceof net.minecraft.nbt.FloatTag f)  return (double) f.getAsFloat();
        if (tag instanceof net.minecraft.nbt.DoubleTag d) return d.getAsDouble();
        return tag.getAsString();
    }

    /** Re-run an existing scan with its original bounds and overwrite it.
     *  Ideal for periodic refresh loops (wine cellar, storage inventory,
     *  etc.) — call this every N seconds and each completion fires a
     *  {@code radar_scan_complete} event just like a fresh scanArea. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> rescan(String name) throws LuaException {
        if (name == null || name.isBlank()) throw new LuaException("name required");
        ScanFile existing = ScanRegistry.get(name);
        if (existing == null) throw new LuaException("no scan file named '" + name + "'");
        return scanArea(existing.minX(), existing.minY(), existing.minZ(),
                        existing.maxX(), existing.maxY(), existing.maxZ(), name);
    }

    /** True if every block between the radar centre and the target point is
     *  a non-opaque block (air, water, glass, fluids, etc.). Uses a cheap
     *  Bresenham-style step across world blocks; server thread only. */
    @LuaFunction(mainThread = true)
    public final boolean hasLineOfSight(double targetX, double targetY, double targetZ) throws LuaException {
        Level level = host.getLevel();
        if (level == null || level.isClientSide) throw new LuaException("no server level");
        var from = host.getBlockPos().getCenter();
        var toVec = new net.minecraft.world.phys.Vec3(targetX, targetY, targetZ);
        var ctx = new net.minecraft.world.level.ClipContext(
                from, toVec,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                null);
        var hit = level.clip(ctx);
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    // --- internals ---

    private int clamp(int r) throws LuaException {
        int max = ModConfig.CHUNK_RADAR_MAX_RADIUS.get();
        if (r < 1) throw new LuaException("radius must be >= 1");
        if (r > max) throw new LuaException("radius exceeds configured max " + max);
        if (r > 8) {
            DeceasedCC.LOGGER.warn("ChunkRadar at {} scanning with radius {} — expect tick impact", host.getBlockPos(), r);
        }
        return r;
    }

    private Map<String, Object> runScan(int radius, @Nullable String modFilter, boolean oresOnly) throws LuaException {
        long cooldownMs = ModConfig.CHUNK_RADAR_COOLDOWN_SECONDS.get() * 1000L;
        long now = System.currentTimeMillis();

        if (scanInFlight || (now - lastScanEpochMs) < cooldownMs) {
            CachedScan c = cache.get();
            if (c == null) throw new LuaException("scan cooling down; no cached scan yet");
            return c.toLuaTable(true);
        }

        Level level = host.getLevel();
        if (level == null || level.isClientSide) throw new LuaException("no server level");

        BlockPos origin = host.getBlockPos();
        List<BlockPos> positions = collectPositions(origin, radius);

        scanInFlight = true;
        // We don't actually block the Lua call — we build the snapshot on the
        // main thread (mainThread=true), then dispatch classification off-thread
        // and wait briefly. In practice positions are collected quickly and the
        // classification is the expensive part.
        List<SnapshotEntry> snapshot = new ArrayList<>(positions.size());
        for (BlockPos p : positions) {
            BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            BlockEntity be = level.getBlockEntity(p);
            CompoundTag tag = be == null ? null : be.saveWithoutMetadata();
            snapshot.add(new SnapshotEntry(p.subtract(origin), s, tag));
        }

        CompletableFuture<CachedScan> fut = CompletableFuture.supplyAsync(
                () -> classify(snapshot, modFilter, oresOnly, radius),
                ScanThread.pool());

        try {
            CachedScan result = fut.get();
            cache.set(result);
            lastScanEpochMs = System.currentTimeMillis();
            return result.toLuaTable(false);
        } catch (Exception e) {
            throw new LuaException("scan failed: " + e.getMessage());
        } finally {
            scanInFlight = false;
        }
    }

    private static List<BlockPos> collectPositions(BlockPos origin, int radius) {
        List<BlockPos> positions = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    positions.add(origin.offset(x, y, z));
                }
            }
        }
        return positions;
    }

    private CachedScan classify(List<SnapshotEntry> snapshot, @Nullable String modFilter, boolean oresOnly, int radius) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SnapshotEntry e : snapshot) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(e.state.getBlock());
            if (id == null) continue;
            String modId = id.getNamespace();
            if (modFilter != null && !modFilter.equals(modId)) continue;
            if (oresOnly && !e.state.is(FORGE_ORES)) continue;

            Map<String, Object> row = new HashMap<>();
            row.put("x", e.offset.getX());
            row.put("y", e.offset.getY());
            row.put("z", e.offset.getZ());
            row.put("block", id.toString());
            row.put("mod", modId);
            if (e.nbt != null) row.put("nbt", nbtToLua(e.nbt));
            rows.add(row);
        }
        return new CachedScan(System.currentTimeMillis(), radius, rows);
    }

    // Note: an older CompoundTag-specific nbtToLua overload lived here and
    // was picked by Java's most-specific-method resolution whenever
    // inspect() / classify() passed a CompoundTag. That overload only
    // recursed on CompoundTag children and stringified everything else —
    // including ListTag — which broke Lua callers iterating things like
    // `detail.nbt.Items` (rendered as a `"[{...}, {...}]"` string instead
    // of a table). Deleted; the Tag-taking version above handles all the
    // types correctly.

    private record SnapshotEntry(BlockPos offset, BlockState state, @Nullable CompoundTag nbt) {}

    private record CachedScan(long epochMs, int radius, List<Map<String, Object>> rows) {
        Map<String, Object> toLuaTable(boolean stale) {
            Map<String, Object> out = new HashMap<>();
            out.put("stale", stale);
            out.put("timestamp", epochMs);
            out.put("radius", radius);
            out.put("count", rows.size());
            // Lua tables are 1-indexed. We build a numeric-keyed map so luaj
            // materialises it as a proper array-style table.
            Map<Integer, Object> indexed = new HashMap<>();
            for (int i = 0; i < rows.size(); i++) indexed.put(i + 1, rows.get(i));
            out.put("entries", Collections.unmodifiableMap(indexed));
            return out;
        }
    }
}
