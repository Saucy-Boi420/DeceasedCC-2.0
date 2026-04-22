package net.deceasedcraft.deceasedcc.turrets;

import net.deceasedcraft.deceasedcc.core.ModItems;
import net.deceasedcraft.deceasedcc.core.ModMenus;
import net.deceasedcraft.deceasedcc.items.TurretRemoteItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu for the in-hand Turret Remote. Backed by the player's held ItemStack
 * (in a specific hand) — upgrade slot contents live in the stack's NBT.
 *
 * Layout:
 *   x=71,y=67  upgrade slot 0
 *   x=89,y=67  upgrade slot 1
 *   standard player inventory + hotbar starting at x=8,y=104
 */
public class TurretRemoteMenu extends AbstractContainerMenu {
    public static final int UPGRADE_SLOT_COUNT = TurretRemoteItem.UPGRADE_SLOT_COUNT;
    public static final int BATTERY_SLOT_COUNT = TurretRemoteItem.BATTERY_SLOT_COUNT;

    // Image height for this GUI (expanded from 186 to 206 to fit the battery row).
    public static final int GUI_HEIGHT = 206;

    private final Inventory playerInv;
    private final InteractionHand hand;
    private final UpgradeBackedContainer upgrades;
    private final BatteryBackedContainer batteries;

    public TurretRemoteMenu(int id, Inventory playerInv, InteractionHand hand) {
        super(ModMenus.TURRET_REMOTE.get(), id);
        this.playerInv = playerInv;
        this.hand = hand;
        this.upgrades = new UpgradeBackedContainer(playerInv.player, hand);
        this.batteries = new BatteryBackedContainer(playerInv.player, hand);
        checkContainerSize(upgrades, UPGRADE_SLOT_COUNT);
        checkContainerSize(batteries, BATTERY_SLOT_COUNT);

        // Upgrade slots (2), centred at y=67
        for (int i = 0; i < UPGRADE_SLOT_COUNT; i++) {
            addSlot(new RemoteUpgradeSlot(upgrades, i, 71 + i * 18, 67));
        }
        // Battery slots (5), centred at y=86 with spacing 18 (43, 61, 79, 97, 115)
        for (int i = 0; i < BATTERY_SLOT_COUNT; i++) {
            addSlot(new BatterySlot(batteries, i, 43 + i * 18, 86));
        }
        // Player inventory — shifted down by 20 for expanded image height
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 124 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 182));
        }
    }

    public static TurretRemoteMenu clientFactory(int id, Inventory inv, FriendlyByteBuf buf) {
        InteractionHand hand = buf.readEnum(InteractionHand.class);
        return new TurretRemoteMenu(id, inv, hand);
    }

    public ItemStack getRemoteStack() {
        return playerInv.player.getItemInHand(hand);
    }

    public InteractionHand getHand() {
        return hand;
    }

    @Override
    public boolean stillValid(Player player) {
        return getRemoteStack().getItem() instanceof TurretRemoteItem;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        int upgradeEnd = UPGRADE_SLOT_COUNT;
        int batteryEnd = upgradeEnd + BATTERY_SLOT_COUNT;
        int playerEnd  = batteryEnd + 36;

        if (index < batteryEnd) {
            // Shift-click from upgrade/battery → player inventory
            if (!moveItemStackTo(stack, batteryEnd, playerEnd, true)) return ItemStack.EMPTY;
        } else {
            // Shift-click from player inventory → upgrade slot if valid, else battery slot if battery
            if (TurretUpgrade.allowedInRemote(stack)) {
                if (!moveItemStackTo(stack, 0, upgradeEnd, false)) return ItemStack.EMPTY;
            } else if (TurretRemoteItem.isBatteryItem(stack)) {
                if (!moveItemStackTo(stack, upgradeEnd, batteryEnd, false)) return ItemStack.EMPTY;
            } else {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    /** Accepts only upgrades allowed in the remote (basic range + any power). */
    private static final class RemoteUpgradeSlot extends Slot {
        RemoteUpgradeSlot(UpgradeBackedContainer c, int idx, int x, int y) { super(c, idx, x, y); }
        @Override public boolean mayPlace(ItemStack s) { return TurretUpgrade.allowedInRemote(s); }
        @Override public int getMaxStackSize() { return 1; }
    }

    /** Accepts only {@code refueled:battery}. */
    public static final class BatterySlot extends Slot {
        BatterySlot(BatteryBackedContainer c, int idx, int x, int y) { super(c, idx, x, y); }
        @Override public boolean mayPlace(ItemStack s) { return TurretRemoteItem.isBatteryItem(s); }
        @Override public int getMaxStackSize() { return 1; }
    }

    /** Same backing pattern as {@link UpgradeBackedContainer} but stored in
     *  the remote's {@code Batteries} NBT list. */
    public static final class BatteryBackedContainer extends SimpleContainer {
        private final Player player;
        private final InteractionHand hand;
        BatteryBackedContainer(Player player, InteractionHand hand) {
            super(BATTERY_SLOT_COUNT);
            this.player = player;
            this.hand = hand;
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof TurretRemoteItem) {
                java.util.List<ItemStack> loaded = TurretRemoteItem.getBatteries(stack);
                for (int i = 0; i < BATTERY_SLOT_COUNT && i < loaded.size(); i++) {
                    setItem(i, loaded.get(i));
                }
            }
        }
        @Override
        public void setChanged() {
            super.setChanged();
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof TurretRemoteItem)) return;
            java.util.List<ItemStack> batteries = new java.util.ArrayList<>(BATTERY_SLOT_COUNT);
            for (int i = 0; i < getContainerSize(); i++) batteries.add(getItem(i));
            TurretRemoteItem.setBatteries(stack, batteries);
            player.getInventory().setChanged();
        }
    }

    /** SimpleContainer that persists its contents into the remote's NBT on every
     *  mutation. The remote stack is looked up fresh each time via the player's
     *  held hand so the container stays pointed at the correct stack even if it
     *  moves slots. */
    private static final class UpgradeBackedContainer extends SimpleContainer {
        private final Player player;
        private final InteractionHand hand;
        UpgradeBackedContainer(Player player, InteractionHand hand) {
            super(UPGRADE_SLOT_COUNT);
            this.player = player;
            this.hand = hand;
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof TurretRemoteItem) {
                List<ItemStack> loaded = TurretRemoteItem.getUpgrades(stack);
                for (int i = 0; i < UPGRADE_SLOT_COUNT && i < loaded.size(); i++) {
                    setItem(i, loaded.get(i));
                }
            }
        }
        @Override
        public void setChanged() {
            super.setChanged();
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof TurretRemoteItem)) return;
            List<ItemStack> upgrades = new ArrayList<>(UPGRADE_SLOT_COUNT);
            for (int i = 0; i < getContainerSize(); i++) upgrades.add(getItem(i));
            TurretRemoteItem.setUpgrades(stack, upgrades);
            player.getInventory().setChanged();
        }
    }
}
