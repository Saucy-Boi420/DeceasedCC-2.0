package net.deceasedcraft.deceasedcc.turrets;

import net.deceasedcraft.deceasedcc.blocks.entity.BasicTurretBlockEntity;
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

public class BasicTurretMenu extends AbstractContainerMenu {
    public static final int BUTTON_CYCLE_FILTER = 1;
    public static final int BUTTON_TOGGLE_ENABLED = 2;
    public static final int SLOT_WEAPON_X = 80;
    public static final int SLOT_WEAPON_Y = 18;
    public static final int AMMO_ROW_X = 44;
    public static final int AMMO_ROW_Y = 44;
    public static final int AMMO_SPACING = 24;
    public static final int UPGRADE_ROW_X = 68;       // 2 slots spacing 24, centred (68, 92)
    public static final int UPGRADE_ROW_Y = 70;
    public static final int UPGRADE_SPACING = 24;

    private final Container turret;
    private final BasicTurretBlockEntity be;
    public final DataSlot modeSlot = DataSlot.standalone();
    public final DataSlot enabledSlot = DataSlot.standalone();

    public BasicTurretMenu(int id, Inventory playerInv, Container turret, BasicTurretBlockEntity be) {
        super(ModMenus.BASIC_TURRET.get(), id);
        this.turret = turret;
        this.be = be;
        checkContainerSize(turret, BasicTurretContainer.SIZE);

        addSlot(new FilteredSlot(turret, BasicTurretContainer.SLOT_WEAPON, SLOT_WEAPON_X, SLOT_WEAPON_Y));
        for (int i = 0; i < BasicTurretContainer.SLOT_AMMO_COUNT; i++) {
            addSlot(new FilteredSlot(turret,
                    BasicTurretContainer.SLOT_AMMO_START + i,
                    AMMO_ROW_X + i * AMMO_SPACING, AMMO_ROW_Y));
        }
        for (int i = 0; i < BasicTurretContainer.SLOT_UPGRADE_COUNT; i++) {
            addSlot(new FilteredSlot(turret,
                    BasicTurretContainer.SLOT_UPGRADE_START + i,
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

        addDataSlot(modeSlot);
        addDataSlot(enabledSlot);
        if (be != null) {
            modeSlot.set(be.state.mode.ordinal());
            enabledSlot.set(be.state.enabled ? 1 : 0);
        }
    }

    public static BasicTurretMenu clientFactory(int id, Inventory inv, FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        BasicTurretBlockEntity bt = be instanceof BasicTurretBlockEntity b ? b : null;
        Container c;
        if (bt != null) {
            c = new BasicTurretContainer(bt);
        } else {
            c = new SimpleContainer(BasicTurretContainer.SIZE);
        }
        return new BasicTurretMenu(id, inv, c, bt);
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (buttonId == BUTTON_CYCLE_FILTER && be != null) {
            be.cycleFilter();
            modeSlot.set(be.state.mode.ordinal());
            return true;
        }
        if (buttonId == BUTTON_TOGGLE_ENABLED && be != null) {
            be.state.enabled = !be.state.enabled;
            be.setChanged();
            enabledSlot.set(be.state.enabled ? 1 : 0);
            return true;
        }
        return false;
    }

    public BasicTurretFilter currentMode() {
        return BasicTurretFilter.fromOrdinal(modeSlot.get());
    }

    public boolean enabled() { return enabledSlot.get() == 1; }

    @Override public boolean stillValid(Player player) { return turret.stillValid(player); }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        int turretEnd = BasicTurretContainer.SIZE;
        int playerEnd = turretEnd + 36;

        if (index < turretEnd) {
            if (!moveItemStackTo(stack, turretEnd, playerEnd, true)) return ItemStack.EMPTY;
        } else {
            if (TurretMountContainer.isGunItem(stack)) {
                if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
            } else if (TurretMountContainer.isAmmoItem(stack)) {
                if (!moveItemStackTo(stack,
                        BasicTurretContainer.SLOT_AMMO_START,
                        BasicTurretContainer.SLOT_AMMO_START + BasicTurretContainer.SLOT_AMMO_COUNT,
                        false)) return ItemStack.EMPTY;
            } else if (TurretUpgrade.allowedInBasic(stack)) {
                if (!moveItemStackTo(stack,
                        BasicTurretContainer.SLOT_UPGRADE_START,
                        BasicTurretContainer.SLOT_UPGRADE_START + BasicTurretContainer.SLOT_UPGRADE_COUNT,
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
