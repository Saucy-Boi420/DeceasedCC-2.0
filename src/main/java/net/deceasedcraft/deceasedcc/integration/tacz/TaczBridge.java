package net.deceasedcraft.deceasedcc.integration.tacz;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.deceasedcraft.deceasedcc.core.Integrations;
import net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Fires TACZ guns from a real, ticking {@link TurretShooterEntity} per
 * turret. Each turret has its own shooter entity, and each shooter tracks
 * its own "drawn" state — this is the fix for the 1.4.1 issue where a
 * single global {@code lastDrawnGunClass} guard caused every turret beyond
 * the first to thrash between drawing and {@code NOT_DRAW}, cutting effective
 * fire rate to roughly 1/N.
 *
 * <p>Design notes for 1.4.2:
 * <ul>
 *   <li>Reflection handles (TACZ classes + methods) are resolved once and
 *   cached — previously each fire did ~6 {@code Class.forName + getMethod}
 *   lookups on the main thread.</li>
 *   <li>{@link #primeAttachmentCache} is keyed by gun id and executed at
 *   most once per gun type per session.</li>
 *   <li>{@code setItemSlot} only runs when the gun stack reference actually
 *   changes.</li>
 * </ul>
 */
public final class TaczBridge {
    private static final String NBT_AMMO_COUNT = "GunCurrentAmmoCount";
    private static final int PREFILL_AMMO = 30;

    // Per-shooter drawn state — each turret's shooter tracks its own "drawn
    // gun class" independently so turrets can't knock each other out of
    // fire-ready state.
    private static final ConcurrentHashMap<UUID, Class<?>> DRAWN_BY_SHOOTER = new ConcurrentHashMap<>();
    // Per-shooter "last equipped gun identity" — skips redundant setItemSlot
    // calls and the associated equipment sync packets.
    private static final ConcurrentHashMap<UUID, ItemStack> LAST_GUN_BY_SHOOTER = new ConcurrentHashMap<>();
    // Cached rpm per gun id so we don't reflect GunData every shot.
    private static final ConcurrentHashMap<String, Integer> RPM_BY_GUN = new ConcurrentHashMap<>();

    // Log-once flags.
    private static volatile boolean warned = false;
    private static volatile boolean primeAttachmentWarned = false;
    private static volatile boolean soundWarned = false;
    private static volatile boolean reflectionWarned = false;

    // Cached reflection — resolved lazily on first fire.
    private static volatile TaczRefl REFL = null;

    private TaczBridge() {}

    /** Evict per-shooter cache entries when the owning turret is removed. */
    public static void forgetShooter(UUID shooterUuid) {
        if (shooterUuid == null) return;
        DRAWN_BY_SHOOTER.remove(shooterUuid);
        LAST_GUN_BY_SHOOTER.remove(shooterUuid);
    }

    /** Native fire-rate in ticks for the given gun stack. Fallback chain:
     *  <ol>
     *    <li>The gun's own {@code GunData.roundsPerMinute} (cached).</li>
     *    <li>The classified category's average RPM (e.g., pistol → pistol avg).</li>
     *    <li>{@link GunClassifier#HARDCODED_FALLBACK_RPM}.</li>
     *  </ol>
     *  Always returns &gt;= 1.  */
    public static int nativeFireRateTicks(ItemStack gun) {
        if (gun.isEmpty()) return GunClassifier.HARDCODED_FALLBACK_RPM;
        TaczRefl r = refl();
        if (r == null) return ticksFromRpm(GunClassifier.HARDCODED_FALLBACK_RPM);
        int rpm = 0;
        try {
            Object iGunImpl = r.getIGunOrNull.invoke(null, gun);
            if (iGunImpl != null) {
                Object gunId = r.getGunId.invoke(iGunImpl, gun);
                if (gunId != null) {
                    String key = gunId.toString();
                    Integer cached = RPM_BY_GUN.get(key);
                    if (cached != null) {
                        rpm = cached;
                    } else {
                        Object opt = r.getCommonGunIndex.invoke(null, gunId);
                        if (opt != null && (boolean) opt.getClass().getMethod("isPresent").invoke(opt)) {
                            Object gunIndex = opt.getClass().getMethod("get").invoke(opt);
                            Object gunData = gunIndex.getClass().getMethod("getGunData").invoke(gunIndex);
                            Object rpmObj = gunData.getClass().getMethod("getRoundsPerMinute").invoke(gunData);
                            rpm = rpmObj instanceof Integer i ? i : 0;
                        }
                        RPM_BY_GUN.put(key, rpm);
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (rpm <= 0) {
            // Fall back to the classifier's category average — e.g., a pack
            // that forgot to set RPM on its pistol still fires at pistol rate.
            rpm = GunClassifier.categoryFallbackRpm(GunClassifier.classOf(gun));
        }
        if (rpm <= 0) rpm = GunClassifier.HARDCODED_FALLBACK_RPM;
        return ticksFromRpm(rpm);
    }

    private static int ticksFromRpm(int rpm) {
        return Math.max(1, 1200 / Math.max(1, rpm));
    }

    /** True when the gun is either empty (0 mag + no ammo in turret slots)
     *  or its durability has been exhausted. The turret uses this to render
     *  the gun flat on the slab and to skip firing. */
    public static boolean isGunInoperable(ItemStack gun, ItemStack[] ammoSlots) {
        if (gun.isEmpty()) return false; // no gun at all is a different state
        if (gun.isDamageableItem() && gun.getDamageValue() >= gun.getMaxDamage() - 1) return true;
        // Ammo empty AND no rounds in the turret's slots.
        CompoundTag tag = gun.getTag();
        int mag = tag == null ? 0 : tag.getInt(NBT_AMMO_COUNT);
        if (mag > 0) return false;
        if (ammoSlots != null) for (ItemStack s : ammoSlots) if (!s.isEmpty()) return false;
        return true;
    }

    /** Back-compat shim used by older call sites that only have the block
     *  entity. Routes through the BE's own fireThroughShooter path. */
    public static void tryFire(TurretMountBlockEntity turret, Entity target) {
        turret.fireThroughShooter();
    }

    /** Fire, and on success eject the matching bullet casing into an adjacent
     *  inventory (or nowhere, if there isn't one). The ammo snapshot is
     *  grabbed BEFORE firing so that we still know what was fired when the
     *  last round in a stack is consumed. */
    public static boolean fireAndEject(net.minecraft.world.level.Level level,
                                       net.minecraft.core.BlockPos turretPos,
                                       TurretShooterEntity shooter, ItemStack gun,
                                       ItemStack[] ammoSlots, float yaw, float pitch,
                                       java.util.Set<UUID> friendlyFireExempt,
                                       boolean tightHitbox) {
        ItemStack ammoSnapshot = ItemStack.EMPTY;
        for (ItemStack s : ammoSlots) {
            if (!s.isEmpty()) { ammoSnapshot = s.copy(); break; }
        }
        boolean fired = fire(shooter, gun, ammoSlots, yaw, pitch, friendlyFireExempt, tightHitbox);
        if (fired && !ammoSnapshot.isEmpty()) {
            CasingEjector.ejectFor(level, turretPos, ammoSnapshot);
        }
        return fired;
    }

    /** Back-compat overload used by auto-targeting paths — wide safety
     *  envelope, default exemption. */
    public static boolean fireAndEject(net.minecraft.world.level.Level level,
                                       net.minecraft.core.BlockPos turretPos,
                                       TurretShooterEntity shooter, ItemStack gun,
                                       ItemStack[] ammoSlots, float yaw, float pitch,
                                       java.util.Set<UUID> friendlyFireExempt) {
        return fireAndEject(level, turretPos, shooter, gun, ammoSlots, yaw, pitch,
                friendlyFireExempt, false);
    }

    public static boolean fireAndEject(net.minecraft.world.level.Level level,
                                       net.minecraft.core.BlockPos turretPos,
                                       TurretShooterEntity shooter, ItemStack gun,
                                       ItemStack[] ammoSlots, float yaw, float pitch) {
        return fireAndEject(level, turretPos, shooter, gun, ammoSlots, yaw, pitch,
                java.util.Collections.emptySet(), false);
    }

    /** Fire a single shot through the given shooter entity. */
    public static boolean fire(TurretShooterEntity shooter, ItemStack gun,
                               ItemStack[] ammoSlots, float yaw, float pitch,
                               java.util.Set<UUID> friendlyFireExempt,
                               boolean tightHitbox) {
        if (!Integrations.tacz()) return false;
        if (gun.isEmpty()) return false;
        if (shooter == null || !shooter.isAlive()) return false;
        if (!(shooter.level() instanceof ServerLevel sl)) return false;
        // Broken or empty gun → refuse to fire (BER swaps to the flat-on-
        // slab render when this is true).
        if (isGunInoperable(gun, ammoSlots)) return false;
        // Friendly-fire safety: in AUTO mode, bullets penetrate mobs and keep
        // travelling, so if a player is anywhere along the muzzle→target line
        // (or behind the target within bullet range) we abort. In REMOTE-CONTROL
        // mode (tightHitbox=true) the player is manually aiming and pulling
        // the trigger — trust them; let the bullet hit whatever's in the way.
        if (!tightHitbox && playerOnShotLine(sl, shooter, yaw, pitch, 64f,
                friendlyFireExempt == null ? java.util.Collections.emptySet() : friendlyFireExempt,
                false)) {
            return false;
        }

        int ammoIdx = -1;
        for (int i = 0; i < ammoSlots.length; i++) {
            if (!ammoSlots[i].isEmpty()) { ammoIdx = i; break; }
        }
        if (ammoIdx == -1) return false;

        final UUID shooterId = shooter.getUUID();

        // When the gun stack on this shooter changes, push the new gun in
        // and re-evaluate its attachment cache so attachment stat deltas
        // (inaccuracy, recoil, RPM, etc.) actually apply to our shots.
        ItemStack lastEquipped = LAST_GUN_BY_SHOOTER.get(shooterId);
        boolean stackChanged = (lastEquipped != gun);
        if (stackChanged) {
            shooter.setItemSlot(EquipmentSlot.MAINHAND, gun);
            LAST_GUN_BY_SHOOTER.put(shooterId, gun);
        }

        CompoundTag gunTag = gun.getOrCreateTag();
        if (!gunTag.contains(NBT_AMMO_COUNT) || gunTag.getInt(NBT_AMMO_COUNT) <= 0) {
            gunTag.putInt(NBT_AMMO_COUNT, PREFILL_AMMO);
        }

        ItemStackHandler inv = shooter.getAmmoInventory();
        int slotsToCopy = Math.min(ammoSlots.length, inv.getSlots());
        for (int i = 0; i < slotsToCopy; i++) {
            inv.setStackInSlot(i, ammoSlots[i].copy());
        }

        TaczRefl r = refl();
        if (r == null) return false;

        boolean fired = false;
        try {
            Object operator = r.fromLivingEntity.invoke(null, shooter);

            // Evaluate attachment effects fresh whenever the stack changes,
            // then push the evaluated AttachmentCacheProperty into the
            // operator so the next shoot() honours scope/barrel/magazine
            // modifiers.
            if (stackChanged) {
                Object prop = buildAttachmentCache(gun);
                if (prop != null) {
                    try {
                        r.updateCacheProperty.invoke(operator, prop);
                    } catch (Throwable ignored) {}
                }
            }

            Class<?> currentGunClass = gun.getItem().getClass();
            Class<?> drawnFor = DRAWN_BY_SHOOTER.get(shooterId);
            if (drawnFor != currentGunClass) {
                Supplier<ItemStack> gunSupplier = shooter::getMainHandItem;
                r.draw.invoke(operator, gunSupplier);
                DRAWN_BY_SHOOTER.put(shooterId, currentGunClass);
            }

            try { r.aim.invoke(operator, true); } catch (Throwable ignored) {}

            final float pitchFinal = pitch;
            final float yawFinal = yaw;
            Supplier<Float> pitchSupplier = () -> pitchFinal;
            Supplier<Float> yawSupplier = () -> yawFinal;
            // Reset the shooter's "last shot" timestamp to 0 before calling
            // shoot(). TACZ's cooldown check compares current time to this
            // field — 0 is "fired in 1970", well past any cooldown window,
            // so the shot is always allowed. This is what bypasses the
            // per-gun RPM cap without breaking TACZ's firing pipeline (the
            // earlier 3-arg shoot(..., 0L) approach broke firing entirely).
            try {
                Object holder = r.getDataHolder.invoke(operator);
                if (holder != null) r.holderShootTimestamp.setLong(holder, 0L);
            } catch (Throwable ignored) {}
            Object result = r.shoot.invoke(operator, pitchSupplier, yawSupplier);
            String resultName = result == null ? "NULL" : result.toString();

            if (resultName.contains("SUCCESS") || resultName.contains("FIRED")) {
                fired = true;
                broadcastShootSound(shooter, gun);
                // Durability damage via vanilla hurt() — Unbreaking enchantment
                // probabilistically skips the hit internally, so honouring the
                // enchant falls out automatically. If the gun has no vanilla
                // maxDamage this is a cheap no-op.
                if (gun.isDamageableItem()) {
                    gun.hurt(1, shooter.getRandom(), null);
                }
            } else if (resultName.contains("NEED_BOLT")) {
                invokeVoid(r.iGunOperator, operator, "bolt");
            } else if (resultName.contains("NOT_DRAW")) {
                // This shooter lost its drawn state — force a redraw next
                // time. Only this shooter's entry is cleared, not a global
                // guard that would poison every other turret.
                DRAWN_BY_SHOOTER.remove(shooterId);
            } else if (resultName.contains("NO_AMMO")) {
                invokeVoid(r.iGunOperator, operator, "reload");
            }
        } catch (Throwable t) {
            if (!warned) {
                DeceasedCC.LOGGER.error("TACZ firing failed (logged once): {}", t.toString(), t);
                warned = true;
            }
        }

        for (int i = 0; i < slotsToCopy; i++) {
            ammoSlots[i] = inv.getStackInSlot(i);
        }
        if (fired && !ammoSlots[ammoIdx].isEmpty()) {
            ammoSlots[ammoIdx].shrink(1);
        }
        return fired;
    }

    /** Vertical offset from the turret block's Y to the muzzle (bullet
     *  spawn height). Must match the BER's active-gun render height AND
     *  the aim/LOS raycasts in TurretMountPeripheral. Changing this
     *  without updating aimAt (which references this constant) will cause
     *  bullets to miss by MUZZLE_Y_OFFSET blocks vertically. */
    public static final double MUZZLE_Y_OFFSET = 1.05;

    /** Position + rotate a shooter at the turret's muzzle. */
    public static void placeShooter(TurretShooterEntity shooter, BlockPos turretPos,
                                    float yaw, float pitch) {
        double yRad = Math.toRadians(yaw);
        double pRad = Math.toRadians(pitch);
        double fx = -Math.sin(yRad) * Math.cos(pRad);
        double fy = -Math.sin(pRad);
        double fz =  Math.cos(yRad) * Math.cos(pRad);

        double mx = turretPos.getX() + 0.5 + fx * 0.4;
        double my = turretPos.getY() + MUZZLE_Y_OFFSET + fy * 0.4;
        double mz = turretPos.getZ() + 0.5 + fz * 0.4;

        shooter.setPos(mx, my, mz);
        shooter.setYRot(yaw);
        shooter.setXRot(pitch);
        shooter.setYHeadRot(yaw);
        shooter.setYBodyRot(yaw);
        shooter.xRotO = pitch;
        shooter.yRotO = yaw;
    }

    // ---- cached reflection -------------------------------------------------

    private static TaczRefl refl() {
        TaczRefl r = REFL;
        if (r != null) return r;
        synchronized (TaczBridge.class) {
            if (REFL != null) return REFL;
            try {
                REFL = new TaczRefl();
            } catch (Throwable t) {
                if (!reflectionWarned) {
                    DeceasedCC.LOGGER.error("TaczBridge: failed to resolve TACZ reflection (logged once): {}", t.toString(), t);
                    reflectionWarned = true;
                }
                return null;
            }
            return REFL;
        }
    }

    /** Public accessor for use by GunClassifier — lazy-resolves, may return null. */
    public static TaczRefl reflOrNull() { return refl(); }

    /** Cache of every TACZ Method/Class we use on the hot path. */
    public static final class TaczRefl {
        final Class<?> iGunOperator;
        final Method fromLivingEntity;
        final Method draw;
        final Method aim;
        final Method shoot;
        final Method getDataHolder;
        final java.lang.reflect.Field holderShootTimestamp;
        final Method updateCacheProperty;

        final Class<?> iGun;
        final Method getIGunOrNull;
        final Method getGunId;

        final Class<?> timelessAPI;
        final Method getCommonGunIndex;
        final Class<?> attachCache;
        final java.lang.reflect.Constructor<?> attachCacheCtor;
        final Class<?> gunDataCls;
        final Method attachEval;

        final Class<?> soundMgr;
        final Method sendSoundToNearby;
        final String shoot3p;

        TaczRefl() throws Exception {
            iGunOperator = Class.forName("com.tacz.guns.api.entity.IGunOperator");
            fromLivingEntity = iGunOperator.getDeclaredMethod("fromLivingEntity", LivingEntity.class);
            draw  = iGunOperator.getDeclaredMethod("draw", Supplier.class);
            aim   = iGunOperator.getDeclaredMethod("aim", boolean.class);
            shoot = iGunOperator.getDeclaredMethod("shoot", Supplier.class, Supplier.class);
            getDataHolder = iGunOperator.getDeclaredMethod("getDataHolder");
            Class<?> holderCls = Class.forName("com.tacz.guns.entity.shooter.ShooterDataHolder");
            holderShootTimestamp = holderCls.getField("shootTimestamp");
            Class<?> cachePropCls = Class.forName("com.tacz.guns.resource.modifier.AttachmentCacheProperty");
            updateCacheProperty = iGunOperator.getDeclaredMethod("updateCacheProperty", cachePropCls);

            iGun = Class.forName("com.tacz.guns.api.item.IGun");
            getIGunOrNull = iGun.getDeclaredMethod("getIGunOrNull", ItemStack.class);
            getGunId = iGun.getDeclaredMethod("getGunId", ItemStack.class);

            timelessAPI = Class.forName("com.tacz.guns.api.TimelessAPI");
            getCommonGunIndex = timelessAPI.getMethod("getCommonGunIndex", ResourceLocation.class);
            attachCache = Class.forName("com.tacz.guns.resource.modifier.AttachmentCacheProperty");
            attachCacheCtor = attachCache.getConstructor();
            gunDataCls = Class.forName("com.tacz.guns.resource.pojo.data.gun.GunData");
            attachEval = attachCache.getMethod("eval", ItemStack.class, gunDataCls);

            soundMgr = Class.forName("com.tacz.guns.sound.SoundManager");
            shoot3p = (String) soundMgr.getField("SHOOT_3P_SOUND").get(null);
            sendSoundToNearby = soundMgr.getMethod("sendSoundToNearby",
                    LivingEntity.class, int.class,
                    ResourceLocation.class, ResourceLocation.class,
                    String.class, float.class, float.class);
        }
    }

    // ---- per-gun attachment cache ----------------------------------------

    // Safety envelope around each player's hitbox when testing the shot
    // line. The player's actual hitbox is 0.6×1.8×0.6; these margins add
    // enough room to cover a sprint-stride between server ticks (≈0.3 blks
    // sideways) and a jump / fall window (≈1.25 blks up, 0.8 down). Tune
    // here if turrets are still too stingy or too reckless.
    private static final double PLAYER_MARGIN_X = 1.5;
    private static final double PLAYER_MARGIN_Y_UP = 2.0;
    private static final double PLAYER_MARGIN_Y_DN = 1.0;
    private static final double PLAYER_MARGIN_Z = 1.5;

    /** Walk an AABB-swept line from the shooter in its aim direction out to
     *  {@code range} blocks. If any {@code Player}'s INFLATED bounding box
     *  (hitbox + safety margins) intersects the swept segment, abort. The
     *  inflate accounts for players running / jumping between server ticks,
     *  so a turret won't clip a friendly who's moving past the line of
     *  fire. Bullets pass through mobs, so a player behind a zombie is
     *  still at risk — the range test extends past the target. */
    private static boolean playerOnShotLine(ServerLevel sl,
                                            TurretShooterEntity shooter,
                                            float yaw, float pitch, float range,
                                            java.util.Set<UUID> exempt,
                                            boolean tightHitbox) {
        double yRad = Math.toRadians(yaw);
        double pRad = Math.toRadians(pitch);
        double fx = -Math.sin(yRad) * Math.cos(pRad);
        double fy = -Math.sin(pRad);
        double fz =  Math.cos(yRad) * Math.cos(pRad);

        double sx = shooter.getX(), sy = shooter.getY() + 0.1, sz = shooter.getZ();
        double ex = sx + fx * range;
        double ey = sy + fy * range;
        double ez = sz + fz * range;
        net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(sx, sy, sz);
        net.minecraft.world.phys.Vec3 to   = new net.minecraft.world.phys.Vec3(ex, ey, ez);

        // Broad-phase: an AABB around the segment, pre-inflated by the
        // player margin, used to cheap-skip players nowhere near the line.
        double broadInflate = Math.max(Math.max(PLAYER_MARGIN_X, PLAYER_MARGIN_Z),
                Math.max(PLAYER_MARGIN_Y_UP, PLAYER_MARGIN_Y_DN));
        net.minecraft.world.phys.AABB segBox = new net.minecraft.world.phys.AABB(from, to).inflate(broadInflate);
        for (net.minecraft.world.entity.player.Player p : sl.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            if (exempt != null && exempt.contains(p.getUUID())) continue;
            net.minecraft.world.phys.AABB hit = p.getBoundingBox();
            if (!hit.intersects(segBox)) continue;
            net.minecraft.world.phys.AABB safe;
            if (tightHitbox) {
                // Remote-control mode: the exempt player (controller) is
                // stationary at their body location — no need for the wide
                // safety envelope. Use the raw bounding box so shots just
                // past the controller still fire.
                safe = hit;
            } else {
                // Expand asymmetrically — generous upward (jumping) and to the
                // sides (strafing), smaller downward (falling).
                safe = new net.minecraft.world.phys.AABB(
                        hit.minX - PLAYER_MARGIN_X,
                        hit.minY - PLAYER_MARGIN_Y_DN,
                        hit.minZ - PLAYER_MARGIN_Z,
                        hit.maxX + PLAYER_MARGIN_X,
                        hit.maxY + PLAYER_MARGIN_Y_UP,
                        hit.maxZ + PLAYER_MARGIN_Z);
            }
            if (safe.clip(from, to).isPresent()) return true;
        }
        return false;
    }

    /** Build a fresh {@code AttachmentCacheProperty} for the given gun stack
     *  (evaluated against the stack's current attachment NBT). Returns null
     *  on failure — caller should skip the {@code updateCacheProperty} call.
     *  Evaluated per-stack-change since attachments change the result. */
    private static Object buildAttachmentCache(ItemStack gunStack) {
        TaczRefl r = refl();
        if (r == null) return null;
        try {
            Object iGunImpl = r.getIGunOrNull.invoke(null, gunStack);
            if (iGunImpl == null) return null;
            Object gunId = r.getGunId.invoke(iGunImpl, gunStack);
            if (gunId == null) return null;
            Object opt = r.getCommonGunIndex.invoke(null, gunId);
            if (opt == null) return null;
            if (!(boolean) opt.getClass().getMethod("isPresent").invoke(opt)) return null;
            Object gunIndex = opt.getClass().getMethod("get").invoke(opt);
            Object gunData = gunIndex.getClass().getMethod("getGunData").invoke(gunIndex);
            Object prop = r.attachCacheCtor.newInstance();
            r.attachEval.invoke(prop, gunStack, gunData);
            return prop;
        } catch (Throwable t) {
            if (!primeAttachmentWarned) {
                DeceasedCC.LOGGER.warn("TaczBridge.buildAttachmentCache failed (logged once): {}", t.toString());
                primeAttachmentWarned = true;
            }
            return null;
        }
    }

    // ---- audio + helpers -------------------------------------------------

    private static void invokeVoid(Class<?> cls, Object instance, String method) {
        try {
            Method m = cls.getDeclaredMethod(method);
            m.invoke(instance);
        } catch (Throwable ignored) {}
    }

    private static void broadcastShootSound(TurretShooterEntity shooter, ItemStack gun) {
        TaczRefl r = refl();
        if (r == null) return;
        try {
            Object iGunImpl = r.getIGunOrNull.invoke(null, gun);
            if (iGunImpl == null) return;
            Object gunId = r.getGunId.invoke(iGunImpl, gun);
            if (gunId == null) return;
            r.sendSoundToNearby.invoke(null, shooter, 64, gunId, gunId, r.shoot3p, 1.0f, 1.0f);
        } catch (Throwable t) {
            if (!soundWarned) {
                DeceasedCC.LOGGER.warn("TaczBridge: gun shoot-sound broadcast failed (logged once): {}", t.toString());
                soundWarned = true;
            }
        }
    }
}
