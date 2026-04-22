package net.deceasedcraft.deceasedcc.blocks.entity;

import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.deceasedcraft.deceasedcc.peripherals.PeripheralBlockEntity;
import net.deceasedcraft.deceasedcc.turrets.TurretMountPeripheral;
import net.deceasedcraft.deceasedcc.turrets.TurretState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * TACZ-integrated turret mount. Live firing reaches into TACZ's {@code GunItem}
 * via reflection — see {@link net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge}.
 * If TACZ is absent the turret silently no-ops on fire requests.
 *
 * <p>Behaviours handled here each tick:
 * <ul>
 *   <li>Absorb TACZ gun items dropped on top of the mount into the weapon slot.</li>
 *   <li>Pull matching TACZ ammo from any adjacent inventory (chest, barrel, etc.)
 *   into the 4-slot ammo buffer.</li>
 *   <li>Push peripheral target/fire logic via {@link TurretMountPeripheral#serverTick()}.</li>
 *   <li>Throttle block-entity sync to clients so yaw/pitch and the weapon stack
 *   replicate without flooding the network.</li>
 * </ul>
 */
public class TurretMountBlockEntity extends PeripheralBlockEntity {
    private static final String TACZ_MODID = "tacz";
    private static final int SYNC_INTERVAL_TICKS = 4;
    private static final int AMMO_PULL_INTERVAL_TICKS = 20;

    public final TurretState state = new TurretState();
    /** Client-only smoother for yaw/pitch updates. */
    public final net.deceasedcraft.deceasedcc.turrets.TurretRotationLerp rotationLerp =
            new net.deceasedcraft.deceasedcc.turrets.TurretRotationLerp();
    private TurretMountPeripheral peripheralRef;
    private float lastSyncedYaw = Float.NaN;
    private float lastSyncedPitch = Float.NaN;
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

    public TurretMountBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.TURRET_MOUNT.get(), pos, blockState);
    }

    @Override
    protected IPeripheral createPeripheral() {
        peripheralRef = new TurretMountPeripheral(this);
        return peripheralRef;
    }

    public TurretMountPeripheral mountPeripheral() {
        peripheral();
        return peripheralRef;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        state.save(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        state.load(tag);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        state.save(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        state.load(tag);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Effective range after upgrade multipliers. Used by the global
     *  TurretRegistry for daisy-chain activation overlap checks. */
    public int computeEffectiveRange() {
        float mult = net.deceasedcraft.deceasedcc.turrets.TurretUpgrade
                .effectiveMultipliers(state.upgradeSlots)
                .get(net.deceasedcraft.deceasedcc.turrets.TurretUpgrade.Stat.RANGE);
        int base = net.deceasedcraft.deceasedcc.core.ModConfig.TURRET_ADVANCED_RANGE.get();
        return Math.max(4, (int) (base * mult));
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
            int eff = computeEffectiveRange();
            net.deceasedcraft.deceasedcc.core.TurretRegistry.register(sl, getBlockPos(), eff, true);
            lastRegisteredRange = eff;
        }
    }

    @Override
    public void setRemoved() {
        // Drop from the activation registry first so a freshly-broken turret
        // stops gating chain-linked neighbours.
        if (getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
            net.deceasedcraft.deceasedcc.core.TurretRegistry.unregister(sl, getBlockPos());
        }
        net.deceasedcraft.deceasedcc.core.TurretTickGuard.clear(getBlockPos());
        // When the turret block is destroyed mid-session, kick the remote
        // controller out cleanly so their camera detaches and the ghost
        // UUID in the registry is cleaned up.
        if (state.controllingPlayer != null
                && getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
            java.util.UUID uuid = state.controllingPlayer;
            state.controllingPlayer = null;
            net.deceasedcraft.deceasedcc.turrets.ControlledTurretRegistry.unregister(uuid);
            net.minecraft.server.level.ServerPlayer player =
                    sl.getServer().getPlayerList().getPlayer(uuid);
            if (player != null) {
                net.deceasedcraft.deceasedcc.network.DeceasedNetwork.CHANNEL.send(
                        net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                        new net.deceasedcraft.deceasedcc.network.TurretControlPackets.ForceExit(
                                "Turret destroyed"));
            }
        }
        super.setRemoved();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState blockState, BlockEntity be) {
        if (!(be instanceof TurretMountBlockEntity tm)) return;
        // Wrap the entire body in a TickGuard so any exception thrown by
        // upgrade math, TACZ reflection, peripheral logic, or controller
        // packets isolates to this one turret instead of crashing the tick.
        net.deceasedcraft.deceasedcc.core.TurretTickGuard.runTick(level, pos, () -> {
            // Proximity-activation gate. Skipped when:
            //   - a player is remote-controlling this turret, OR
            //   - CC has the turret in manual mode (Lua may issue setAim/fire any tick).
            if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                int effRange = tm.computeEffectiveRange();
                if (effRange != tm.lastRegisteredRange) {
                    net.deceasedcraft.deceasedcc.core.TurretRegistry.refreshRange(sl, pos, effRange);
                    tm.lastRegisteredRange = effRange;
                }
                boolean bypass = tm.state.controllingPlayer != null
                        || (tm.state.ccControl && tm.state.manualMode);
                if (!bypass && !net.deceasedcraft.deceasedcc.core.TurretRegistry.isActive(sl, pos)) return;
            }
            tm.syncRenderStateIfChanged(level, pos);
            tm.tickPickupAndAmmo(level, pos);
            tm.ensureShooterAndPosition(level, pos);
            tm.mountPeripheral().serverTick();
            tm.syncIfNeeded(level, pos, blockState);
        });
    }

    /** Called by TaczBridge.tryFire back-compat shim. Routes through the
     *  owned shooter entity so firing state actually advances. Returns
     *  true on successful shot so callers (Fire packet handler, auto-fire
     *  tick) can gate cooldown advancement on real fires only. */
    public boolean fireThroughShooter() {
        net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity shooter = cachedShooter;
        if (shooter == null || !shooter.isAlive()) return false;
        java.util.Set<java.util.UUID> exempt = new java.util.HashSet<>();
        boolean tightHitbox = false;
        if (state.controllingPlayer != null) {
            // Remote-control: player deliberately aims + fires. Only protect
            // their own (stationary) body; everyone else is a valid target.
            exempt.add(state.controllingPlayer);
            tightHitbox = true;
        } else {
            // Auto-target / CC-driven fire: keep the usual friendly-fire
            // envelope + exempt the placer so their body doesn't abort
            // every shot when they stand near the turret.
            if (state.placerUuid != null) exempt.add(state.placerUuid);
        }
        boolean fired = net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.fireAndEject(
                getLevel(), getBlockPos(),
                shooter, state.weapon, state.ammoSlots,
                state.yawDeg, state.pitchDeg, exempt, tightHitbox);
        if (fired) {
            net.deceasedcraft.deceasedcc.core.TurretMetrics.recordShot();
            // setChanged here is load-bearing: TaczBridge.fire shrinks the
            // ammo slot in place. Without this, ammo never persists across
            // a save-and-reload cycle.
            setChanged();
        }
        return fired;
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
        // Always re-set on each call so the rewriter can walk shooter→pos→BE.
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
            if (stack.isEmpty() || !isGunItem(stack)) continue;
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
            if (src.isEmpty() || !isAmmoItem(src)) continue;
            for (int i = 0; i < state.ammoSlots.length; i++) {
                ItemStack dst = state.ammoSlots[i];
                if (dst.isEmpty()) {
                    ItemStack extracted = handler.extractItem(slot, src.getMaxStackSize(), false);
                    if (!extracted.isEmpty()) {
                        state.ammoSlots[i] = extracted;
                        return true;
                    }
                } else if (ItemStack.isSameItemSameTags(dst, src) && dst.getCount() < dst.getMaxStackSize()) {
                    int room = dst.getMaxStackSize() - dst.getCount();
                    ItemStack extracted = handler.extractItem(slot, Math.min(room, src.getCount()), false);
                    if (!extracted.isEmpty()) {
                        dst.grow(extracted.getCount());
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void syncIfNeeded(Level level, BlockPos pos, BlockState blockState) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return;
        // Rotation-only sync via a dedicated packet. sendBlockUpdated with an
        // identical block state does NOT re-ship BE data, which is why the
        // old approach never updated clients. We now push a tiny dedicated
        // packet to everyone tracking this chunk.
        boolean rotationChanged = state.yawDeg != lastSyncedYaw || state.pitchDeg != lastSyncedPitch;
        if (!rotationChanged) return;
        if (level.getGameTime() - lastSyncTick < SYNC_INTERVAL_TICKS) return;
        lastSyncedYaw = state.yawDeg;
        lastSyncedPitch = state.pitchDeg;
        lastSyncTick = level.getGameTime();
        net.deceasedcraft.deceasedcc.network.DeceasedNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.TRACKING_CHUNK.with(() -> sl.getChunkAt(pos)),
                new net.deceasedcraft.deceasedcc.network.TurretRotationPacket(pos, state.yawDeg, state.pitchDeg, state.sentryActive));
    }

    /** Detects render-relevant transitions and broadcasts a full BE update.
     *  Called every active server tick. Cost is two cheap field compares. */
    private void syncRenderStateIfChanged(Level level, BlockPos pos) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel)) return;
        boolean visualInop = !state.weapon.isEmpty()
                && net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge
                        .isGunInoperable(state.weapon, state.ammoSlots);
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
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return;
        BlockState bs = level.getBlockState(pos);
        // Send both the full BE update (for weapon stack + slot data) and the
        // rotation packet (for immediate aim refresh).
        level.sendBlockUpdated(pos, bs, bs, 3);
        net.deceasedcraft.deceasedcc.network.DeceasedNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.TRACKING_CHUNK.with(() -> sl.getChunkAt(pos)),
                new net.deceasedcraft.deceasedcc.network.TurretRotationPacket(pos, state.yawDeg, state.pitchDeg, state.sentryActive));
        lastSyncTick = level.getGameTime();
    }

    // --- helpers ---

    private static boolean isGunItem(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || !TACZ_MODID.equals(id.getNamespace())) return false;
        return !isAmmoPath(id.getPath());
    }

    private static boolean isAmmoItem(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || !TACZ_MODID.equals(id.getNamespace())) return false;
        return isAmmoPath(id.getPath());
    }

    private static boolean isAmmoPath(String path) {
        return path.contains("ammo") || path.contains("bullet") || path.contains("round")
                || path.contains("cartridge") || path.contains("magazine") || path.contains("shell");
    }
}
