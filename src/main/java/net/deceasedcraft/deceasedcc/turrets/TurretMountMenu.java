package net.deceasedcraft.deceasedcc.turrets;

import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.deceasedcraft.deceasedcc.core.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * GUI-side container menu for the Advanced Turret Mount. Adds one weapon
 * slot, four ammo slots, the CC-Control toggle button, and the player's
 * inventory + hotbar. Shift-click moves items between the turret and the
 * player as usual.
 */
public class TurretMountMenu extends AbstractContainerMenu {
    public static final int BUTTON_TOGGLE_CC_CONTROL = 1;
    public static final int BUTTON_CYCLE_BASIC_FILTER = 2;
    public static final int BUTTON_TOGGLE_ENABLED = 3;

    public static final int SLOT_WEAPON_X = 80;
    public static final int SLOT_WEAPON_Y = 18;
    public static final int AMMO_ROW_X = 44;
    public static final int AMMO_ROW_Y = 44;
    public static final int AMMO_SPACING = 24;
    // Upgrade slots: 4 in a horizontal row, 18 px apart, centred.
    public static final int UPGRADE_ROW_X = 44;
    public static final int UPGRADE_ROW_Y = 70;
    public static final int UPGRADE_SPACING = 24;

    private final Container turret;
    private final TurretMountBlockEntity be;
    public final DataSlot ccControlSlot = DataSlot.standalone();
    public final DataSlot basicFilterSlot = DataSlot.standalone();
    public final DataSlot enabledSlot = DataSlot.standalone();

    public TurretMountMenu(int id, Inventory playerInv, Container turret, TurretMountBlockEntity be) {
        super(ModMenus.TURRET_MOUNT.get(), id);
        this.turret = turret;
        this.be = be;
        checkContainerSize(turret, TurretMountContainer.SIZE);

        addSlot(new FilteredSlot(turret, TurretMountContainer.SLOT_WEAPON, SLOT_WEAPON_X, SLOT_WEAPON_Y));
        for (int i = 0; i < TurretMountContainer.SLOT_AMMO_COUNT; i++) {
            addSlot(new FilteredSlot(turret,
                    TurretMountContainer.SLOT_AMMO_START + i,
                    AMMO_ROW_X + i * AMMO_SPACING, AMMO_ROW_Y));
        }
        for (int i = 0; i < TurretMountContainer.SLOT_UPGRADE_COUNT; i++) {
            addSlot(new FilteredSlot(turret,
                    TurretMountContainer.SLOT_UPGRADE_START + i,
                    UPGRADE_ROW_X + i * UPGRADE_SPACING, UPGRADE_ROW_Y));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 104 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 162));
        }

        addDataSlot(ccControlSlot);
        addDataSlot(basicFilterSlot);
        addDataSlot(enabledSlot);
        if (be != null) {
            ccControlSlot.set(be.state.ccControl ? 1 : 0);
            basicFilterSlot.set(be.state.basicFilterOrdinal);
            enabledSlot.set(be.state.enabled ? 1 : 0);
        }
    }

    public static TurretMountMenu clientFactory(int id, Inventory playerInv, FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        BlockEntity be = playerInv.player.level().getBlockEntity(pos);
        TurretMountBlockEntity tm = be instanceof TurretMountBlockEntity t ? t : null;
        Container c = tm != null ? new TurretMountContainer(tm) : new SimpleContainer(TurretMountContainer.SIZE);
        return new TurretMountMenu(id, playerInv, c, tm);
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (be == null) return false;
        if (buttonId == BUTTON_TOGGLE_CC_CONTROL) {
            be.state.ccControl = !be.state.ccControl;
            be.setChanged();
            ccControlSlot.set(be.state.ccControl ? 1 : 0);
            return true;
        }
        if (buttonId == BUTTON_CYCLE_BASIC_FILTER) {
            // Only cycles when CC control is off; the filter is a basic-mode
            // concept. Ignored otherwise.
            if (be.state.ccControl) return false;
            BasicTurretFilter next = BasicTurretFilter.fromOrdinal(be.state.basicFilterOrdinal).next();
            be.state.basicFilterOrdinal = next.ordinal();
            be.setChanged();
            basicFilterSlot.set(be.state.basicFilterOrdinal);
            return true;
        }
        if (buttonId == BUTTON_TOGGLE_ENABLED) {
            be.state.enabled = !be.state.enabled;
            be.setChanged();
            enabledSlot.set(be.state.enabled ? 1 : 0);
            return true;
        }
        return false;
    }

    public boolean ccControlOn() { return ccControlSlot.get() == 1; }
    public BasicTurretFilter basicFilter() { return BasicTurretFilter.fromOrdinal(basicFilterSlot.get()); }
    public boolean enabled() { return enabledSlot.get() == 1; }

    @Override public boolean stillValid(Player player) { return turret.stillValid(player); }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        int turretSlotsEnd = TurretMountContainer.SIZE;
        int playerSlotsEnd = turretSlotsEnd + 36;

        if (index < turretSlotsEnd) {
            if (!moveItemStackTo(stack, turretSlotsEnd, playerSlotsEnd, true)) return ItemStack.EMPTY;
        } else {
            if (TurretMountContainer.isGunItem(stack)) {
                if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
            } else if (TurretMountContainer.isAmmoItem(stack)) {
                if (!moveItemStackTo(stack,
                        TurretMountContainer.SLOT_AMMO_START,
                        TurretMountContainer.SLOT_AMMO_START + TurretMountContainer.SLOT_AMMO_COUNT,
                        false)) return ItemStack.EMPTY;
            } else if (TurretUpgrade.allowedInAdvanced(stack)) {
                if (!moveItemStackTo(stack,
                        TurretMountContainer.SLOT_UPGRADE_START,
                        TurretMountContainer.SLOT_UPGRADE_START + TurretMountContainer.SLOT_UPGRADE_COUNT,
                        false)) return ItemStack.EMPTY;
            } else {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    private static final class FilteredSlot extends Slot {
        FilteredSlot(Container c, int idx, int x, int y) { super(c, idx, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return container.canPlaceItem(getContainerSlot(), stack); }
    }
}
