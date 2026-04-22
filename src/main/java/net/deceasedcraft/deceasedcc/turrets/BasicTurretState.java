package net.deceasedcraft.deceasedcc.turrets;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Persistent state for a Basic Turret. Mirrors the subset of TurretState that
 * a computer-free turret needs — weapon + ammo + aim + filter + current target
 * + 2 upgrade slots + sentry swivel state.
 */
public class BasicTurretState {
    public BasicTurretFilter mode = BasicTurretFilter.HOSTILES_ONLY;
    public float yawDeg = 0f;
    public float pitchDeg = 0f;
    public UUID currentTargetUuid;
    public ItemStack weapon = ItemStack.EMPTY;
    public ItemStack[] ammoSlots = new ItemStack[] { ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY };
    public ItemStack[] upgradeSlots = new ItemStack[] { ItemStack.EMPTY, ItemStack.EMPTY };
    public UUID shooterUuid;
    public UUID placerUuid;
    public long lastFireGameTime = 0L;
    public boolean sentryActive = false;
    public float sentryStartYaw = 0f;
    public boolean enabled = true;
    // LOD scan-cadence state. NOT serialized — every reload starts in IDLE
    // and re-evaluates on the first scan.
    public TurretActivity activity = TurretActivity.IDLE;
    public int consecutiveEmptyScans = 0;
    public long lastScanTick = 0L;

    public void save(CompoundTag tag) {
        tag.putInt("mode", mode.ordinal());
        tag.putFloat("yaw", yawDeg);
        tag.putFloat("pitch", pitchDeg);
        if (!weapon.isEmpty()) tag.put("weapon", weapon.save(new CompoundTag()));
        ListTag ammo = new ListTag();
        for (ItemStack s : ammoSlots) ammo.add(s.save(new CompoundTag()));
        tag.put("ammo", ammo);
        ListTag ups = new ListTag();
        for (ItemStack s : upgradeSlots) ups.add(s.save(new CompoundTag()));
        tag.put("upgrades", ups);
        if (shooterUuid != null) tag.putUUID("shooter", shooterUuid);
        if (placerUuid != null) tag.putUUID("placer", placerUuid);
        tag.putBoolean("sentry", sentryActive);
        tag.putFloat("sentryStart", sentryStartYaw);
        tag.putBoolean("enabled", enabled);
    }

    public void load(CompoundTag tag) {
        mode = BasicTurretFilter.fromOrdinal(tag.getInt("mode"));
        yawDeg = tag.getFloat("yaw");
        pitchDeg = tag.getFloat("pitch");
        weapon = tag.contains("weapon") ? ItemStack.of(tag.getCompound("weapon")) : ItemStack.EMPTY;
        ListTag ammo = tag.getList("ammo", 10);
        for (int i = 0; i < Math.min(ammo.size(), ammoSlots.length); i++) {
            ammoSlots[i] = ItemStack.of(ammo.getCompound(i));
        }
        ListTag ups = tag.getList("upgrades", 10);
        for (int i = 0; i < Math.min(ups.size(), upgradeSlots.length); i++) {
            upgradeSlots[i] = ItemStack.of(ups.getCompound(i));
        }
        shooterUuid = tag.hasUUID("shooter") ? tag.getUUID("shooter") : null;
        placerUuid = tag.hasUUID("placer") ? tag.getUUID("placer") : null;
        sentryActive = tag.getBoolean("sentry");
        sentryStartYaw = tag.getFloat("sentryStart");
        enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
    }
}
