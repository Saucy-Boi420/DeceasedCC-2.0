package net.deceasedcraft.deceasedcc.turrets;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.deceasedcraft.deceasedcc.core.ModConfig;
import net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5 — Turret Mount peripheral. Targeting loop runs off
 * {@link #serverTick()}, clamped to {@code turret.tickRate}. Actual firing is
 * delegated to {@link TaczBridge}, which uses reflection; if TACZ is absent,
 * firing becomes a no-op but the rest of the API still behaves correctly so
 * CC scripts can be tested without TACZ installed.
 */
public class TurretMountPeripheral implements IPeripheral {
    private final TurretMountBlockEntity host;
    // Fire-rate cache. nativeFireRateTicks does internal map lookups; calling
    // it every tick during sustained fire is wasteful when the gun rarely
    // changes. Keyed on weapon ItemStack identity — TACZ damages in place
    // (same identity), so the cache survives normal firing. A swap or
    // pickup produces a new ItemStack reference → cache invalidates.
    private int cachedFireRateTicks = -1;
    private int cachedWeaponIdentity = 0;

    public TurretMountPeripheral(TurretMountBlockEntity host) {
        this.host = host;
    }

    @Override public String getType() { return "turret_mount"; }
    @Override public boolean equals(@Nullable IPeripheral other) { return other instanceof TurretMountPeripheral p && p.host == host; }

    /** Thrown by every setter when the in-world GUI has disabled CC control.
     *  Readers (getStatus, getKillLog, etc.) still work regardless. */
    private void requireCC() throws LuaException {
        if (!host.state.ccControl) {
            throw new LuaException("Computer control is disabled on this turret (toggle in the GUI).");
        }
    }

    /** True when the GUI toggle is set to ON. Scripts can check this to know
     *  whether their subsequent writes will throw. */
    @LuaFunction(mainThread = true)
    public final boolean isComputerControlled() { return host.state.ccControl; }

    @LuaFunction(mainThread = true)
    public final void setEnabled(boolean enabled) throws LuaException { requireCC(); host.state.enabled = enabled; host.setChanged(); }

    @LuaFunction(mainThread = true)
    public final void setTargetPriority(String type) throws LuaException {
        requireCC();
        switch (type) {
            case "nearest", "mostDangerous", "lowestHealth", "firstInRange" -> host.state.priority = type;
            default -> throw new LuaException("unknown priority: " + type);
        }
        host.setChanged();
    }

    @LuaFunction(mainThread = true)
    public final void setFriendlyFire(boolean ff) throws LuaException { requireCC(); host.state.friendlyFire = ff; host.setChanged(); }

    @LuaFunction(mainThread = true)
    public final void setSector(double minAngle, double maxAngle) throws LuaException {
        requireCC();
        if (minAngle < 0 || maxAngle < 0 || minAngle > 360 || maxAngle > 360) throw new LuaException("angles must be in [0, 360]");
        host.state.minSectorDeg = (float) minAngle;
        host.state.maxSectorDeg = (float) maxAngle;
        host.setChanged();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", host.state.enabled);
        out.put("weaponLoaded", !host.state.weapon.isEmpty());
        out.put("weapon", host.state.weapon.isEmpty() ? null : host.state.weapon.getDescriptionId());

        // Weapon durability. ItemStack.isDamageableItem() is true when the
        // item has max damage > 0; TACZ guns use this, so we surface both raw
        // counts and a 0..1 percentage.
        if (!host.state.weapon.isEmpty() && host.state.weapon.isDamageableItem()) {
            int dmg = host.state.weapon.getDamageValue();
            int max = host.state.weapon.getMaxDamage();
            Map<String, Object> dura = new LinkedHashMap<>();
            dura.put("damage", dmg);
            dura.put("maxDamage", max);
            dura.put("remaining", max - dmg);
            dura.put("percent", max == 0 ? 1.0 : (double) (max - dmg) / (double) max);
            out.put("durability", dura);
        } else {
            out.put("durability", null);
        }

        int ammoCount = 0;
        for (var a : host.state.ammoSlots) ammoCount += a.getCount();
        out.put("ammo", ammoCount);

        Map<Integer, Map<String, Object>> slots = new LinkedHashMap<>();
        for (int i = 0; i < host.state.ammoSlots.length; i++) {
            var s = host.state.ammoSlots[i];
            Map<String, Object> slot = new LinkedHashMap<>();
            if (s.isEmpty()) {
                slot.put("empty", true);
            } else {
                slot.put("empty", false);
                slot.put("item", net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem()).toString());
                slot.put("count", s.getCount());
                slot.put("maxStack", s.getMaxStackSize());
            }
            slots.put(i + 1, slot);
        }
        out.put("ammoSlots", slots);

        out.put("currentTarget", host.state.currentTargetUuid == null ? null : host.state.currentTargetUuid.toString());
        out.put("yaw", host.state.yawDeg);
        out.put("pitch", host.state.pitchDeg);
        return out;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getDurability() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (host.state.weapon.isEmpty()) {
            out.put("loaded", false);
            return out;
        }
        out.put("loaded", true);
        out.put("item", host.state.weapon.getDescriptionId());
        if (host.state.weapon.isDamageableItem()) {
            int dmg = host.state.weapon.getDamageValue();
            int max = host.state.weapon.getMaxDamage();
            out.put("damage", dmg);
            out.put("maxDamage", max);
            out.put("remaining", max - dmg);
            out.put("percent", max == 0 ? 1.0 : (double) (max - dmg) / (double) max);
        } else {
            out.put("damage", 0);
            out.put("maxDamage", 0);
            out.put("remaining", 0);
            out.put("percent", 1.0);
        }
        return out;
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getAmmoDetails() {
        Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
        for (int i = 0; i < host.state.ammoSlots.length; i++) {
            var s = host.state.ammoSlots[i];
            Map<String, Object> slot = new LinkedHashMap<>();
            if (s.isEmpty()) {
                slot.put("empty", true);
                slot.put("count", 0);
            } else {
                slot.put("empty", false);
                slot.put("item", net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem()).toString());
                slot.put("count", s.getCount());
                slot.put("maxStack", s.getMaxStackSize());
            }
            out.put(i + 1, slot);
        }
        return out;
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getKillLog() {
        Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
        int i = 1;
        for (TurretState.KillEntry entry : host.state.killLog) {
            Map<String, Object> row = new HashMap<>();
            row.put("entity", entry.entityType());
            row.put("timestamp", entry.timestamp());
            out.put(i++, row);
        }
        return out;
    }

    @LuaFunction(mainThread = true)
    public final void forceTarget(String uuid) throws LuaException {
        requireCC();
        try {
            host.state.forcedTargetUuid = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new LuaException("invalid UUID");
        }
    }

    @LuaFunction(mainThread = true)
    public final void clearTarget() throws LuaException {
        requireCC();
        host.state.forcedTargetUuid = null;
        host.state.currentTargetUuid = null;
    }

    @LuaFunction(mainThread = true)
    public final void addFriendly(String playerName) throws LuaException {
        requireCC();
        if (playerName == null || playerName.isBlank()) throw new LuaException("playerName required");
        host.state.friendlyPlayers.add(playerName);
        host.setChanged();
    }

    @LuaFunction(mainThread = true)
    public final boolean removeFriendly(String playerName) throws LuaException {
        requireCC();
        boolean removed = host.state.friendlyPlayers.remove(playerName);
        if (removed) host.setChanged();
        return removed;
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, String> getFriendlies() {
        Map<Integer, String> out = new LinkedHashMap<>();
        int i = 1;
        for (String n : host.state.friendlyPlayers) out.put(i++, n);
        return out;
    }

    // --- Target filter (Lua counterpart to the basic turret's cycle button) ---

    @LuaFunction(mainThread = true)
    public final void setTargetFilter(String mode) throws LuaException {
        requireCC();
        switch (mode) {
            case "hostiles", "allLiving", "whitelist" -> host.state.filterMode = mode;
            default -> throw new LuaException("filter mode must be 'hostiles', 'allLiving', or 'whitelist'");
        }
        host.setChanged();
    }

    @LuaFunction(mainThread = true)
    public final String getTargetFilter() { return host.state.filterMode; }

    @LuaFunction(mainThread = true)
    public final void addTargetType(String entityId) throws LuaException {
        requireCC();
        if (entityId == null || entityId.isBlank()) throw new LuaException("entityId required");
        host.state.targetTypes.add(entityId);
        host.setChanged();
    }

    @LuaFunction(mainThread = true)
    public final boolean removeTargetType(String entityId) throws LuaException {
        requireCC();
        boolean removed = host.state.targetTypes.remove(entityId);
        if (removed) host.setChanged();
        return removed;
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, String> getTargetTypes() {
        Map<Integer, String> out = new LinkedHashMap<>();
        int i = 1;
        for (String t : host.state.targetTypes) out.put(i++, t);
        return out;
    }

    // --- Direct aim + fire control (scripts can fully drive the turret) ---

    /** Directly set yaw and pitch. Respects TACZ hinge limits on pitch.
     *  Yaw/pitch are client-synced via the rotation packet — no setChanged
     *  here so we don't mark the chunk dirty for every aim packet. */
    @LuaFunction(mainThread = true)
    public final void setAim(double yaw, double pitch) throws LuaException {
        requireCC();
        float y = (float) yaw;
        y = ((y % 360f) + 360f) % 360f;
        float p = (float) pitch;
        p = Math.max(-90f, Math.min(50f, p));
        host.state.yawDeg = y;
        host.state.pitchDeg = p;
    }

    /** Aim at an absolute world point. Returns the yaw/pitch the turret adopted. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> aimAtPoint(double x, double y, double z) throws LuaException {
        requireCC();
        // Aim source matches the shooter position set in TaczBridge.placeShooter
        // (block centre + 0.7 Y). If these drift, bullets trace a different
        // line from what we computed and targets get over-/undershot.
        // Muzzle height must match TaczBridge.placeShooter's actual bullet-spawn Y
// (blockPos + 1.05). Using the old 0.55 here mis-aims shots by ~0.5 blocks
// vertically — fine for an adult zombie (1.8 tall) but causes bullets to
// fly over baby zombies (0.93 eye height) at any horizontal distance.
var from = host.getBlockPos().getCenter().add(0, net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.MUZZLE_Y_OFFSET, 0);
        double dx = x - from.x;
        double dy = y - from.y;
        double dz = z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (yaw < 0) yaw += 360f;
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        pitch = Math.max(-90f, Math.min(50f, pitch));
        host.state.yawDeg = yaw;
        host.state.pitchDeg = pitch;
        // No setChanged — yaw/pitch ride the rotation packet; chunk-save
        // marking would burn disk I/O on every script aim call.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("yaw", yaw);
        out.put("pitch", pitch);
        return out;
    }

    /** Pull the trigger once. Honours the fireRate cooldown. Returns true if
     *  the shot fired (TACZ installed + cooldown expired). */
    @LuaFunction(mainThread = true)
    public final boolean fire() throws LuaException {
        requireCC();
        var level = host.getLevel();
        if (level == null) return false;
        long now = level.getGameTime();
        if (now - host.state.lastFireGameTime < host.state.fireRateTicks) return false;
        var shooter = host.getOrSpawnShooter(level);
        if (shooter == null) return false;
        java.util.Set<java.util.UUID> exempt = new java.util.HashSet<>();
        if (host.state.placerUuid != null) exempt.add(host.state.placerUuid);
        if (host.state.controllingPlayer != null) exempt.add(host.state.controllingPlayer);
        boolean fired = net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.fireAndEject(
                level, host.getBlockPos(), shooter, host.state.weapon, host.state.ammoSlots,
                host.state.yawDeg, host.state.pitchDeg, exempt);
        if (fired) {
            host.state.lastFireGameTime = now;
            net.deceasedcraft.deceasedcc.core.TurretMetrics.recordShot();
            // setChanged is load-bearing: ammo decrements happen in-place
            // inside TaczBridge.fire. Without this, ammo state never persists.
            host.setChanged();
        }
        return fired;
    }

    /** Minimum ticks between shots, for both auto and manual modes. Clamped to [1, 200]. */
    @LuaFunction(mainThread = true)
    public final void setFireRate(int ticks) throws LuaException {
        requireCC();
        if (ticks < 1 || ticks > 200) throw new LuaException("ticks must be in [1, 200]");
        host.state.fireRateTicks = ticks;
        host.setChanged();
    }

    @LuaFunction(mainThread = true)
    public final int getFireRate() { return host.state.fireRateTicks; }

    /** When true, auto-targeting is disabled. The script must call setAim /
     *  aimAtPoint and fire() to do anything. Useful for headshot control or
     *  leading moving targets. */
    @LuaFunction(mainThread = true)
    public final void setManualMode(boolean manual) throws LuaException { requireCC(); host.state.manualMode = manual; host.setChanged(); }

    @LuaFunction(mainThread = true)
    public final boolean isManualMode() { return host.state.manualMode; }

    /** Opt-in auto-scan in CC mode. By default a CC-controlled turret rests
     *  until Lua tells it to do something (setAim, fire, forceTarget). Set
     *  autoHunt to true to have the turret scan for targets on its own,
     *  respecting the CC filter / friendlyFire / priority settings. */
    @LuaFunction(mainThread = true)
    public final void setAutoHunt(boolean hunt) throws LuaException {
        requireCC();
        host.state.autoHunt = hunt;
        host.setChanged();
    }

    @LuaFunction(mainThread = true)
    public final boolean isAutoHunting() { return host.state.autoHunt; }

    /** "body" (default) or "head". Only applies in auto mode. In manual mode
     *  the script aims via setAim / aimAtPoint and this is ignored. */
    @LuaFunction(mainThread = true)
    public final void setAimMode(String mode) throws LuaException {
        requireCC();
        switch (mode) {
            case "body", "head" -> host.state.aimMode = mode;
            default -> throw new LuaException("aim mode must be 'body' or 'head'");
        }
        host.setChanged();
    }

    @LuaFunction(mainThread = true)
    public final String getAimMode() { return host.state.aimMode; }

    /** Sets the max turn speed in degrees per tick. Clamped at the server's
     *  config max; scripts cannot make the turret turn faster than the
     *  server allows. Pass 0 or negative to reset to the server max. */
    @LuaFunction(mainThread = true)
    public final void setTurnSpeed(double degPerTick) throws LuaException {
        requireCC();
        double serverMax = ModConfig.TURRET_MAX_TURN_SPEED.get();
        if (degPerTick <= 0) {
            host.state.turnSpeedDegPerTick = Float.NaN; // reset to config max
        } else {
            host.state.turnSpeedDegPerTick = (float) Math.min(degPerTick, serverMax);
        }
        host.setChanged();
    }

    /** Returns the effective turn speed (degrees per tick) this turret is
     *  currently using. */
    @LuaFunction(mainThread = true)
    public final double getTurnSpeed() {
        if (Float.isNaN(host.state.turnSpeedDegPerTick)) return ModConfig.TURRET_MAX_TURN_SPEED.get();
        return host.state.turnSpeedDegPerTick;
    }

    @LuaFunction(mainThread = true)
    public final double getMaxTurnSpeed() { return ModConfig.TURRET_MAX_TURN_SPEED.get(); }

    // --- server tick ---

    public void serverTick() {
        if (!(host.getLevel() instanceof ServerLevel sl)) return;

        // When the gun is broken or empty, freeze the turret entirely so
        // the BER's flat-lay render doesn't track anything. No aim updates,
        // no sentry sweep, no target resolution.
        if (!host.state.weapon.isEmpty()
                && TaczBridge.isGunInoperable(host.state.weapon, host.state.ammoSlots)) {
            host.state.currentTargetUuid = null;
            host.state.sentryActive = false;
            if (host.state.controllingPlayer != null) {
                forceExitController(sl, "Gun inoperable");
            }
            return;
        }

        // Phase D: wireless player control. Skip all auto-targeting and
        // sentry logic — aim is driven by the player's Aim packets, and
        // fire happens via Fire packets (already cooldown-gated). Drain
        // FE from the remote, force-exit on empty / out-of-range / dim.
        if (host.state.controllingPlayer != null) {
            tickControlled(sl);
            return;
        }

        // Enabled toggle OVERRIDES everything — including CC-driven fire.
        // Short-circuits before the CC branch so a disabled turret stays
        // silent regardless of whether CC or basic mode is active.
        if (!host.state.enabled) {
            host.state.currentTargetUuid = null;
            host.state.sentryActive = false;
            // Swivel toward idle pose: yaw = 0 (north), pitch = +20 (down)
            host.state.yawDeg = TurretAim.approachYaw(host.state.yawDeg, 0f, 2f);
            host.state.pitchDeg = TurretAim.approachPitch(host.state.pitchDeg, 20f, 2f);
            return;
        }

        if (!host.state.ccControl) {
            runBasicMode(sl);
            tickFiring(sl);
            return;
        }

        // CC MANUAL mode — turret holds whatever yaw/pitch Lua set, fires
        // only when Lua calls fire(). No target selection. tickFiring still
        // runs so an explicit forceTarget + fire() works.
        if (host.state.manualMode) { tickFiring(sl); return; }

        // CC AUTO mode — priority order:
        //   1. Lua-set forced target (turret.forceTarget(uuid))
        //   2. Auto-scan ONLY if the script has opted in via setAutoHunt(true).
        //      Respects CC filter / whitelist / friendlyFire / priority.
        //   3. Idle pose otherwise ("rest until told").
        Entity target = null;
        if (host.state.forcedTargetUuid != null) {
            Entity forced = sl.getEntity(host.state.forcedTargetUuid);
            if (forced != null && forced.isAlive()) {
                target = forced;
            } else {
                host.state.forcedTargetUuid = null;
            }
        }
        if (target == null && host.state.autoHunt) {
            target = resolveTarget(sl);
        }
        if (target != null) {
            host.state.sentryActive = false;
            host.state.currentTargetUuid = target.getUUID();
            aimAt(target);
        } else {
            host.state.currentTargetUuid = null;
            host.state.sentryActive = false;
            host.state.yawDeg = TurretAim.approachYaw(host.state.yawDeg, 0f, 2f);
            host.state.pitchDeg = TurretAim.approachPitch(host.state.pitchDeg, 20f, 2f);
        }
        tickFiring(sl);
    }

    /** Phase D: while a player is remote-controlling this turret, validate
     *  the session every tick and drain FE from the remote. Force-exits on
     *  offline player / out-of-range / dimension change / empty battery. */
    private void tickControlled(ServerLevel sl) {
        java.util.UUID uuid = host.state.controllingPlayer;
        host.state.sentryActive = false;
        host.state.currentTargetUuid = null;
        net.minecraft.server.level.ServerPlayer player = sl.getServer().getPlayerList().getPlayer(uuid);
        if (player == null) {
            // Player went offline — silently drop control. The logout
            // handler normally does this too; covered here as a safety net.
            host.state.controllingPlayer = null;
            net.deceasedcraft.deceasedcc.turrets.ControlledTurretRegistry.unregister(uuid);
            return;
        }
        if (ModConfig.REMOTE_DIMENSION_CHANGE_KICKS.get()
                && !player.level().dimension().equals(sl.dimension())) {
            forceExitController(sl, "Dimension changed");
            return;
        }
        net.minecraft.world.item.ItemStack remote = net.minecraft.world.item.ItemStack.EMPTY;
        for (net.minecraft.world.InteractionHand h : net.minecraft.world.InteractionHand.values()) {
            net.minecraft.world.item.ItemStack s = player.getItemInHand(h);
            if (s.getItem() instanceof net.deceasedcraft.deceasedcc.items.TurretRemoteItem) {
                remote = s; break;
            }
        }
        if (remote.isEmpty()) {
            forceExitController(sl, "Remote not in hand");
            return;
        }
        float rangeMult = 1.0f + 0.25f
                * net.deceasedcraft.deceasedcc.items.TurretRemoteItem.getRangeUpgradeCount(remote);
        double maxRange = ModConfig.REMOTE_BASE_RANGE_BLOCKS.get() * rangeMult;
        if (player.distanceToSqr(host.getBlockPos().getCenter()) > maxRange * maxRange) {
            forceExitController(sl, "Out of range");
            return;
        }
        int drain = net.deceasedcraft.deceasedcc.items.TurretRemoteItem.getEffectiveDrainPerTick(remote);
        int drained = net.deceasedcraft.deceasedcc.items.TurretRemoteItem.drainInternal(remote, drain);
        if (drained < drain) {
            forceExitController(sl, "Battery dead");
        }
    }

    private void forceExitController(ServerLevel sl, String reason) {
        java.util.UUID uuid = host.state.controllingPlayer;
        host.state.controllingPlayer = null;
        if (uuid == null) return;
        net.deceasedcraft.deceasedcc.turrets.ControlledTurretRegistry.unregister(uuid);
        net.minecraft.server.level.ServerPlayer player = sl.getServer().getPlayerList().getPlayer(uuid);
        if (player != null) {
            net.deceasedcraft.deceasedcc.network.DeceasedNetwork.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new net.deceasedcraft.deceasedcc.network.TurretControlPackets.ForceExit(reason));
        }
    }

    /** Per-tick fire attempt. Tracks the target every tick so fast-moving
     *  mobs don't slip off the 4-tick retarget cadence, leads the shot
     *  based on velocity + bullet speed, and uses a ray-vs-hitbox check
     *  instead of strict angular tolerance. */
    private void tickFiring(ServerLevel sl) {
        if (host.state.currentTargetUuid == null) return;
        if (host.state.weapon.isEmpty()) return;
        if (TaczBridge.isGunInoperable(host.state.weapon, host.state.ammoSlots)) return;
        Entity target = sl.getEntity(host.state.currentTargetUuid);
        if (target == null || !target.isAlive()) {
            host.state.currentTargetUuid = null;
            // Drop ENGAGING immediately so the debug command + cadence reflect
            // reality without waiting for the next scan.
            if (host.state.activity == TurretActivity.ENGAGING) {
                host.state.activity = TurretActivity.ALERT;
            }
            return;
        }
        // Continuous tracking so aim doesn't lag the mob between retargets.
        aimAtLeading(target);
        // Re-check range: target may have walked out since last scan.
        int rangeNow = effectiveRange(host.state.ccControl);
        if (target.distanceToSqr(host.getBlockPos().getCenter()) > (double) rangeNow * rangeNow) {
            host.state.currentTargetUuid = null;
            if (host.state.activity == TurretActivity.ENGAGING) {
                host.state.activity = TurretActivity.ALERT;
            }
            return;
        }
        if (!rayHitsTarget(target)) return;
        if (!hasClearShot(sl, target)) return;
        // Safety: if the gun's actual aim (which leads the target) is pointed
        // at a wall closer than the target, pause fire.
        if (!aimRayUnblocked(sl, target)) return;
        // Safety: if a friendly (placer or named-friendly) is standing in the
        // line of fire, pause so the turret doesn't shoot its owner.
        if (friendlyInLineOfFire(sl, target)) return;
        long now = sl.getGameTime();
        int effectiveFireRateTicks = effectiveFireRateTicks();
        if (now - host.state.lastFireGameTime < effectiveFireRateTicks) return;
        host.state.lastFireGameTime = now;
        TaczBridge.tryFire(host, target);
        net.deceasedcraft.deceasedcc.core.TurretMetrics.recordShot();
        // setChanged here is load-bearing: TaczBridge.fire shrinks the ammo
        // slot in place. Without this, ammo decrements live in memory only
        // and any save+reload restores the last-marked-dirty state.
        host.setChanged();
    }

    private void aimAtLeading(Entity target) {
        // Muzzle height must match TaczBridge.placeShooter's actual bullet-spawn Y
// (blockPos + 1.05). Using the old 0.55 here mis-aims shots by ~0.5 blocks
// vertically — fine for an adult zombie (1.8 tall) but causes bullets to
// fly over baby zombies (0.93 eye height) at any horizontal distance.
var from = host.getBlockPos().getCenter().add(0, net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.MUZZLE_Y_OFFSET, 0);
        double aimY = "head".equals(host.state.aimMode)
                ? target.getEyeY()
                : target.getY() + target.getBbHeight() * 0.5;
        net.minecraft.world.phys.Vec3 now = new net.minecraft.world.phys.Vec3(target.getX(), aimY, target.getZ());
        float bulletSpeed = net.deceasedcraft.deceasedcc.integration.tacz.GunClassifier.bulletSpeed(host.state.weapon);
        double distance = from.distanceTo(now);
        float leadSeconds = (float) Math.min(1.5, distance / Math.max(1f, bulletSpeed));
        var v = target.getDeltaMovement();
        var lead = now.add(v.scale(leadSeconds * 20f));

        double dx = lead.x - from.x;
        double dy = lead.y - from.y;
        double dz = lead.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (desiredYaw < 0) desiredYaw += 360f;
        float desiredPitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        desiredPitch = Math.max(-90f, Math.min(50f, desiredPitch));

        float maxStep = effectiveTurnSpeed();
        host.state.yawDeg = TurretAim.approachYaw(host.state.yawDeg, desiredYaw, maxStep);
        host.state.pitchDeg = TurretAim.approachPitch(host.state.pitchDeg, desiredPitch, maxStep);
    }

    private boolean rayHitsTarget(Entity target) {
        // Muzzle height must match TaczBridge.placeShooter's actual bullet-spawn Y
// (blockPos + 1.05). Using the old 0.55 here mis-aims shots by ~0.5 blocks
// vertically — fine for an adult zombie (1.8 tall) but causes bullets to
// fly over baby zombies (0.93 eye height) at any horizontal distance.
var from = host.getBlockPos().getCenter().add(0, net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.MUZZLE_Y_OFFSET, 0);
        double yRad = Math.toRadians(host.state.yawDeg);
        double pRad = Math.toRadians(host.state.pitchDeg);
        double fx = -Math.sin(yRad) * Math.cos(pRad);
        double fy = -Math.sin(pRad);
        double fz =  Math.cos(yRad) * Math.cos(pRad);
        double r = 128.0;
        var to = from.add(fx * r, fy * r, fz * r);
        return target.getBoundingBox().inflate(0.25).clip(from, to).isPresent();
    }

    /** Compute the fire-rate cooldown after upgrade boosts AND the
     *  advanced-turret cooldown config. Used by auto-fire (runBasicMode +
     *  forced-target tickFiring), NOT by Lua peripheral.fire() which honours
     *  state.fireRateTicks instead. Cached per-weapon-identity. */
    private int effectiveFireRateTicks() {
        float mult = TurretUpgrade.effectiveMultipliers(host.state.upgradeSlots)
                .get(TurretUpgrade.Stat.FIRE_RATE);
        int base = currentNativeFireRateTicks();
        float cooldownMult = ModConfig.ADVANCED_TURRET_FIRE_COOLDOWN_MULT.get().floatValue();
        return Math.max(1, (int) Math.floor(base * cooldownMult / mult));
    }

    private int currentNativeFireRateTicks() {
        int identity = host.state.weapon.isEmpty()
                ? 0 : System.identityHashCode(host.state.weapon);
        if (identity != cachedWeaponIdentity || cachedFireRateTicks < 0) {
            cachedFireRateTicks = host.state.weapon.isEmpty()
                    ? 2 // matches TurretState.fireRateTicks default
                    : TaczBridge.nativeFireRateTicks(host.state.weapon);
            cachedWeaponIdentity = identity;
        }
        return cachedFireRateTicks;
    }

    private int effectiveRange(boolean ccMode) {
        float mult = TurretUpgrade.effectiveMultipliers(host.state.upgradeSlots)
                .get(TurretUpgrade.Stat.RANGE);
        int base = ModConfig.TURRET_ADVANCED_RANGE.get();
        int hardCap = ModConfig.TURRET_MAX_RANGE_HARD_CAP.get();
        return Math.max(4, Math.min(hardCap, (int) (base * mult)));
    }

    private float effectiveTurnMult() {
        return TurretUpgrade.effectiveMultipliers(host.state.upgradeSlots)
                .get(TurretUpgrade.Stat.TURN_SPEED);
    }

    private void updateSentry(ServerLevel sl) {
        int radius = ModConfig.ADVANCED_TURRET_SENTRY_RADIUS.get();
        var pos = host.getBlockPos();
        boolean anyPlayer = sl.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                radius, false) != null;
        if (anyPlayer) {
            if (!host.state.sentryActive) {
                host.state.sentryActive = true;
                host.state.sentryStartYaw = host.state.yawDeg;
            }
            long t = sl.getGameTime();
            float phase = (float) Math.sin((t % 60L) / 60.0 * (Math.PI * 2.0));
            host.state.yawDeg = ((host.state.sentryStartYaw + phase * 20f) % 360f + 360f) % 360f;
            host.state.pitchDeg = TurretAim.approachPitch(host.state.pitchDeg, 0f, 2f);
        } else {
            host.state.sentryActive = false;
            host.state.yawDeg = TurretAim.approachYaw(host.state.yawDeg, 0f, 2f);
            host.state.pitchDeg = TurretAim.approachPitch(host.state.pitchDeg, 0f, 2f);
        }
    }

    private boolean hasClearShot(ServerLevel sl, Entity target) {
        net.deceasedcraft.deceasedcc.core.TurretMetrics.recordRaycast();
        // Muzzle height must match TaczBridge.placeShooter's actual bullet-spawn Y
// (blockPos + 1.05). Using the old 0.55 here mis-aims shots by ~0.5 blocks
// vertically — fine for an adult zombie (1.8 tall) but causes bullets to
// fly over baby zombies (0.93 eye height) at any horizontal distance.
var from = host.getBlockPos().getCenter().add(0, net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.MUZZLE_Y_OFFSET, 0);
        var to = target.getBoundingBox().getCenter();
        var ctx = new net.minecraft.world.level.ClipContext(
                from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                null);
        var hit = sl.clip(ctx);
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** Raycast along the turret's current aim direction (which may lead the
     *  target) and make sure no block is closer than the target. Prevents
     *  firing into a wall when the lead-point is occluded. */
    private boolean aimRayUnblocked(ServerLevel sl, Entity target) {
        net.deceasedcraft.deceasedcc.core.TurretMetrics.recordRaycast();
        // Muzzle height must match TaczBridge.placeShooter's actual bullet-spawn Y
// (blockPos + 1.05). Using the old 0.55 here mis-aims shots by ~0.5 blocks
// vertically — fine for an adult zombie (1.8 tall) but causes bullets to
// fly over baby zombies (0.93 eye height) at any horizontal distance.
var from = host.getBlockPos().getCenter().add(0, net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.MUZZLE_Y_OFFSET, 0);
        double yRad = Math.toRadians(host.state.yawDeg);
        double pRad = Math.toRadians(host.state.pitchDeg);
        double fx = -Math.sin(yRad) * Math.cos(pRad);
        double fy = -Math.sin(pRad);
        double fz =  Math.cos(yRad) * Math.cos(pRad);
        double dist = from.distanceTo(target.getBoundingBox().getCenter()) + 0.5;
        var to = from.add(fx * dist, fy * dist, fz * dist);
        var ctx = new net.minecraft.world.level.ClipContext(
                from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                null);
        var hit = sl.clip(ctx);
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** True if ANY player is standing in the bullet's path. The ray extends
     *  64 blocks PAST the target — TACZ bullets penetrate zombies, so a
     *  friendly behind a hostile is genuinely in the line of fire. The
     *  target itself is excluded so a player IS shootable in friendlyFire-on
     *  mode without their own body blocking the shot. Disabled entirely
     *  if the global pauseIfFriendlyInLOS config is false. */
    private boolean friendlyInLineOfFire(ServerLevel sl, Entity target) {
        if (!ModConfig.TURRET_PAUSE_IF_FRIENDLY_IN_LOS.get()) return false;
        net.deceasedcraft.deceasedcc.core.TurretMetrics.recordRaycast();
        // Muzzle height must match TaczBridge.placeShooter's actual bullet-spawn Y
// (blockPos + 1.05). Using the old 0.55 here mis-aims shots by ~0.5 blocks
// vertically — fine for an adult zombie (1.8 tall) but causes bullets to
// fly over baby zombies (0.93 eye height) at any horizontal distance.
var from = host.getBlockPos().getCenter().add(0, net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.MUZZLE_Y_OFFSET, 0);
        var tgt = target.getBoundingBox().getCenter();
        var dir = tgt.subtract(from);
        double dist = dir.length();
        if (dist <= 0.01) return false;
        var to = from.add(dir.normalize().scale(dist + 64.0));
        var box = new AABB(from, to).inflate(0.5);
        var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                sl, null, from, to, box,
                e -> e instanceof Player && e != target);
        return hit != null;
    }

    /** Degrees of tolerance for the "on target" check. Roughly the gun's
     *  spread cone half-angle — closer than this, the turret's considered
     *  lined up and may fire. Looser than this, it's still tracking. */
    private static final float ON_TARGET_TOL_DEG = 3f;

    private boolean isOnTarget(Entity target, float tolDeg) {
        // Muzzle height must match TaczBridge.placeShooter's actual bullet-spawn Y
// (blockPos + 1.05). Using the old 0.55 here mis-aims shots by ~0.5 blocks
// vertically — fine for an adult zombie (1.8 tall) but causes bullets to
// fly over baby zombies (0.93 eye height) at any horizontal distance.
var from = host.getBlockPos().getCenter().add(0, net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.MUZZLE_Y_OFFSET, 0);
        double aimY = "head".equals(host.state.aimMode)
                ? target.getEyeY()
                : target.getY() + target.getBbHeight() * 0.5;
        double dx = target.getX() - from.x;
        double dy = aimY - from.y;
        double dz = target.getZ() - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (desiredYaw < 0) desiredYaw += 360f;
        float desiredPitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        desiredPitch = Math.max(-90f, Math.min(50f, desiredPitch));
        float yawDiff = Math.abs(((desiredYaw - host.state.yawDeg + 540f) % 360f) - 180f);
        float pitchDiff = Math.abs(desiredPitch - host.state.pitchDeg);
        return yawDiff <= tolDeg && pitchDiff <= tolDeg;
    }

    /** Basic-mode targeting used when the GUI toggle disables CC control.
     *  Always-on, hostiles-only, body aim, default 5-tick fire cadence,
     *  ignores every CC-driven field in state. */
    private void runBasicMode(ServerLevel sl) {
        BlockPos pos = host.getBlockPos();

        // LOD cadence + stagger hash. ENGAGING scans every tickRate; ALERT
        // and IDLE scan progressively less often. Stagger spreads the load
        // across ticks so 100 turrets don't bunch up on tick%4==0.
        int tickRate = ModConfig.TURRET_TICK_RATE.get();
        int alertMult = ModConfig.TURRET_ALERT_SCAN_MULT.get();
        int idleMult = ModConfig.TURRET_IDLE_SCAN_MULT.get();
        int cadence = switch (host.state.activity) {
            case ENGAGING -> tickRate;
            case ALERT    -> tickRate * alertMult;
            case IDLE     -> tickRate * idleMult;
        };
        long now = sl.getGameTime();
        long offset = Math.floorMod(pos.hashCode(), cadence);
        if (((now + offset) % cadence) != 0) return;
        host.state.lastScanTick = now;
        net.deceasedcraft.deceasedcc.core.TurretMetrics.recordScan();

        int range = effectiveRange(false);
        if (host.state.weapon.isEmpty()) { host.state.currentTargetUuid = null; updateSentry(sl); return; }

        AABB box = new AABB(pos).inflate(range);
        // AABB.inflate yields a cube whose corners are ~range*sqrt(3) away;
        // add an explicit squared-distance gate so the turret actually respects
        // a spherical range instead of tracking entities 1.7× farther out.
        double rangeSq = (double) range * range;
        var centre = pos.getCenter();
        // Two-stage scan: collect every alive LivingEntity once, then filter.
        // The unfiltered list drives ALERT detection; the filtered subset
        // picks the actual target.
        java.util.List<LivingEntity> allInBox = sl.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity le : allInBox) {
            if (!basicValid(le) || !hasClearShot(sl, le)) continue;
            double d = le.distanceToSqr(centre);
            if (d > rangeSq) continue;
            if (d < bestDist) { bestDist = d; best = le; }
        }

        // Activity transition (per scan).
        if (best != null) {
            host.state.activity = TurretActivity.ENGAGING;
            host.state.consecutiveEmptyScans = 0;
        } else if (!allInBox.isEmpty()) {
            host.state.activity = TurretActivity.ALERT;
            host.state.consecutiveEmptyScans = 0;
        } else {
            host.state.consecutiveEmptyScans++;
            if (host.state.activity == TurretActivity.ENGAGING) {
                host.state.activity = TurretActivity.ALERT;
            }
            int threshold = ModConfig.TURRET_IDLE_THRESHOLD_SCANS.get();
            if (host.state.consecutiveEmptyScans >= threshold) {
                host.state.activity = TurretActivity.IDLE;
            }
        }

        if (best == null) { host.state.currentTargetUuid = null; updateSentry(sl); return; }

        boolean wasNull = host.state.currentTargetUuid == null;
        host.state.sentryActive = false;
        host.state.currentTargetUuid = best.getUUID();
        if (wasNull) net.deceasedcraft.deceasedcc.core.TurretMetrics.recordTargetAcquired();
        aimAtBody(best);
        // Actual firing is handled by tickFiring() every tick, so fast-RPM
        // guns aren't bottlenecked to the 4-tick target cadence here.
    }

    private boolean basicValid(LivingEntity e) {
        if (!e.isAlive()) return false;
        // Never target other turrets' shooter entities (Mob/LivingEntity → caught
        // by ALL_LIVING). Two turrets in range of each other would otherwise
        // dump rounds back and forth forever.
        if (e instanceof TurretShooterEntity) return false;
        // Minimum engagement distance — scales with the loaded gun type so
        // snipers don't knife-fight and pistols aren't scared of a 1-block
        // zombie. Prevents the turret from shooting its own block.
        if (e.distanceToSqr(host.getBlockPos().getCenter()) < minTargetDistanceSq()) return false;
        // Never target the turret's placer, regardless of mode — this is the
        // path that actually runs when the GUI toggle disables CC control
        // (the other isValidTarget method is dead code). Named friendlies
        // are also exempt for parity with the CC path.
        if (e instanceof Player p) {
            if (host.state.placerUuid != null
                    && host.state.placerUuid.equals(p.getUUID())) return false;
            if (host.state.friendlyPlayers.contains(p.getGameProfile().getName())) return false;
        }
        BasicTurretFilter mode = BasicTurretFilter.fromOrdinal(host.state.basicFilterOrdinal);
        return switch (mode) {
            case HOSTILES_ONLY -> !(e instanceof Player) && (e instanceof Enemy || (e instanceof Mob m && m.getTarget() != null));
            case PLAYERS_TOO   -> e instanceof Enemy || e instanceof Player;
            case ALL_LIVING    -> !(e instanceof Player);
        };
    }

    private void aimAtBody(Entity target) {
        // Muzzle height must match TaczBridge.placeShooter's actual bullet-spawn Y
// (blockPos + 1.05). Using the old 0.55 here mis-aims shots by ~0.5 blocks
// vertically — fine for an adult zombie (1.8 tall) but causes bullets to
// fly over baby zombies (0.93 eye height) at any horizontal distance.
var from = host.getBlockPos().getCenter().add(0, net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.MUZZLE_Y_OFFSET, 0);
        double aimY = target.getY() + target.getBbHeight() * 0.5;
        double dx = target.getX() - from.x;
        double dy = aimY - from.y;
        double dz = target.getZ() - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (desiredYaw < 0) desiredYaw += 360f;
        float desiredPitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        desiredPitch = Math.max(-90f, Math.min(50f, desiredPitch));

        // Basic-mode aiming (runBasicMode) uses the full server-max turn
        // speed — no CC clamp applies.
        float maxStep = ModConfig.TURRET_MAX_TURN_SPEED.get().floatValue();
        host.state.yawDeg = TurretAim.approachYaw(host.state.yawDeg, desiredYaw, maxStep);
        host.state.pitchDeg = TurretAim.approachPitch(host.state.pitchDeg, desiredPitch, maxStep);
    }

    private @Nullable Entity resolveTarget(ServerLevel sl) {
        if (host.state.forcedTargetUuid != null) {
            Entity e = sl.getEntity(host.state.forcedTargetUuid);
            if (e != null && e.isAlive()) return e;
            host.state.forcedTargetUuid = null;
        }
        BlockPos p = host.getBlockPos();
        int range = effectiveRange(true);
        AABB box = new AABB(p).inflate(range);
        Collection<Entity> hits = sl.getEntities((Entity) null, box, e -> isValidTarget(e) && hasClearShot(sl, e));
        return pickByPriority((List<Entity>) (List<?>) List.copyOf(hits));
    }

    private boolean isValidTarget(Entity e) {
        if (!e.isAlive()) return false;
        // Never target other turrets' shooter entities.
        if (e instanceof TurretShooterEntity) return false;
        // Gun-type minimum engagement distance (see minTargetDistanceSq).
        if (e.distanceToSqr(host.getBlockPos().getCenter()) < minTargetDistanceSq()) return false;
        if (e instanceof Player p) {
            // Never target the turret's placer, regardless of mode.
            if (host.state.placerUuid != null
                    && host.state.placerUuid.equals(p.getUUID())) return false;
            // Named friendlies are never targeted, even when friendlyFire is
            // on — explicit allow-list always wins.
            if (host.state.friendlyPlayers.contains(p.getGameProfile().getName())) return false;
            if (!host.state.friendlyFire) return false;
            return true;
        }
        // filterMode gates non-player targets.
        return switch (host.state.filterMode) {
            case "allLiving" -> e instanceof net.minecraft.world.entity.LivingEntity;
            case "whitelist" -> {
                var id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
                yield id != null && host.state.targetTypes.contains(id.toString());
            }
            default /* hostiles */ -> e instanceof Enemy || (e instanceof Mob m && m.getTarget() != null);
        };
    }

    private @Nullable Entity pickByPriority(List<Entity> candidates) {
        if (candidates.isEmpty()) return null;
        BlockPos p = host.getBlockPos();
        switch (host.state.priority) {
            case "firstInRange":
                return candidates.get(0);
            case "lowestHealth": {
                Entity best = null; float low = Float.MAX_VALUE;
                for (Entity e : candidates) {
                    if (e instanceof LivingEntity le && le.getHealth() < low) { low = le.getHealth(); best = e; }
                }
                return best == null ? candidates.get(0) : best;
            }
            case "mostDangerous": {
                Entity best = null; float most = -1f;
                for (Entity e : candidates) {
                    if (e instanceof Mob m) {
                        float score = (m.getTarget() == null ? 0 : 5) + m.getMaxHealth() * 0.1f;
                        if (score > most) { most = score; best = e; }
                    }
                }
                return best == null ? candidates.get(0) : best;
            }
            case "nearest":
            default: {
                Entity best = null; double d2 = Double.MAX_VALUE;
                for (Entity e : candidates) {
                    double dd = e.distanceToSqr(p.getCenter());
                    if (dd < d2) { d2 = dd; best = e; }
                }
                return best;
            }
        }
    }

    /** Minimum squared engagement distance, classified by the loaded weapon:
     *  pistol/SMG 0.5, rifle/shotgun 1.0, LMG 1.5, sniper 2.0. Falls back to
     *  1.0 if the weapon slot is empty or the item id doesn't match a known
     *  family. Keyed on the weapon item's registry-id path so it works for
     *  any mod that names guns with the usual English words. */
    private double minTargetDistanceSq() {
        double d = 1.0;
        if (host.state.weapon.isEmpty()) return d * d;
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(host.state.weapon.getItem());
        if (id == null) return d * d;
        String path = id.getPath().toLowerCase();
        if (pathHas(path, "sniper", "awp", "m40", "barrett", "svd", "dragunov", "scout", "bolt"))             d = 2.0;
        else if (pathHas(path, "lmg", "m249", "m60", "rpk", "minigun", "pkm", "machinegun"))                  d = 1.5;
        else if (pathHas(path, "shotgun", "spas", "m870", "m1014", "nova", "super90", "remington"))          d = 1.0;
        else if (pathHas(path, "rifle", "ak", "m4", "m16", "scar", "ar15", "aug", "famas", "g36", "fal"))    d = 1.0;
        else if (pathHas(path, "smg", "mp5", "mp7", "uzi", "vector", "p90", "tmp", "thompson", "ump"))       d = 0.5;
        else if (pathHas(path, "pistol", "revolver", "glock", "m1911", "deagle", "usp", "colt", "beretta"))  d = 0.5;
        return d * d;
    }

    private static boolean pathHas(String path, String... needles) {
        for (String n : needles) if (path.contains(n)) return true;
        return false;
    }

    private void aimAt(Entity target) {
        // Muzzle height must match TaczBridge.placeShooter's actual bullet-spawn Y
// (blockPos + 1.05). Using the old 0.55 here mis-aims shots by ~0.5 blocks
// vertically — fine for an adult zombie (1.8 tall) but causes bullets to
// fly over baby zombies (0.93 eye height) at any horizontal distance.
var from = host.getBlockPos().getCenter().add(0, net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.MUZZLE_Y_OFFSET, 0);
        double aimY;
        if ("head".equals(host.state.aimMode)) {
            aimY = target.getEyeY();
        } else {
            aimY = target.getY() + target.getBbHeight() * 0.5;
        }
        double dx = target.getX() - from.x;
        double dy = aimY - from.y;
        double dz = target.getZ() - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (desiredYaw < 0) desiredYaw += 360f;
        float desiredPitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        desiredPitch = Math.max(-90f, Math.min(50f, desiredPitch));

        // Turn-speed clamp: advanced turret honours its per-turret turnSpeed
        // (never faster than the server max). In basic mode the caller
        // passes the server max directly.
        float maxStep = effectiveTurnSpeed();
        host.state.yawDeg = TurretAim.approachYaw(host.state.yawDeg, desiredYaw, maxStep);
        host.state.pitchDeg = TurretAim.approachPitch(host.state.pitchDeg, desiredPitch, maxStep);
    }

    private float effectiveTurnSpeed() {
        float serverMax = ModConfig.TURRET_MAX_TURN_SPEED.get().floatValue();
        float mult = effectiveTurnMult();
        if (!host.state.ccControl) return serverMax * mult;
        if (Float.isNaN(host.state.turnSpeedDegPerTick)) return serverMax * mult;
        return Math.min(host.state.turnSpeedDegPerTick, serverMax) * mult;
    }

    /** Lua: list installed upgrades for this turret. */
    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getUpgrades() {
        Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
        int i = 1;
        if (host.state.upgradeSlots == null) return out;
        for (var s : host.state.upgradeSlots) {
            Map<String, Object> row = new LinkedHashMap<>();
            var up = TurretUpgrade.fromStack(s);
            if (up == null) {
                row.put("empty", true);
            } else {
                row.put("empty", false);
                row.put("stat", up.stat.name());
                row.put("tier", up.tier.name());
                row.put("boostPercent", up.percentBoost);
            }
            out.put(i++, row);
        }
        return out;
    }

    /** Lua: post-upgrade effective stats. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getEffectiveStats() {
        var mults = TurretUpgrade.effectiveMultipliers(host.state.upgradeSlots);
        int baseFr = host.state.fireRateTicks;
        float rateMult = mults.get(TurretUpgrade.Stat.FIRE_RATE);
        int effFr = Math.max(1, (int) Math.floor(baseFr / rateMult));
        int range = effectiveRange(host.state.ccControl);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fireRateTicks", effFr);
        out.put("shotsPerSecond", 20.0 / effFr);
        out.put("turnSpeedDegPerTick", effectiveTurnSpeed());
        out.put("rangeBlocks", range);
        out.put("multipliers", Map.of(
                "fireRate", mults.get(TurretUpgrade.Stat.FIRE_RATE),
                "turnSpeed", mults.get(TurretUpgrade.Stat.TURN_SPEED),
                "range", mults.get(TurretUpgrade.Stat.RANGE)));
        return out;
    }

    public void recordKillByType(Entity victim) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(victim.getType());
        host.state.recordKill(id == null ? "minecraft:unknown" : id.toString());
        host.setChanged();
    }
}
