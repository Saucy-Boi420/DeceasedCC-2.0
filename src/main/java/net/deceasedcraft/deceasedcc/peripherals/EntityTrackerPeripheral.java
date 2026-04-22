package net.deceasedcraft.deceasedcc.peripherals;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.blocks.entity.EntityTrackerBlockEntity;
import net.deceasedcraft.deceasedcc.core.ModConfig;
import net.deceasedcraft.deceasedcc.scan.ScanFile;
import net.deceasedcraft.deceasedcc.scan.ScanRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Phase 4 — Entity Tracker peripheral. Uses server-side AABB queries.
 *
 * <p>Watch lifecycle: {@link #watchEntity(String)} stores the UUID in a
 * per-peripheral set. Every 4 game ticks {@link #serverTick()} (called by the
 * block entity's static {@code serverTick} — see registration) compares
 * current vs. last-observed state, and queues a CC event
 * {@code entity_tracker_update} via {@link IComputerAccess#queueEvent}.</p>
 *
 * <p>Player UUID masking: if {@link ModConfig#ENTITY_TRACKER_EXPOSE_PLAYER_UUIDS}
 * is false (default), player rows omit the {@code uuid} field unless the
 * accessing computer is inside its owner's claimed area — which we can't
 * detect without a land-claim mod, so we simply omit universally and return a
 * stable hash of the UUID+world-seed instead. OPs bypass this via CC's
 * restricted-access mode (future expansion).</p>
 */
public class EntityTrackerPeripheral implements IPeripheral {
    private final EntityTrackerBlockEntity host;
    private final Set<IComputerAccess> attached = ConcurrentHashMap.newKeySet();
    private final Map<UUID, WatchState> watches = new ConcurrentHashMap<>();
    private final Map<String, AreaWatch> areaWatches = new ConcurrentHashMap<>();
    private int tickCounter;
    // Phase 7a — event relay to an upstream AdvancedNetworkController.
    // Applies to every CC event this peripheral queues: entity_tracker_update,
    // entity_proximity_enter, entity_proximity_leave. Null when no controller
    // is subscribed.
    @Nullable private volatile BiConsumer<String, Object[]> upstreamRelay;

    public EntityTrackerPeripheral(EntityTrackerBlockEntity host) {
        this.host = host;
    }

    @Override public String getType() { return "entity_tracker"; }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof EntityTrackerPeripheral o && o.host == this.host;
    }

    @Override public void attach(IComputerAccess computer) { attached.add(computer); }
    @Override public void detach(IComputerAccess computer) { attached.remove(computer); }

    /** See {@link ChunkRadarPeripheral#setUpstreamRelay} for semantics. */
    public void setUpstreamRelay(@Nullable BiConsumer<String, Object[]> relay) {
        this.upstreamRelay = relay;
    }

    /** Absolute world position of this tracker block. */
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
    public final Map<Integer, Map<String, Object>> getEntities(int radius) throws LuaException {
        return listAround(clamp(radius), EntityFilter.ALL);
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getHostile(int radius) throws LuaException {
        return listAround(clamp(radius), EntityFilter.HOSTILE);
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getPlayers(int radius) throws LuaException {
        return listAround(clamp(radius), EntityFilter.PLAYERS);
    }

    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getNearest(int radius) throws LuaException {
        Map<Integer, Map<String, Object>> all = listAround(clamp(radius), EntityFilter.ALL);
        Map<String, Object> best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos p = host.getBlockPos();
        for (Map<String, Object> row : all.values()) {
            double dx = ((Number) row.get("x")).doubleValue() - p.getX();
            double dy = ((Number) row.get("y")).doubleValue() - p.getY();
            double dz = ((Number) row.get("z")).doubleValue() - p.getZ();
            double d = dx*dx + dy*dy + dz*dz;
            if (d < bestDist) { bestDist = d; best = row; }
        }
        return best;
    }

    // --- Area scan + shared file API (v1.2.0) --------------------------------

    /** Scan an absolute-coord AABB for entities and write the result as a
     *  named ScanFile into the shared registry. Entities render as per-point
     *  colored dots in the hologram (red hostile / cyan player / green passive). */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> scanArea(int x1, int y1, int z1, int x2, int y2, int z2, String name) throws LuaException {
        if (name == null || name.isBlank()) throw new LuaException("file name required");
        if (!(host.getLevel() instanceof ServerLevel sl)) throw new LuaException("no server level");

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        int maxRange = ModConfig.ENTITY_TRACKER_MAX_RADIUS.get();
        var origin = host.getBlockPos();
        double distSq = Math.max(
                origin.distSqr(new BlockPos(minX, minY, minZ)),
                origin.distSqr(new BlockPos(maxX, maxY, maxZ)));
        if (distSq > (double) maxRange * maxRange * 3.0) {
            throw new LuaException("area extends beyond tracker's max range (" + maxRange + " blocks)");
        }

        AABB box = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        List<Entity> entities = sl.getEntities((Entity) null, box, Entity::isAlive);
        List<ScanFile.Point> points = new ArrayList<>();
        for (Entity e : entities) {
            int rgb;
            Map<String, Object> meta = new HashMap<>();
            var id = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
            meta.put("type", id == null ? "minecraft:unknown" : id.toString());
            meta.put("displayName", e.getDisplayName().getString());
            if (e instanceof Player) {
                rgb = 0x33CCFF;                                          // cyan player
                meta.put("isPlayer", true);
            } else if (isHostile(e)) {
                rgb = 0xE53935;                                          // red hostile
                meta.put("hostile", true);
            } else {
                rgb = 0x43A047;                                          // green passive
            }
            if (e instanceof LivingEntity le) {
                meta.put("health", le.getHealth());
                meta.put("maxHealth", le.getMaxHealth());
            }
            points.add(new ScanFile.Point(
                    (int) Math.floor(e.getX()),
                    (int) Math.floor(e.getY()),
                    (int) Math.floor(e.getZ()),
                    rgb, meta));
        }

        ScanFile file = new ScanFile(
                "entity_tracker@" + origin.toShortString(),
                System.currentTimeMillis(),
                "entities",
                minX, minY, minZ, maxX, maxY, maxZ,
                points);
        ScanRegistry.put(name, file);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("count", points.size());
        out.put("min", Map.of("x", minX, "y", minY, "z", minZ));
        out.put("max", Map.of("x", maxX, "y", maxY, "z", maxZ));
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

    @LuaFunction(mainThread = true)
    public final void watchEntity(String uuidStr) throws LuaException {
        try {
            watches.put(UUID.fromString(uuidStr), new WatchState());
        } catch (IllegalArgumentException ex) {
            throw new LuaException("invalid UUID: " + uuidStr);
        }
    }

    @LuaFunction(mainThread = true)
    public final void clearWatch() {
        watches.clear();
    }

    // --- Proximity area watches (v1.3.0) -------------------------------------

    /** Register a named AABB watch. While the watch is active, any entity
     *  entering the box fires {@code entity_proximity_enter(name, uuid, type, x, y, z)}
     *  and any leaving fires {@code entity_proximity_leave}. Re-using a name
     *  replaces the existing watch. */
    @LuaFunction(mainThread = true)
    public final void watchArea(int x1, int y1, int z1, int x2, int y2, int z2, String name) throws LuaException {
        if (name == null || name.isBlank()) throw new LuaException("name required");
        areaWatches.put(name, new AreaWatch(
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, Math.max(z1, z2) + 1,
                "area"));
        host.setChanged();
    }

    /** Register a spherical watch at (x, y, z) with the given radius. Uses an
     *  AABB for speed; corner cases include a few extra blocks, which is fine
     *  for proximity alerts. */
    @LuaFunction(mainThread = true)
    public final void watchPoint(int x, int y, int z, int radius, String name) throws LuaException {
        if (name == null || name.isBlank()) throw new LuaException("name required");
        if (radius < 1) throw new LuaException("radius must be >= 1");
        areaWatches.put(name, new AreaWatch(
                x - radius, y - radius, z - radius,
                x + radius + 1, y + radius + 1, z + radius + 1,
                "point"));
        host.setChanged();
    }

    /** Register a watch around an offset from this tracker. Useful for scripts
     *  that place the tracker as the reference frame. */
    @LuaFunction(mainThread = true)
    public final void watchRelative(int dx1, int dy1, int dz1, int dx2, int dy2, int dz2, String name) throws LuaException {
        BlockPos p = host.getBlockPos();
        watchArea(p.getX() + dx1, p.getY() + dy1, p.getZ() + dz1,
                  p.getX() + dx2, p.getY() + dy2, p.getZ() + dz2, name);
    }

    @LuaFunction(mainThread = true)
    public final boolean removeAreaWatch(String name) {
        boolean removed = areaWatches.remove(name) != null;
        if (removed) host.setChanged();
        return removed;
    }

    @LuaFunction(mainThread = true)
    public final void clearAreaWatches() {
        areaWatches.clear();
        host.setChanged();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> listAreaWatches() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : areaWatches.entrySet()) {
            AreaWatch w = e.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("kind", w.kind);
            info.put("min", Map.of("x", w.minX, "y", w.minY, "z", w.minZ));
            info.put("max", Map.of("x", w.maxX - 1, "y", w.maxY - 1, "z", w.maxZ - 1));
            info.put("occupants", w.occupants.size());
            out.put(e.getKey(), info);
        }
        return out;
    }

    public void saveWatches(net.minecraft.nbt.CompoundTag tag) {
        net.minecraft.nbt.CompoundTag all = new net.minecraft.nbt.CompoundTag();
        for (var e : areaWatches.entrySet()) {
            net.minecraft.nbt.CompoundTag w = new net.minecraft.nbt.CompoundTag();
            AreaWatch a = e.getValue();
            w.putInt("minX", a.minX); w.putInt("minY", a.minY); w.putInt("minZ", a.minZ);
            w.putInt("maxX", a.maxX); w.putInt("maxY", a.maxY); w.putInt("maxZ", a.maxZ);
            w.putString("kind", a.kind);
            all.put(e.getKey(), w);
        }
        tag.put("areaWatches", all);
    }

    public void loadWatches(net.minecraft.nbt.CompoundTag tag) {
        areaWatches.clear();
        if (!tag.contains("areaWatches", net.minecraft.nbt.Tag.TAG_COMPOUND)) return;
        net.minecraft.nbt.CompoundTag all = tag.getCompound("areaWatches");
        for (String key : all.getAllKeys()) {
            net.minecraft.nbt.CompoundTag w = all.getCompound(key);
            areaWatches.put(key, new AreaWatch(
                    w.getInt("minX"), w.getInt("minY"), w.getInt("minZ"),
                    w.getInt("maxX"), w.getInt("maxY"), w.getInt("maxZ"),
                    w.getString("kind")));
        }
    }

    private int clamp(int r) throws LuaException {
        int max = ModConfig.ENTITY_TRACKER_MAX_RADIUS.get();
        if (r < 1) throw new LuaException("radius must be >= 1");
        if (r > max) throw new LuaException("radius exceeds configured max " + max);
        return r;
    }

    private Map<Integer, Map<String, Object>> listAround(int radius, EntityFilter filter) throws LuaException {
        if (!(host.getLevel() instanceof ServerLevel sl)) throw new LuaException("no server level");
        BlockPos p = host.getBlockPos();
        AABB box = new AABB(p).inflate(radius);
        List<Entity> entities = sl.getEntities((Entity) null, box, filter::accept);
        Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
        int i = 1;
        for (Entity e : entities) {
            out.put(i++, describe(e));
        }
        return out;
    }

    private Map<String, Object> describe(Entity e) {
        Map<String, Object> row = new HashMap<>();
        var id = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        row.put("type", id == null ? "minecraft:unknown" : id.toString());
        row.put("registryName", id == null ? "minecraft:unknown" : id.toString());
        row.put("x", e.getX());
        row.put("y", e.getY());
        row.put("z", e.getZ());
        Map<String, Object> vel = new HashMap<>();
        vel.put("x", e.getDeltaMovement().x);
        vel.put("y", e.getDeltaMovement().y);
        vel.put("z", e.getDeltaMovement().z);
        row.put("velocity", vel);
        row.put("hostile", isHostile(e));
        row.put("isPlayer", e instanceof Player);
        row.put("displayName", e.getDisplayName().getString());
        if (e instanceof LivingEntity le) {
            row.put("health", le.getHealth());
            row.put("maxHealth", le.getMaxHealth());
        }
        boolean expose = ModConfig.ENTITY_TRACKER_EXPOSE_PLAYER_UUIDS.get();
        if (e instanceof Player && !expose) {
            // Opaque hash so scripts can deduplicate without getting the UUID.
            row.put("uuid", Integer.toHexString(e.getUUID().hashCode()));
            row.put("uuidMasked", true);
        } else {
            row.put("uuid", e.getUUID().toString());
            row.put("uuidMasked", false);
        }
        return row;
    }

    private boolean isHostile(Entity e) {
        if (e instanceof Enemy) return true;
        if (e instanceof Mob mob) {
            return mob.getTarget() != null;
        }
        return false;
    }

    /** Invoked by the block entity's server tick pipeline. */
    public void serverTick() {
        int cadence;
        try {
            cadence = Math.max(1, ModConfig.ENTITY_TRACKER_COOLDOWN_TICKS.get());
        } catch (Throwable t) {
            cadence = 4;
        }
        if (++tickCounter % cadence != 0) return;
        if (!(host.getLevel() instanceof ServerLevel sl)) return;
        if (!areaWatches.isEmpty()) tickAreaWatches(sl);
        if (watches.isEmpty()) return;
        MinecraftServer srv = sl.getServer();

        List<UUID> expired = new ArrayList<>();
        for (var entry : watches.entrySet()) {
            UUID uuid = entry.getKey();
            WatchState state = entry.getValue();
            Entity target = findEntity(srv, uuid);
            if (target == null) {
                fire("gone", uuid);
                expired.add(uuid);
                continue;
            }
            double dist = target.distanceToSqr(host.getBlockPos().getCenter());
            int radius = ModConfig.ENTITY_TRACKER_MAX_RADIUS.get();
            if (dist > (double) radius * radius) {
                fire("out_of_range", uuid);
                expired.add(uuid);
                continue;
            }
            if (state.lastX == null) {
                state.lastX = target.getX();
                state.lastY = target.getY();
                state.lastZ = target.getZ();
                continue;
            }
            double dx = target.getX() - state.lastX;
            double dy = target.getY() - state.lastY;
            double dz = target.getZ() - state.lastZ;
            if (dx*dx + dy*dy + dz*dz > 4.0) {
                fire("moved", uuid);
                state.lastX = target.getX();
                state.lastY = target.getY();
                state.lastZ = target.getZ();
            }
            if (target instanceof LivingEntity le && !le.isAlive()) {
                fire("died", uuid);
                expired.add(uuid);
            }
            if (target instanceof Mob mob && mob.getTarget() != null && !state.wasCombat) {
                fire("combat", uuid);
                state.wasCombat = true;
            } else if (target instanceof Mob mob && mob.getTarget() == null) {
                state.wasCombat = false;
            }
        }
        expired.forEach(watches::remove);
    }

    private @Nullable Entity findEntity(MinecraftServer srv, UUID uuid) {
        for (ServerLevel lvl : srv.getAllLevels()) {
            Entity e = lvl.getEntity(uuid);
            if (e != null) return e;
        }
        // Players live in the player list regardless of dimension.
        ServerPlayer sp = srv.getPlayerList().getPlayer(uuid);
        return sp;
    }

    private void fire(String reason, UUID uuid) {
        Object[] args = { uuid.toString(), reason };
        for (IComputerAccess c : attached) {
            c.queueEvent("entity_tracker_update", args);
        }
        BiConsumer<String, Object[]> relay = upstreamRelay;
        if (relay != null) {
            try { relay.accept("entity_tracker_update", args); } catch (Exception ignored) {}
        }
    }

    /** Per-tick: build the current occupant set for each area, diff against
     *  the previous tick's occupants, and fire enter/leave proximity events. */
    private void tickAreaWatches(ServerLevel sl) {
        for (var e : areaWatches.entrySet()) {
            String name = e.getKey();
            AreaWatch a = e.getValue();
            AABB box = new AABB(a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ);
            List<Entity> inside = sl.getEntities((Entity) null, box, Entity::isAlive);
            java.util.Set<UUID> current = new java.util.HashSet<>();
            for (Entity ent : inside) current.add(ent.getUUID());

            // Enter events: new UUIDs.
            for (Entity ent : inside) {
                if (!a.occupants.contains(ent.getUUID())) {
                    fireProximity("entity_proximity_enter", name, ent);
                }
            }
            // Leave events: UUIDs that were in the set but aren't now.
            for (UUID prev : a.occupants) {
                if (!current.contains(prev)) {
                    Entity gone = sl.getEntity(prev);
                    fireProximityLeave(name, prev, gone);
                }
            }
            a.occupants = current;
        }
    }

    private void fireProximity(String event, String watchName, Entity e) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        String type = id == null ? "minecraft:unknown" : id.toString();
        Object[] args = { watchName, e.getUUID().toString(), type, e.getX(), e.getY(), e.getZ() };
        for (IComputerAccess c : attached) {
            c.queueEvent(event, args);
        }
        BiConsumer<String, Object[]> relay = upstreamRelay;
        if (relay != null) {
            try { relay.accept(event, args); } catch (Exception ignored) {}
        }
    }

    private void fireProximityLeave(String watchName, UUID uuid, @Nullable Entity gone) {
        String type = "minecraft:unknown";
        if (gone != null) {
            var id = ForgeRegistries.ENTITY_TYPES.getKey(gone.getType());
            if (id != null) type = id.toString();
        }
        Object[] args = { watchName, uuid.toString(), type };
        for (IComputerAccess c : attached) {
            c.queueEvent("entity_proximity_leave", args);
        }
        BiConsumer<String, Object[]> relay = upstreamRelay;
        if (relay != null) {
            try { relay.accept("entity_proximity_leave", args); } catch (Exception ignored) {}
        }
    }

    private static final class WatchState {
        Double lastX, lastY, lastZ;
        boolean wasCombat;
    }

    /** Persistent proximity watch. {@code max*} bounds are EXCLUSIVE (AABB-style). */
    static final class AreaWatch {
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final String kind;
        java.util.Set<UUID> occupants = new java.util.HashSet<>();

        AreaWatch(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String kind) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
            this.kind = kind;
        }
    }

    private enum EntityFilter {
        ALL {
            @Override boolean accept(Entity e) { return e.isAlive(); }
        },
        HOSTILE {
            @Override boolean accept(Entity e) {
                return e.isAlive() && (e instanceof Enemy || (e instanceof Mob m && m.getTarget() != null));
            }
        },
        PLAYERS {
            @Override boolean accept(Entity e) { return e.isAlive() && e instanceof Player; }
        };
        abstract boolean accept(Entity e);
    }
}
