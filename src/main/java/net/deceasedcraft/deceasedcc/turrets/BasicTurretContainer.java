package net.deceasedcraft.deceasedcc.turrets;

import net.deceasedcraft.deceasedcc.blocks.entity.BasicTurretBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Basic-turret container.
 *  Slot 0     : weapon
 *  Slots 1-4  : ammo
 *  Slots 5-6  : upgrades (basic tier only)
 */
public class BasicTurretContainer implements Container {
    public static final int SLOT_WEAPON = 0;
    public static final int SLOT_AMMO_START = 1;
    public static final int SLOT_AMMO_COUNT = 4;
    public static final int SLOT_UPGRADE_START = 5;
    public static final int SLOT_UPGRADE_COUNT = 2;
    public static final int SIZE = 1 + SLOT_AMMO_COUNT + SLOT_UPGRADE_COUNT;

    private final BasicTurretBlockEntity be;

    public BasicTurretContainer(BasicTurretBlockEntity be) {
        this.be = be;
        if (be.state.upgradeSlots == null || be.state.upgradeSlots.length != SLOT_UPGRADE_COUNT) {
            ItemStack[] fresh = new ItemStack[SLOT_UPGRADE_COUNT];
            for (int i = 0; i < SLOT_UPGRADE_COUNT; i++) fresh[i] = ItemStack.EMPTY;
            if (be.state.upgradeSlots != null) {
                for (int i = 0; i < Math.min(be.state.upgradeSlots.length, SLOT_UPGRADE_COUNT); i++) {
                    fresh[i] = be.state.upgradeSlots[i];
                }
            }
            be.state.upgradeSlots = fresh;
        }
    }

    @Override public int getContainerSize() { return SIZE; }

    @Override
    public boolean isEmpty() {
        if (!be.state.weapon.isEmpty()) return false;
        for (ItemStack s : be.state.ammoSlots) if (!s.isEmpty()) return false;
        for (ItemStack s : be.state.upgradeSlots) if (!s.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot == SLOT_WEAPON) return be.state.weapon;
        int ammoIdx = slot - SLOT_AMMO_START;
        if (ammoIdx >= 0 && ammoIdx < SLOT_AMMO_COUNT) return be.state.ammoSlots[ammoIdx];
        int upIdx = slot - SLOT_UPGRADE_START;
        if (upIdx >= 0 && upIdx < SLOT_UPGRADE_COUNT) return be.state.upgradeSlots[upIdx];
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack cur = getItem(slot);
        if (cur.isEmpty()) return ItemStack.EMPTY;
        ItemStack removed = cur.split(count);
        setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack cur = getItem(slot);
        setItem(slot, ItemStack.EMPTY);
        return cur;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot == SLOT_WEAPON) { be.state.weapon = stack; setChanged(); return; }
        int ammoIdx = slot - SLOT_AMMO_START;
        if (ammoIdx >= 0 && ammoIdx < SLOT_AMMO_COUNT) { be.state.ammoSlots[ammoIdx] = stack; setChanged(); return; }
        int upIdx = slot - SLOT_UPGRADE_START;
        if (upIdx >= 0 && upIdx < SLOT_UPGRADE_COUNT) { be.state.upgradeSlots[upIdx] = stack; setChanged(); return; }
    }

    @Override public void setChanged() { be.setChanged(); }

    @Override
    public boolean stillValid(Player player) {
        if (be.getLevel() == null) return false;
        if (be.getLevel().getBlockEntity(be.getBlockPos()) != be) return false;
        BlockPos p = be.getBlockPos();
        return player.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) <= 64;
    }

    @Override
    public void clearContent() {
        be.state.weapon = ItemStack.EMPTY;
        for (int i = 0; i < be.state.ammoSlots.length; i++) be.state.ammoSlots[i] = ItemStack.EMPTY;
        for (int i = 0; i < be.state.upgradeSlots.length; i++) be.state.upgradeSlots[i] = ItemStack.EMPTY;
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == SLOT_WEAPON) return TurretMountContainer.isGunItem(stack);
        int ammoIdx = slot - SLOT_AMMO_START;
        if (ammoIdx >= 0 && ammoIdx < SLOT_AMMO_COUNT) return TurretMountContainer.isAmmoItem(stack);
        int upIdx = slot - SLOT_UPGRADE_START;
        if (upIdx >= 0 && upIdx < SLOT_UPGRADE_COUNT) return TurretUpgrade.allowedInBasic(stack);
        return false;
    }
}
