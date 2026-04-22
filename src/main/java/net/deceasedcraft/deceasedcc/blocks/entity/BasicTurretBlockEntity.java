package net.deceasedcraft.deceasedcc.blocks.entity;

import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge;
import net.deceasedcraft.deceasedcc.turrets.BasicTurretFilter;
import net.deceasedcraft.deceasedcc.turrets.BasicTurretState;
import net.deceasedcraft.deceasedcc.turrets.TurretMountContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import java.util.List;

/**
 * Self-contained turret. No peripheral, no CC integration. Scans a small
 * radius ({@link #RANGE}) for valid targets based on its filter mode, aims
 * at the closest match, and fires via {@link TaczBridge} if TACZ is installed.
 *
 * <p>All the auto-pickup / auto-pull / sync machinery mirrors the Advanced
 * Turret Mount so the two behave consistently where they overlap.</p>
 */
public class BasicTurretBlockEntity extends BlockEntity {
    public static final int BASE_FIRE_RATE_TICKS = 2;
    private static final int TICK_RATE = 4;
    private static final int SYNC_INTERVAL_TICKS = 4;
    private static final int AMMO_PULL_INTERVAL_TICKS = 20;

    public final BasicTurretState state = new BasicTurretState();
    /** Client-only smoother for the yaw/pitch packet stream — see
     *  {@link net.deceasedcraft.deceasedcc.turrets.TurretRotationLerp}. */
    public final net.deceasedcraft.deceasedcc.turrets.TurretRotationLerp rotationLerp =
            new net.deceasedcraft.deceasedcc.turrets.TurretRotationLerp();
    private float lastSyncedYaw = Float.NaN;
    private float lastSyncedPitch = Float.NaN;
    private boolean lastSyncedSentry = false;
    private long lastSyncTick;
    private long lastAmmoPullTick;
    private net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity cachedShooter;
    private int lastRegisteredRange = -1;
    // Render-state sync tracking. forceSync triggers a full BE update to
    // every tracking client. We compare these between ticks and re-broadcast
    // whenever something visible changes (weapon swap, ammo depleted,
    // durability hits 0, freshly placed).
    private boolean firstTickPending = true;
    private boolean lastSyncedInoperable = false;
    private int lastSyncedWeaponItemHash = 0;
    // Fire-rate cache. nativeFireRateTicks does internal map lookups; calling
    // it every tick is wasteful when the gun rarely changes. Keyed on the
    // weapon's identity hash — TACZ damages the stack in place (same identity)
    // so the cache survives normal firing. A fresh assignment (gun swap,
    // pickup, slot insert) produces a new ItemStack reference → invalidates.
    private int cachedFireRateTicks = -1;
    private int cachedWeaponIdentity = 0;

    public BasicTurretBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.BASIC_TURRET.get(), pos, blockState);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) { super.saveAdditional(tag); state.save(tag); }

    @Override
    public void load(CompoundTag tag) { super.load(tag); state.load(tag); }

    @Override
    public CompoundTag getUpdateTag() { CompoundTag t = new CompoundTag(); state.save(t); return t; }

    @Override
    public void handleUpdateTag(CompoundTag tag) { state.load(tag); }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Effective range after upgrade multipliers, the basic-turret range
     *  config knob, and the global hard cap. Used by both the targeting loop
     *  and the global TurretRegistry for daisy-chain activation overlap. */
    public int computeEffectiveRange() {
        var mults = net.deceasedcraft.deceasedcc.turrets.TurretUpgrade.effectiveMultipliers(state.upgradeSlots);
        float rangeMult = mults.get(net.deceasedcraft.deceasedcc.turrets.TurretUpgrade.Stat.RANGE);
        float basicMult = net.deceasedcraft.deceasedcc.core.ModConfig.BASIC_TURRET_RANGE_MULT.get().floatValue();
        int base = net.deceasedcraft.deceasedcc.core.ModConfig.BASIC_TURRET_RANGE.get();
        int hardCap = net.deceasedcraft.deceasedcc.core.ModConfig.TURRET_MAX_RANGE_HARD_CAP.get();
        return Math.max(4, Math.min(hardCap, (int) (base * rangeMult * basicMult)));
    }

    /** Cached native fire-rate lookup. Recomputes only when the weapon stack
     *  identity changes (gun added / removed / swapped). Survives in-place
     *  NBT mutations like TACZ damage application. */
    private int currentFireRateTicks() {
        int identity = state.weapon.isEmpty() ? 0 : System.identityHashCode(state.weapon);
        if (identity != cachedWeaponIdentity || cachedFireRateTicks < 0) {
            cachedFireRateTicks = state.weapon.isEmpty()
                    ? BASE_FIRE_RATE_TICKS
                    : TaczBridge.nativeFireRateTicks(state.weapon);
            cachedWeaponIdentity = identity;
        }
        return cachedFireRateTicks;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel sl) {
            int eff = computeEffectiveRange();
            net.deceasedcraft.deceasedcc.core.TurretRegistry.register(sl, getBlockPos(), eff, false);
            lastRegisteredRange = eff;
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel sl) {
            net.deceasedcraft.deceasedcc.core.TurretRegistry.unregister(sl, getBlockPos());
        }
        net.deceasedcraft.deceasedcc.core.TurretTickGuard.clear(getBlockPos());
        super.setRemoved();
    }

    public void cycleFilter() {
        state.mode = state.mode.next();
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState bs = level.getBlockState(getBlockPos());
            level.sendBlockUpdated(getBlockPos(), bs, bs, 3);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState blockState, BlockEntity be) {
        if (!(be instanceof BasicTurretBlockEntity bt)) return;
        // Wrap the entire body in a TickGuard so any exception thrown by
        // upgrade math, TACZ reflection, or some other downstream call
        // isolates to this one turret instead of crashing the server tick.
        net.deceasedcraft.deceasedcc.core.TurretTickGuard.runTick(level, pos, () -> {
            // Proximity-activation gate.
            if (level instanceof ServerLevel sl) {
                int effRange = bt.computeEffectiveRange();
                if (effRange != bt.lastRegisteredRange) {
                    net.deceasedcraft.deceasedcc.core.TurretRegistry.refreshRange(sl, pos, effRange);
                    bt.lastRegisteredRange = effRange;
                }
                if (!net.deceasedcraft.deceasedcc.core.TurretRegistry.isActive(sl, pos)) return;
            }
            bt.syncRenderStateIfChanged(level, pos);
            bt.tickPickupAndAmmo(level, pos);
            bt.ensureShooterAndPosition(level, pos);
            if (!bt.state.enabled) {
                bt.state.currentTargetUuid = null;
                bt.state.sentryActive = false;
                bt.syncIfNeeded(level, pos, blockState);
                return;
            }
            bt.tickTargeting(level, pos);
            bt.tickFiring(level, pos);
            bt.syncIfNeeded(level, pos, blockState);
        });
    }

    public net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity getOrSpawnShooter(Level level) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return null;
        net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity existing = null;
        if (state.shooterUuid != null) {
            net.minecraft.world.entity.Entity e = sl.getEntity(state.shooterUuid);
            if (e instanceof net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity ts && ts.isAlive()) {
                existing = ts;
            }
        }
        if (existing == null) {
            existing = new net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity(
                    net.deceasedcraft.deceasedcc.core.ModEntities.TURRET_SHOOTER.get(), sl);
            var centre = getBlockPos().getCenter();
            existing.setPos(centre.x, centre.y + 1.2, centre.z);
            sl.addFreshEntity(existing);
            state.shooterUuid = existing.getUUID();
            setChanged();
        }
        // Always re-set on each call (cheap, transient) so the rewriter can
        // walk shooter→pos→BE without relying on worldscan/uuid lookups.
        existing.ownerTurretPos = getBlockPos();
        cachedShooter = existing;
        return existing;
    }

    private void ensureShooterAndPosition(Level level, BlockPos pos) {
        var shooter = getOrSpawnShooter(level);
        if (shooter == null) return;
        net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.placeShooter(
                shooter, pos, state.yawDeg, state.pitchDeg);
    }

    public net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity cachedShooter() { return cachedShooter; }

    private void tickPickupAndAmmo(Level level, BlockPos pos) {
        if (state.weapon.isEmpty()) tryPickupGun(level, pos);
        if (!state.weapon.isEmpty() && level.getGameTime() - lastAmmoPullTick >= AMMO_PULL_INTERVAL_TICKS) {
            tryPullAmmo(level, pos);
            lastAmmoPullTick = level.getGameTime();
        }
    }

    private void tryPickupGun(Level level, BlockPos pos) {
        // 3×3×3 grab zone centred on the turret — dropped guns in any of the
        // 6 neighbour blocks (or resting on top / below) get hoovered in.
        AABB box = AABB.ofSize(pos.getCenter(), 3.0, 3.0, 3.0);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && !e.hasPickUpDelay());
        for (ItemEntity e : items) {
            ItemStack stack = e.getItem();
            if (stack.isEmpty() || !TurretMountContainer.isGunItem(stack)) continue;
            state.weapon = stack.copyWithCount(1);
            if (stack.getCount() > 1) {
                stack.shrink(1);
                e.setItem(stack);
            } else {
                e.discard();
            }
            setChanged();
            forceSync(level, pos);
            return;
        }
    }

    private void tryPullAmmo(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockEntity neighbour = level.getBlockEntity(pos.relative(dir));
            if (neighbour == null) continue;
            LazyOptional<IItemHandler> cap = neighbour.getCapability(ForgeCapabilities.ITEM_HANDLER, dir.getOpposite());
            IItemHandler handler = cap.orElse(null);
            if (handler == null) continue;
            if (pullOneStackInto(handler)) {
                setChanged();
                return;
            }
        }
    }

    private boolean pullOneStackInto(IItemHandler handler) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack src = handler.getStackInSlot(slot);
            if (src.isEmpty() || !TurretMountContainer.isAmmoItem(src)) continue;
            for (int i = 0; i < state.ammoSlots.length; i++) {
                ItemStack dst = state.ammoSlots[i];
                if (dst.isEmpty()) {
                    ItemStack extracted = handler.extractItem(slot, src.getMaxStackSize(), false);
                    if (!extracted.isEmpty()) { state.ammoSlots[i] = extracted; return true; }
                } else if (ItemStack.isSameItemSameTags(dst, src) && dst.getCount() < dst.getMaxStackSize()) {
                    int room = dst.getMaxStackSize() - dst.getCount();
                    ItemStack extracted = handler.extractItem(slot, Math.min(room, src.getCount()), false);
                    if (!extracted.isEmpty()) { dst.grow(extracted.getCount()); return true; }
                }
            }
        }
        return false;
    }

    private void tickTargeting(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl)) { state.currentTargetUuid = null; return; }

        // LOD cadence + stagger hash: dormant turrets scan less often, and
        // hashing pos prevents 100 turrets from all scanning on tick%4==0.
        int tickRate = net.deceasedcraft.deceasedcc.core.ModConfig.TURRET_TICK_RATE.get();
        int alertMult = net.deceasedcraft.deceasedcc.core.ModConfig.TURRET_ALERT_SCAN_MULT.get();
        int idleMult = net.deceasedcraft.deceasedcc.core.ModConfig.TURRET_IDLE_SCAN_MULT.get();
        int cadence = switch (state.activity) {
            case ENGAGING -> tickRate;
            case ALERT    -> tickRate * alertMult;
            case IDLE     -> tickRate * idleMult;
        };
        long now = sl.getGameTime();
        long offset = Math.floorMod(pos.hashCode(), cadence);
        if (((now + offset) % cadence) != 0) return;
        state.lastScanTick = now;
        net.deceasedcraft.deceasedcc.core.TurretMetrics.recordScan();

        var mults = net.deceasedcraft.deceasedcc.turrets.TurretUpgrade.effectiveMultipliers(state.upgradeSlots);
        float turnMult   = mults.get(net.deceasedcraft.deceasedcc.turrets.TurretUpgrade.Stat.TURN_SPEED);
        int effectiveRange = computeEffectiveRange();

        if (state.weapon.isEmpty()) {
            // No gun at all → idle swivel so the turret still feels alive.
            state.currentTargetUuid = null;
            updateSentry(sl, pos);
            return;
        }
        if (TaczBridge.isGunInoperable(state.weapon, state.ammoSlots)) {
            // Broken or empty → freeze completely. The gun lays flat on the
            // slab via the BER; no targeting, no swivel, no aim updates.
            state.currentTargetUuid = null;
            state.sentryActive = false;
            return;
        }

        AABB box = new AABB(pos).inflate(effectiveRange);
        // AABB.inflate gives us a cube whose corners sit ~range*sqrt(3) away,
        // so an explicit squared-distance gate is required to actually enforce
        // a spherical range. Without this the turret happily tracks mobs 30+
        // blocks away for a "20-block" range.
        double rangeSq = (double) effectiveRange * effectiveRange;
        var centre = pos.getCenter();
        // Two-stage scan: first pass collects every living entity in the box
        // (drives ALERT detection cheaply), second pass filters down to valid
        // shootable targets. One AABB query, two views.
        List<LivingEntity> allInBox = sl.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive);
        List<LivingEntity> candidates = new java.util.ArrayList<>();
        for (LivingEntity e : allInBox) {
            if (isValid(e) && e.distanceToSqr(centre) <= rangeSq && hasClearShot(level, pos, e)) {
                candidates.add(e);
            }
        }
        LivingEntity target = pickNearest(candidates, pos);

        // Activity transition (per scan).
        if (target != null) {
            state.activity = net.deceasedcraft.deceasedcc.turrets.TurretActivity.ENGAGING;
            state.consecutiveEmptyScans = 0;
        } else if (!allInBox.isEmpty()) {
            state.activity = net.deceasedcraft.deceasedcc.turrets.TurretActivity.ALERT;
            state.consecutiveEmptyScans = 0;
        } else {
            state.consecutiveEmptyScans++;
            if (state.activity == net.deceasedcraft.deceasedcc.turrets.TurretActivity.ENGAGING) {
                state.activity = net.deceasedcraft.deceasedcc.turrets.TurretActivity.ALERT;
            }
            int threshold = net.deceasedcraft.deceasedcc.core.ModConfig.TURRET_IDLE_THRESHOLD_SCANS.get();
            if (state.consecutiveEmptyScans >= threshold) {
                state.activity = net.deceasedcraft.deceasedcc.turrets.TurretActivity.IDLE;
            }
        }

        if (target == null) { state.currentTargetUuid = null; updateSentry(sl, pos); return; }

        // Acquired a real target — leave sentry mode. Count null→non-null
        // transitions for the targets-acquired metric.
        boolean wasNull = state.currentTargetUuid == null;
        state.sentryActive = false;
        state.currentTargetUuid = target.getUUID();
        if (wasNull) net.deceasedcraft.deceasedcc.core.TurretMetrics.recordTargetAcquired();
        aimAt(target, pos, turnMult);
    }

    /** Runs every tick: if we have a live target, we're on-aim, cooldown is
     *  up, and the gun isn't broken/empty → fire one shot. Decoupling this
     *  from the 4-tick retarget loop lets fast-RPM guns fire at their native
     *  rate instead of being capped at 5 shots/sec. */
    private void tickFiring(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl)) return;
        if (state.currentTargetUuid == null) return;
        if (cachedShooter == null || !cachedShooter.isAlive()) return;
        if (state.weapon.isEmpty()) return;
        if (TaczBridge.isGunInoperable(state.weapon, state.ammoSlots)) return;

        Entity target = sl.getEntity(state.currentTargetUuid);
        if (!(target instanceof LivingEntity le) || !le.isAlive()) {
            state.currentTargetUuid = null;
            // Drop ENGAGING immediately so the debug command + cadence reflect
            // reality without waiting for the next 4-tick scan.
            if (state.activity == net.deceasedcraft.deceasedcc.turrets.TurretActivity.ENGAGING) {
                state.activity = net.deceasedcraft.deceasedcc.turrets.TurretActivity.ALERT;
            }
            return;
        }

        // Re-aim every tick so the gun actually tracks a moving target;
        // the 4-tick retarget loop only decides *who* we're shooting, not
        // *where*. Lead the shot based on the target's velocity and the
        // gun's bullet speed.
        var mults = net.deceasedcraft.deceasedcc.turrets.TurretUpgrade.effectiveMultipliers(state.upgradeSlots);
        float turnMult = mults.get(net.deceasedcraft.deceasedcc.turrets.TurretUpgrade.Stat.TURN_SPEED);
        aimAtLeading(le, pos, turnMult);

        // Re-check range: the target may have walked out of range since the
        // last retarget scan (which only runs every 4 ticks).
        int effRangeNow = computeEffectiveRange();
        if (le.distanceToSqr(pos.getCenter()) > (double) effRangeNow * effRangeNow) {
            state.currentTargetUuid = null;
            if (state.activity == net.deceasedcraft.deceasedcc.turrets.TurretActivity.ENGAGING) {
                state.activity = net.deceasedcraft.deceasedcc.turrets.TurretActivity.ALERT;
            }
            return;
        }
        // Ray-vs-hitbox firing gate — catches the "aim is close enough to
        // land" case where strict angle tolerance rejected good shots.
        if (!rayHitsTarget(pos, le)) return;
        if (!hasClearShot(level, pos, le)) return;
        // Safety: if the gun's actual aim direction (which leads the target)
        // is pointed at a wall closer than the target, the bullet would clip
        // that wall. Abort.
        if (!aimRayUnblocked(level, pos, le)) return;
        // Safety: if the placer (or any other friendly-aligned player) is
        // standing between the muzzle and the target, pause fire.
        if (friendlyInLineOfFire(sl, pos, le)) return;

        float rateMult = mults.get(net.deceasedcraft.deceasedcc.turrets.TurretUpgrade.Stat.FIRE_RATE);
        int base = currentFireRateTicks();
        float cooldownMult = net.deceasedcraft.deceasedcc.core.ModConfig.BASIC_TURRET_FIRE_COOLDOWN_MULT.get().floatValue();
        int effectiveFireRateTicks = Math.max(1, (int) Math.floor(base * cooldownMult / rateMult));

        long now = sl.getGameTime();
        if (now - state.lastFireGameTime < effectiveFireRateTicks) return;
        java.util.Set<java.util.UUID> exempt = state.placerUuid != null
                ? java.util.Set.of(state.placerUuid)
                : java.util.Collections.emptySet();
        boolean fired = TaczBridge.fireAndEject(level, pos, cachedShooter, state.weapon,
                state.ammoSlots, state.yawDeg, state.pitchDeg, exempt);
        if (fired) {
            state.lastFireGameTime = now;
            net.deceasedcraft.deceasedcc.core.TurretMetrics.recordShot();
            // setChanged here is load-bearing: TaczBridge.fire shrinks the ammo
            // slot in place. Without this, ammo decrements live in memory only
            // and any save+reload restores the last-marked-dirty (full) state.
            setChanged();
        }
    }

    /** Aim at where the mob WILL be when a bullet arrives, not where it
     *  is now. Uses the gun's bullet speed and the target's current
     *  velocity to project forward. */
    private void aimAtLeading(Entity target, BlockPos pos, float turnMult) {
        var from = pos.getCenter().add(0, 0.55, 0);
        double aimY = target.getY() + target.getBbHeight() * 0.5;
        net.minecraft.world.phys.Vec3 now = new net.minecraft.world.phys.Vec3(target.getX(), aimY, target.getZ());
        float bulletSpeed = net.deceasedcraft.deceasedcc.integration.tacz.GunClassifier.bulletSpeed(state.weapon);
        double distance = from.distanceTo(now);
        float leadSeconds = (float) Math.min(1.5, distance / Math.max(1f, bulletSpeed));
        net.minecraft.world.phys.Vec3 v = target.getDeltaMovement();
        net.minecraft.world.phys.Vec3 lead = now.add(v.scale(leadSeconds * 20f));

        double dx = lead.x - from.x;
        double dy = lead.y - from.y;
        double dz = lead.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (desiredYaw < 0) desiredYaw += 360f;
        float desiredPitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        desiredPitch = Math.max(-90f, Math.min(50f, desiredPitch));

        float base = net.deceasedcraft.deceasedcc.core.ModConfig.TURRET_MAX_TURN_SPEED.get().floatValue() * 0.5f;
        float maxStep = base * turnMult;
        state.yawDeg = net.deceasedcraft.deceasedcc.turrets.TurretAim.approachYaw(state.yawDeg, desiredYaw, maxStep);
        state.pitchDeg = net.deceasedcraft.deceasedcc.turrets.TurretAim.approachPitch(state.pitchDeg, desiredPitch, maxStep);
    }

    /** Cast a ray from the muzzle along the turret's current aim vector
     *  and see if it lands in the target's (lightly inflated) bounding box.
     *  Replaces the 3° angle-tolerance gate, which was too strict when the
     *  turret was mid-turn but already close to the target. */
    private boolean rayHitsTarget(BlockPos pos, Entity target) {
        var from = pos.getCenter().add(0, 0.55, 0);
        double yRad = Math.toRadians(state.yawDeg);
        double pRad = Math.toRadians(state.pitchDeg);
        double fx = -Math.sin(yRad) * Math.cos(pRad);
        double fy = -Math.sin(pRad);
        double fz =  Math.cos(yRad) * Math.cos(pRad);
        double r = 128.0;
        var to = from.add(fx * r, fy * r, fz * r);
        return target.getBoundingBox().inflate(0.25).clip(from, to).isPresent();
    }

    /** Idle behaviour: swivel side-to-side when a player is within the
     *  configured sentry radius; otherwise slowly return to due north. */
    private void updateSentry(ServerLevel sl, BlockPos pos) {
        int radius = net.deceasedcraft.deceasedcc.core.ModConfig.BASIC_TURRET_SENTRY_RADIUS.get();
        boolean anyPlayer = sl.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                radius, false) != null;
        if (anyPlayer) {
            if (!state.sentryActive) {
                state.sentryActive = true;
                state.sentryStartYaw = state.yawDeg;
            }
            // 3-second sweep period at 20 tps → 60-tick phase.
            long t = sl.getGameTime();
            float phase = (float) Math.sin((t % 60L) / 60.0 * (Math.PI * 2.0));
            state.yawDeg = ((state.sentryStartYaw + phase * 20f) % 360f + 360f) % 360f;
            state.pitchDeg = net.deceasedcraft.deceasedcc.turrets.TurretAim.approachPitch(state.pitchDeg, 0f, 2f);
        } else {
            state.sentryActive = false;
            state.yawDeg = net.deceasedcraft.deceasedcc.turrets.TurretAim.approachYaw(state.yawDeg, 0f, 2f); // point north
            state.pitchDeg = net.deceasedcraft.deceasedcc.turrets.TurretAim.approachPitch(state.pitchDeg, 0f, 2f);
        }
    }

    private static boolean hasClearShot(Level level, BlockPos pos, Entity target) {
        net.deceasedcraft.deceasedcc.core.TurretMetrics.recordRaycast();
        var from = pos.getCenter().add(0, 0.55, 0); // roughly the muzzle
        var to = target.getBoundingBox().getCenter();
        var ctx = new net.minecraft.world.level.ClipContext(
                from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                null);
        var hit = level.clip(ctx);
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** Raycast along the turret's current aim direction (which may lead the
     *  target) and make sure no block is closer than the target. Prevents
     *  firing into a wall when the lead-point is occluded. */
    private boolean aimRayUnblocked(Level level, BlockPos pos, Entity target) {
        net.deceasedcraft.deceasedcc.core.TurretMetrics.recordRaycast();
        var from = pos.getCenter().add(0, 0.55, 0);
        double yRad = Math.toRadians(state.yawDeg);
        double pRad = Math.toRadians(state.pitchDeg);
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
        var hit = level.clip(ctx);
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** True if ANY player is standing in the bullet's path. The ray extends
     *  64 blocks PAST the target — TACZ bullets penetrate zombies, so a
     *  friendly standing behind a hostile is genuinely in the line of fire.
     *  The target itself is excluded so a player IS shootable in PLAYERS_TOO
     *  mode without their own body blocking the shot. Disabled entirely if
     *  the global pauseIfFriendlyInLOS config is false. */
    private boolean friendlyInLineOfFire(ServerLevel sl, BlockPos pos, Entity target) {
        if (!net.deceasedcraft.deceasedcc.core.ModConfig.TURRET_PAUSE_IF_FRIENDLY_IN_LOS.get()) return false;
        net.deceasedcraft.deceasedcc.core.TurretMetrics.recordRaycast();
        var from = pos.getCenter().add(0, 0.55, 0);
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

    private boolean isOnTarget(Entity target, BlockPos pos, float tolDeg) {
        var from = pos.getCenter().add(0, 0.55, 0);
        double aimY = target.getY() + target.getBbHeight() * 0.5;
        double dx = target.getX() - from.x;
        double dy = aimY - from.y;
        double dz = target.getZ() - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (desiredYaw < 0) desiredYaw += 360f;
        float desiredPitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        desiredPitch = Math.max(-90f, Math.min(50f, desiredPitch));
        float yawDiff = Math.abs(((desiredYaw - state.yawDeg + 540f) % 360f) - 180f);
        float pitchDiff = Math.abs(desiredPitch - state.pitchDeg);
        return yawDiff <= tolDeg && pitchDiff <= tolDeg;
    }

    private boolean isValid(LivingEntity e) {
        if (!e.isAlive()) return false;
        // Never target other turrets' shooter entities. They're invisible Mobs
        // (LivingEntity, non-Player), so the ALL_LIVING filter would otherwise
        // pick them up and turrets would shoot each other indefinitely.
        if (e instanceof net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity) return false;
        // Ignore mobs within 1 block of the turret — too close for the
        // gun to hit cleanly, and avoids muzzle-eating-zombie scenarios.
        if (e.distanceToSqr(getBlockPos().getCenter()) < 1.0) return false;
        // Never target the player who placed the turret, regardless of mode.
        if (state.placerUuid != null && e instanceof Player p
                && state.placerUuid.equals(p.getUUID())) {
            return false;
        }
        return switch (state.mode) {
            case HOSTILES_ONLY -> e instanceof Enemy || (e instanceof Mob m && m.getTarget() != null);
            case PLAYERS_TOO   -> e instanceof Enemy || e instanceof Player;
            case ALL_LIVING    -> !(e instanceof Player);
        };
    }

    private LivingEntity pickNearest(List<LivingEntity> list, BlockPos pos) {
        LivingEntity best = null; double d2 = Double.MAX_VALUE;
        var centre = pos.getCenter();
        for (LivingEntity e : list) {
            double dd = e.distanceToSqr(centre);
            if (dd < d2) { d2 = dd; best = e; }
        }
        return best;
    }

    private void aimAt(Entity target, BlockPos pos, float turnMult) {
        var from = pos.getCenter().add(0, 0.55, 0);
        double aimY = target.getY() + target.getBbHeight() * 0.5;
        double dx = target.getX() - from.x;
        double dy = aimY - from.y;
        double dz = target.getZ() - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (desiredYaw < 0) desiredYaw += 360f;
        float desiredPitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        desiredPitch = Math.max(-90f, Math.min(50f, desiredPitch));

        float base = net.deceasedcraft.deceasedcc.core.ModConfig.TURRET_MAX_TURN_SPEED.get().floatValue() * 0.5f;
        float maxStep = base * turnMult;
        state.yawDeg = net.deceasedcraft.deceasedcc.turrets.TurretAim.approachYaw(state.yawDeg, desiredYaw, maxStep);
        state.pitchDeg = net.deceasedcraft.deceasedcc.turrets.TurretAim.approachPitch(state.pitchDeg, desiredPitch, maxStep);
    }

    private void syncIfNeeded(Level level, BlockPos pos, BlockState blockState) {
        if (!(level instanceof ServerLevel sl)) return;
        // Rotation-only sync via a dedicated packet — sendBlockUpdated with an
        // unchanged state doesn't re-ship BE data, so the client's yaw/pitch
        // stayed stale. A tiny packet does the job cleanly.
        boolean changed = state.yawDeg != lastSyncedYaw || state.pitchDeg != lastSyncedPitch || state.sentryActive != lastSyncedSentry;
        if (!changed) return;
        if (level.getGameTime() - lastSyncTick < SYNC_INTERVAL_TICKS) return;
        lastSyncedYaw = state.yawDeg;
        lastSyncedPitch = state.pitchDeg;
        lastSyncedSentry = state.sentryActive;
        lastSyncTick = level.getGameTime();
        net.deceasedcraft.deceasedcc.network.DeceasedNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.TRACKING_CHUNK.with(() -> sl.getChunkAt(pos)),
                new net.deceasedcraft.deceasedcc.network.TurretRotationPacket(pos, state.yawDeg, state.pitchDeg, state.sentryActive));
    }

    /** Detects render-relevant transitions and broadcasts a full BE update.
     *  Called every active server tick. Cost is two cheap field compares. */
    private void syncRenderStateIfChanged(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel)) return;
        boolean visualInop = !state.weapon.isEmpty()
                && TaczBridge.isGunInoperable(state.weapon, state.ammoSlots);
        int weaponItemHash = state.weapon.isEmpty()
                ? 0 : System.identityHashCode(state.weapon.getItem());
        boolean changed = visualInop != lastSyncedInoperable
                || weaponItemHash != lastSyncedWeaponItemHash;
        if (firstTickPending || changed) {
            forceSync(level, pos);
            firstTickPending = false;
            lastSyncedInoperable = visualInop;
            lastSyncedWeaponItemHash = weaponItemHash;
            // Just became inoperable → drop LOD activity to IDLE so we wake at
            // 1/sec instead of 5/sec while doing nothing useful.
            if (visualInop) {
                state.activity = net.deceasedcraft.deceasedcc.turrets.TurretActivity.IDLE;
                state.consecutiveEmptyScans = 0;
            }
        }
    }

    private void forceSync(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl)) return;
        BlockState bs = level.getBlockState(pos);
        level.sendBlockUpdated(pos, bs, bs, 3);
        net.deceasedcraft.deceasedcc.network.DeceasedNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.TRACKING_CHUNK.with(() -> sl.getChunkAt(pos)),
                new net.deceasedcraft.deceasedcc.network.TurretRotationPacket(pos, state.yawDeg, state.pitchDeg, state.sentryActive));
        lastSyncTick = level.getGameTime();
    }
}
