package net.deceasedcraft.deceasedcc.turrets;

import net.deceasedcraft.deceasedcc.core.ModEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Minimal camera anchor for the Turret Remote's wireless-view mode.
 * <p>
 * Never added to a Level — it's an orphan Entity. The client hands it to
 * {@code Minecraft.setCameraEntity(...)} and updates its position + rotation
 * each tick. The anchor is the only thing required to drive the render
 * camera; nothing tracks or networks it.
 * <p>
 * The EntityType is still registered so the renderer dispatcher can
 * resolve a no-op renderer, even though no instance is ever spawned.
 */
public class TurretCameraEntity extends Entity {

    public TurretCameraEntity(EntityType<? extends TurretCameraEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public TurretCameraEntity(Level level, double x, double y, double z) {
        this(ModEntities.TURRET_CAMERA.get(), level);
        this.setPos(x, y, z);
    }

    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean isAttackable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean shouldRender(double x, double y, double z) { return false; }
    @Override public boolean shouldRenderAtSqrDistance(double dSqr) { return false; }
    @Override public boolean isInvulnerable() { return true; }

    @Override protected void defineSynchedData() { }
    @Override protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) { }
    @Override protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) { }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getAddEntityPacket() {
        return new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(this);
    }

    /** Update position + rotation for the current tick. Keeps the previous
     *  tick's rotation as {@code yRotO / xRotO} so Minecraft's render-frame
     *  interpolation produces a smooth camera sweep instead of snapping once
     *  per tick (which manifested as heavy stutter at high zoom). Intended
     *  to be called ONCE per client tick. */
    public void aimAt(double x, double y, double z, float yawDeg, float pitchDeg) {
        // Roll previous frame → "old" (for interpolation)
        this.xo = this.getX(); this.yo = this.getY(); this.zo = this.getZ();
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
        // Apply new
        this.setPos(x, y, z);
        this.setYRot(yawDeg);
        this.setXRot(pitchDeg);
    }
}
