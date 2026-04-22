package net.deceasedcraft.deceasedcc.turrets;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Mutable state for a single turret mount. Serialised to NBT on the host BE;
 * read by both the server-tick targeting code and the CC peripheral methods.
 */
public class TurretState {
    public boolean enabled = true;
    public boolean friendlyFire = false;
    public String priority = "nearest"; // nearest | mostDangerous | lowestHealth | firstInRange
    public float minSectorDeg = 0f;
    public float maxSectorDeg = 360f;
    public float yawDeg = 0f;
    public float pitchDeg = 0f;
    public UUID forcedTargetUuid;
    public UUID currentTargetUuid;
    public ItemStack weapon = ItemStack.EMPTY;
    public ItemStack[] ammoSlots = new ItemStack[] { ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY };
    public final Deque<KillEntry> killLog = new ArrayDeque<>();
    // Player names protected from this turret's fire, even when friendlyFire=true.
    public final java.util.Set<String> friendlyPlayers = new java.util.LinkedHashSet<>();
    // Target-type filter: "hostiles" (default), "allLiving", or "whitelist".
    public String filterMode = "hostiles";
    // Entity type registry IDs used when filterMode = "whitelist".
    public final java.util.Set<String> targetTypes = new java.util.LinkedHashSet<>();
    // Manual mode: when true, auto-targeting is disabled; scripts drive aim+fire.
    public boolean manualMode = false;
    // Auto-hunt: when CC is on and manualMode is false, the turret idles by
    // default — Lua has to call setAutoHunt(true) for the turret to scan the
    // world for targets on its own. This makes CC turrets "rest until told"
    // unless the script explicitly opts in.
    public boolean autoHunt = false;
    // Minimum ticks between shots for both auto and manual fire(). 2 ticks
    // at default = 10 shots/sec (twice the old 5-tick default).
    public int fireRateTicks = 2;
    // Upgrade slots. Length 2 on basic turrets, 4 on advanced; sized by the
    // host block entity before first use.
    public net.minecraft.world.item.ItemStack[] upgradeSlots = new net.minecraft.world.item.ItemStack[0];
    // Set true when idle + a player is within the sentry radius. Drives the
    // server-side swivel animation in the turret peripheral / BE tick.
    public boolean sentryActive = false;
    public float sentryStartYaw = 0f;
    public long lastFireGameTime = 0L;
    // Aim mode for auto-targeting: "body" (default) or "head". Advanced turret
    // scripts can flip this to shoot at head height without entering full
    // manual mode.
    public String aimMode = "body";
    // Computer control gate. When false, the advanced turret ignores its CC
    // state and auto-targets like a basic turret. Toggled via a button in
    // the in-world GUI; Lua setters throw when this is off.
    public boolean ccControl = true;
    // Max yaw/pitch change per tick (deg). NaN means "use server config max".
    // CC-mode scripts can set this via setTurnSpeed; basic mode always uses
    // the server config max.
    public float turnSpeedDegPerTick = Float.NaN;
    // Filter ordinal for the in-GUI cycle button when ccControl=false
    // (matches BasicTurretFilter): 0=HOSTILES_ONLY, 1=PLAYERS_TOO, 2=ALL_LIVING.
    public int basicFilterOrdinal = 0;
    // UUID of the invisible in-world shooter entity used to route TACZ
    // firing through a real ticking LivingEntity. Re-spawned by the owning
    // BE on each server tick if missing.
    public UUID shooterUuid;
    // Phase D: UUID of the player currently remote-controlling this turret
    // via the Turret Remote. Non-null → auto-target is suspended, aim is
    // driven by the player's packets, and fire requires explicit input.
    // Runtime-only; cleared on server restart.
    public UUID controllingPlayer;
    // UUID of the player who placed the turret. NBT-persistent.
    // Always exempt from auto-target AND friendly-fire checks so the
    // placer can stand right next to their own turret without being
    // flagged as "in the shot line" and blocking every shot. Cleared /
    // changed only by CC API (setPlacer / clearPlacer on advanced turret).
    public UUID placerUuid;
    // LOD scan-cadence state. NOT serialized — only used by basic-mode
    // (runBasicMode) scan; CC mode is script-driven and not throttled.
    public TurretActivity activity = TurretActivity.IDLE;
    public int consecutiveEmptyScans = 0;
    public long lastScanTick = 0L;

    public void save(CompoundTag tag) {
        tag.putBoolean("enabled", enabled);
        tag.putBoolean("ff", friendlyFire);
        tag.putString("priority", priority);
        tag.putFloat("sectorMin", minSectorDeg);
        tag.putFloat("sectorMax", maxSectorDeg);
        tag.putFloat("yaw", yawDeg);
        tag.putFloat("pitch", pitchDeg);
        if (forcedTargetUuid != null) tag.putUUID("forced", forcedTargetUuid);
        if (!weapon.isEmpty()) tag.put("weapon", weapon.save(new CompoundTag()));
        net.minecraft.nbt.ListTag ammo = new net.minecraft.nbt.ListTag();
        for (ItemStack s : ammoSlots) ammo.add(s.save(new CompoundTag()));
        tag.put("ammo", ammo);
        net.minecraft.nbt.ListTag friends = new net.minecraft.nbt.ListTag();
        for (String name : friendlyPlayers) friends.add(net.minecraft.nbt.StringTag.valueOf(name));
        tag.put("friendlies", friends);
        tag.putString("filterMode", filterMode);
        net.minecraft.nbt.ListTag types = new net.minecraft.nbt.ListTag();
        for (String t : targetTypes) types.add(net.minecraft.nbt.StringTag.valueOf(t));
        tag.put("targetTypes", types);
        tag.putBoolean("ccControl", ccControl);
        tag.putFloat("turnSpeed", Float.isNaN(turnSpeedDegPerTick) ? -1f : turnSpeedDegPerTick);
        tag.putInt("basicFilter", basicFilterOrdinal);
        if (shooterUuid != null) tag.putUUID("shooter", shooterUuid);
        if (placerUuid != null) tag.putUUID("placer", placerUuid);
        net.minecraft.nbt.ListTag ups = new net.minecraft.nbt.ListTag();
        for (ItemStack u : upgradeSlots) ups.add(u.save(new CompoundTag()));
        tag.put("upgrades", ups);
        tag.putBoolean("sentry", sentryActive);
        tag.putFloat("sentryStart", sentryStartYaw);
    }

    public void load(CompoundTag tag) {
        enabled = tag.getBoolean("enabled");
        friendlyFire = tag.getBoolean("ff");
        priority = tag.getString("priority");
        if (priority.isEmpty()) priority = "nearest";
        minSectorDeg = tag.getFloat("sectorMin");
        maxSectorDeg = tag.contains("sectorMax") ? tag.getFloat("sectorMax") : 360f;
        yawDeg = tag.getFloat("yaw");
        pitchDeg = tag.getFloat("pitch");
        forcedTargetUuid = tag.hasUUID("forced") ? tag.getUUID("forced") : null;
        weapon = tag.contains("weapon") ? ItemStack.of(tag.getCompound("weapon")) : ItemStack.EMPTY;
        net.minecraft.nbt.ListTag ammo = tag.getList("ammo", 10);
        for (int i = 0; i < Math.min(ammo.size(), ammoSlots.length); i++) {
            ammoSlots[i] = ItemStack.of(ammo.getCompound(i));
        }
        friendlyPlayers.clear();
        net.minecraft.nbt.ListTag friends = tag.getList("friendlies", 8);
        for (int i = 0; i < friends.size(); i++) friendlyPlayers.add(friends.getString(i));
        filterMode = tag.contains("filterMode") ? tag.getString("filterMode") : "hostiles";
        targetTypes.clear();
        net.minecraft.nbt.ListTag types = tag.getList("targetTypes", 8);
        for (int i = 0; i < types.size(); i++) targetTypes.add(types.getString(i));
        ccControl = !tag.contains("ccControl") || tag.getBoolean("ccControl");
        float ts = tag.contains("turnSpeed") ? tag.getFloat("turnSpeed") : -1f;
        turnSpeedDegPerTick = ts < 0 ? Float.NaN : ts;
        basicFilterOrdinal = tag.contains("basicFilter") ? tag.getInt("basicFilter") : 0;
        shooterUuid = tag.hasUUID("shooter") ? tag.getUUID("shooter") : null;
        placerUuid = tag.hasUUID("placer") ? tag.getUUID("placer") : null;
        net.minecraft.nbt.ListTag ups = tag.getList("upgrades", 10);
        for (int i = 0; i < Math.min(ups.size(), upgradeSlots.length); i++) {
            upgradeSlots[i] = ItemStack.of(ups.getCompound(i));
        }
        sentryActive = tag.getBoolean("sentry");
        sentryStartYaw = tag.getFloat("sentryStart");
    }

    public void recordKill(String entityType) {
        killLog.addFirst(new KillEntry(entityType, System.currentTimeMillis()));
        while (killLog.size() > 20) killLog.removeLast();
    }

    public record KillEntry(String entityType, long timestamp) {}
}
