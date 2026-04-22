package net.deceasedcraft.deceasedcc.turrets;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Invisible, invulnerable in-world {@link Mob} used as the shooter for
 * TACZ's gun firing pipeline.
 *
 * <p>Background: TACZ mixes {@code IGunOperator} into {@link net.minecraft.world.entity.LivingEntity}
 * and stores per-shooter firing state on each instance. That state only
 * advances while the entity ticks — spawning the bullet entity, playing the
 * fire sound, running recoil — so a non-ticking Forge FakePlayer can return
 * SUCCESS from {@code shoot()} without actually producing any visible
 * effect. Entropy159/TACZ-Turrets sidesteps this by making the turret
 * itself a Mob; we do the same by spawning one of these entities per
 * turret block, hidden and anchored at the muzzle.</p>
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Invisible, silent, invulnerable, no AI, no gravity, no collision.</li>
 *   <li>Does not save to disk — the turret block re-spawns it on next tick
 *   if missing.</li>
 *   <li>Exposes a 4-slot {@link ItemStackHandler} via
 *   {@link ForgeCapabilities#ITEM_HANDLER} for TACZ's
 *   {@code LivingEntityAmmoCheck} to find rounds.</li>
 *   <li>Gun goes in the vanilla MAIN_HAND equipment slot (TACZ reads it
 *   from there).</li>
 * </ul></p>
 */
public class TurretShooterEntity extends Mob {
    private final ItemStackHandler ammoInventory = new ItemStackHandler(4);
    private final LazyOptional<IItemHandler> ammoCap = LazyOptional.of(() -> ammoInventory);
    /** Pos of the BE that spawned this shooter. Transient — set by the BE on
     *  every (re-)spawn; never serialized because the shooter itself isn't
     *  persisted to disk. Used by TurretDamageRewriter to look up controlling
     *  player and pick the death-message category. */
    public net.minecraft.core.BlockPos ownerTurretPos;

    public TurretShooterEntity(EntityType<? extends Mob> type, Level level) {
        super(type, level);
        this.setInvisible(true);
        this.setInvulnerable(true);
        this.setNoGravity(true);
        // DO NOT setSilent — TACZ's firing pipeline routes its own sounds
        // through the shooter entity; silencing the entity mutes every gun
        // sound for nearby players.
        this.setPersistenceRequired();
        this.setNoAi(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    public ItemStackHandler getAmmoInventory() { return ammoInventory; }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) return ammoCap.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        ammoCap.invalidate();
    }

    @Override public boolean isPickable()        { return false; }
    @Override public boolean isPushable()        { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean fireImmune()        { return true; }
    @Override public boolean ignoreExplosion()   { return true; }

    @Override public boolean hurt(@NotNull DamageSource source, float amount) { return false; }
    @Override public void push(double x, double y, double z) { /* pinned */ }
    @Override protected void pushEntities() { /* pinned */ }

    // Swallow every game-event broadcast so the vibration system + any
    // AI goal that hooks GameEvent.ENTITY_PROJECTILE_SHOOT (or similar)
    // never sees the turret firing. Mobs don't react to vanilla
    // Level.playSound packets directly — they react to GameEvents — so
    // suppressing here kills the "zombies path to the turret on fire"
    // behavior without muting audio for players.
    @Override
    public void gameEvent(@NotNull net.minecraft.world.level.gameevent.GameEvent event,
                          @Nullable net.minecraft.world.entity.Entity source) {
        // intentionally blank
    }
    @Override
    public void gameEvent(@NotNull net.minecraft.world.level.gameevent.GameEvent event) {
        // intentionally blank
    }

    @Override
    public void tick() {
        super.tick();
        // Cancel any accumulated motion so TACZ's bullet math sees us as
        // perfectly stationary and recoil-calculations don't drift us away
        // from the muzzle position the turret assigned.
        this.setDeltaMovement(0, 0, 0);
    }

    @Override protected void customServerAiStep() { /* no AI */ }

    @Override
    public boolean save(@NotNull CompoundTag tag) {
        // Don't persist to world save. The owning turret spawns a fresh
        // shooter next tick if this one is missing.
        return false;
    }

    @Override public boolean shouldBeSaved() { return false; }

    @Override
    protected void dropCustomDeathLoot(@NotNull DamageSource source, int looting, boolean recentlyHit) {
        // no drops — the turret owns the gun and ammo
    }

    @Override public @NotNull HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }

    @Override
    public @NotNull Packet<ClientGamePacketListener> getAddEntityPacket() {
        return new ClientboundAddEntityPacket(this);
    }
}
