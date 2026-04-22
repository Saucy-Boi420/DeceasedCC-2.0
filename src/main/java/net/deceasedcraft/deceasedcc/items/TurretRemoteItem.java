package net.deceasedcraft.deceasedcc.items;

import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.deceasedcraft.deceasedcc.turrets.TurretRemoteMenu;
import net.deceasedcraft.deceasedcc.turrets.TurretUpgrade;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.network.NetworkHooks;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Wireless Turret Remote — Phase C: binding NBT + 2 upgrade slots + Forge
 * Energy capability. Camera-view / wireless control logic is Phase D.
 *
 * Stack NBT layout:
 *   BoundTurrets: [ { dim, x, y, z, label }, ... ]        (Phase B)
 *   Upgrades:     [ {ItemStack tag}, {ItemStack tag} ]    (Phase C)
 *   Energy:       int (0..CAPACITY)                       (Phase C)
 */
public class TurretRemoteItem extends Item {
    // ---------- NBT keys
    private static final String NBT_BINDINGS = "BoundTurrets";
    private static final String NBT_DIM      = "dim";
    private static final String NBT_X        = "x";
    private static final String NBT_Y        = "y";
    private static final String NBT_Z        = "z";
    private static final String NBT_LABEL    = "label";
    private static final String NBT_UPGRADES  = "Upgrades";
    private static final String NBT_ENERGY    = "Energy";
    private static final String NBT_BATTERIES = "Batteries";

    // ---------- Config-controlled (hardcoded for Phase C)
    public static final int MAX_BINDINGS          = 16;
    public static final int UPGRADE_SLOT_COUNT    = 2;
    public static final int BATTERY_SLOT_COUNT    = 5;         // refueled:battery slots
    public static final int CAPACITY              = 20_000;    // base FE cap (no batteries)
    public static final int BATTERY_CAP_EACH      = 6_000;     // + per installed battery (matches refueled:battery native capacity)
    /** Fallback if the config is unavailable; see {@link net.deceasedcraft.deceasedcc.core.ModConfig#REMOTE_DRAIN_RATE_FE_PER_TICK}. */
    public static final int BASE_DRAIN_PER_TICK   = 10;        // FE/tick in camera mode
    public static final String BATTERY_ITEM_ID    = "refueled:battery";

    public TurretRemoteItem(Properties props) {
        super(props);
    }

    // =================================================== BINDINGS (Phase B)

    public record Binding(ResourceLocation dim, BlockPos pos, String label) {
        public CompoundTag toTag() {
            CompoundTag t = new CompoundTag();
            t.putString(NBT_DIM, dim.toString());
            t.putInt(NBT_X, pos.getX());
            t.putInt(NBT_Y, pos.getY());
            t.putInt(NBT_Z, pos.getZ());
            t.putString(NBT_LABEL, label);
            return t;
        }
        public static Binding fromTag(CompoundTag t) {
            return new Binding(
                    new ResourceLocation(t.getString(NBT_DIM)),
                    new BlockPos(t.getInt(NBT_X), t.getInt(NBT_Y), t.getInt(NBT_Z)),
                    t.getString(NBT_LABEL));
        }
    }

    public static List<Binding> getBindings(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(NBT_BINDINGS, Tag.TAG_LIST)) return List.of();
        ListTag list = tag.getList(NBT_BINDINGS, Tag.TAG_COMPOUND);
        List<Binding> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) out.add(Binding.fromTag(list.getCompound(i)));
        return out;
    }

    public static void setBindings(ItemStack stack, List<Binding> bindings) {
        CompoundTag tag = stack.getOrCreateTag();
        ListTag list = new ListTag();
        for (Binding b : bindings) list.add(b.toTag());
        tag.put(NBT_BINDINGS, list);
    }

    public static int indexOf(ItemStack stack, ResourceLocation dim, BlockPos pos) {
        List<Binding> bindings = getBindings(stack);
        for (int i = 0; i < bindings.size(); i++) {
            Binding b = bindings.get(i);
            if (b.dim().equals(dim) && b.pos().equals(pos)) return i;
        }
        return -1;
    }

    public static boolean addBinding(ItemStack stack, ResourceLocation dim, BlockPos pos, String label) {
        if (indexOf(stack, dim, pos) >= 0) return false;
        List<Binding> bindings = new ArrayList<>(getBindings(stack));
        if (bindings.size() >= MAX_BINDINGS) return false;
        bindings.add(new Binding(dim, pos, label));
        setBindings(stack, bindings);
        return true;
    }

    public static boolean removeBinding(ItemStack stack, int index) {
        List<Binding> bindings = new ArrayList<>(getBindings(stack));
        if (index < 0 || index >= bindings.size()) return false;
        bindings.remove(index);
        setBindings(stack, bindings);
        return true;
    }

    public static boolean setLabel(ItemStack stack, int index, String label) {
        List<Binding> bindings = new ArrayList<>(getBindings(stack));
        if (index < 0 || index >= bindings.size()) return false;
        Binding old = bindings.get(index);
        bindings.set(index, new Binding(old.dim(), old.pos(), label));
        setBindings(stack, bindings);
        return true;
    }

    /** Walks every binding and removes ones whose BlockEntity is no longer a
     *  TurretMountBlockEntity. Skips bindings in unloaded chunks (the turret
     *  may still exist) and unknown dimensions (mod possibly removed). Called
     *  whenever the remote GUI opens so the bound list stays current. */
    public static int pruneStaleBindings(ItemStack stack, net.minecraft.server.MinecraftServer server) {
        if (server == null) return 0;
        List<Binding> all = getBindings(stack);
        if (all.isEmpty()) return 0;
        List<Binding> kept = new ArrayList<>(all.size());
        for (Binding b : all) {
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key =
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, b.dim());
            net.minecraft.server.level.ServerLevel sl = server.getLevel(key);
            if (sl == null) {
                // Dimension missing (mod removed?). Keep — no info to act on.
                kept.add(b);
                continue;
            }
            if (!sl.isLoaded(b.pos())) {
                // Chunk unloaded — could still be a real turret. Keep.
                kept.add(b);
                continue;
            }
            BlockEntity be = sl.getBlockEntity(b.pos());
            if (be instanceof TurretMountBlockEntity) {
                kept.add(b);
            }
            // else: confirmed broken — drop.
        }
        int removed = all.size() - kept.size();
        if (removed > 0) setBindings(stack, kept);
        return removed;
    }

    // =================================================== UPGRADES (Phase C)

    public static List<ItemStack> getUpgrades(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>(UPGRADE_SLOT_COUNT);
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(NBT_UPGRADES, Tag.TAG_LIST)) {
            ListTag list = tag.getList(NBT_UPGRADES, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size() && i < UPGRADE_SLOT_COUNT; i++) {
                out.add(ItemStack.of(list.getCompound(i)));
            }
        }
        while (out.size() < UPGRADE_SLOT_COUNT) out.add(ItemStack.EMPTY);
        return out;
    }

    public static void setUpgrades(ItemStack stack, List<ItemStack> upgrades) {
        ListTag list = new ListTag();
        for (int i = 0; i < UPGRADE_SLOT_COUNT; i++) {
            CompoundTag t = new CompoundTag();
            (i < upgrades.size() ? upgrades.get(i) : ItemStack.EMPTY).save(t);
            list.add(t);
        }
        stack.getOrCreateTag().put(NBT_UPGRADES, list);
    }

    /** Base 10 FE/tick divided by (1 + sum of power upgrade percent boosts / 100).
     *  Basic power = +100% -> 2× multiplier -> 5 FE/tick. Advanced = +200% -> 3×.
     *  Additive stacking (matches existing turret upgrade math). */
    public static int getEffectiveDrainPerTick(ItemStack stack) {
        float multiplier = 1.0f;
        for (ItemStack u : getUpgrades(stack)) {
            TurretUpgrade up = TurretUpgrade.fromStack(u);
            if (up == null || up.stat != TurretUpgrade.Stat.POWER) continue;
            multiplier += up.percentBoost / 100f;
        }
        int base = net.deceasedcraft.deceasedcc.core.ModConfig.REMOTE_DRAIN_RATE_FE_PER_TICK.get();
        return Math.max(1, Math.round(base / multiplier));
    }

    /** Count installed basic range upgrades (for Phase E effective-range math). */
    public static int getRangeUpgradeCount(ItemStack stack) {
        int n = 0;
        for (ItemStack u : getUpgrades(stack)) {
            if (TurretUpgrade.fromStack(u) == TurretUpgrade.RANGE_BASIC) n++;
        }
        return n;
    }

    // =================================================== BATTERIES

    /** Installed `refueled:battery` stacks. Always returns a list of size
     *  {@link #BATTERY_SLOT_COUNT}, with {@code ItemStack.EMPTY} in empty slots. */
    public static List<ItemStack> getBatteries(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>(BATTERY_SLOT_COUNT);
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(NBT_BATTERIES, Tag.TAG_LIST)) {
            ListTag list = tag.getList(NBT_BATTERIES, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size() && i < BATTERY_SLOT_COUNT; i++) {
                out.add(ItemStack.of(list.getCompound(i)));
            }
        }
        while (out.size() < BATTERY_SLOT_COUNT) out.add(ItemStack.EMPTY);
        return out;
    }

    public static void setBatteries(ItemStack stack, List<ItemStack> batteries) {
        ListTag list = new ListTag();
        for (int i = 0; i < BATTERY_SLOT_COUNT; i++) {
            CompoundTag t = new CompoundTag();
            (i < batteries.size() ? batteries.get(i) : ItemStack.EMPTY).save(t);
            list.add(t);
        }
        stack.getOrCreateTag().put(NBT_BATTERIES, list);
    }

    public static boolean isBatteryItem(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem());
        return id != null && BATTERY_ITEM_ID.equals(id.toString());
    }

    /** Number of installed (non-empty) battery slots. */
    public static int getInstalledBatteryCount(ItemStack stack) {
        int n = 0;
        for (ItemStack b : getBatteries(stack)) if (!b.isEmpty()) n++;
        return n;
    }

    /** Max energy = base capacity + {@link #BATTERY_CAP_EACH} per installed
     *  battery. Called by the FE capability and the HUD / tooltip. */
    public static int getMaxEnergy(ItemStack stack) {
        return CAPACITY + BATTERY_CAP_EACH * getInstalledBatteryCount(stack);
    }

    /** Read a battery's stored FE via its IEnergyStorage capability. */
    private static int batteryStored(ItemStack battery) {
        if (battery.isEmpty()) return 0;
        return battery.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                .map(net.minecraftforge.energy.IEnergyStorage::getEnergyStored).orElse(0);
    }

    private static int batteryCapacity(ItemStack battery) {
        if (battery.isEmpty()) return 0;
        return battery.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                .map(net.minecraftforge.energy.IEnergyStorage::getMaxEnergyStored).orElse(BATTERY_CAP_EACH);
    }

    /** Total stored FE across base buffer + all installed batteries. */
    public static int getStoredEnergy(ItemStack stack) {
        int base = stack.hasTag() ? stack.getTag().getInt(NBT_ENERGY) : 0;
        for (ItemStack b : getBatteries(stack)) base += batteryStored(b);
        return base;
    }

    /** Mutate a battery's stored FE (goes via IEnergyStorage.receiveEnergy /
     *  extractEnergy, so the battery's own capacity is respected). */
    private static int batteryReceive(ItemStack battery, int amount, boolean simulate) {
        if (battery.isEmpty() || amount <= 0) return 0;
        return battery.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                .map(es -> es.receiveEnergy(amount, simulate)).orElse(0);
    }

    private static int batteryExtract(ItemStack battery, int amount, boolean simulate) {
        if (battery.isEmpty() || amount <= 0) return 0;
        return battery.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                .map(es -> es.extractEnergy(amount, simulate)).orElse(0);
    }

    /** Fill priority: base buffer first, then batteries left → right. Writes
     *  battery changes back to NBT. Returns how much was actually accepted. */
    public static int receiveEnergy(ItemStack stack, int amount, boolean simulate) {
        if (amount <= 0) return 0;
        int remaining = amount;
        int accepted = 0;

        // Base first
        int baseCur = stack.hasTag() ? stack.getTag().getInt(NBT_ENERGY) : 0;
        int baseSpace = Math.max(0, CAPACITY - baseCur);
        int toBase = Math.min(baseSpace, remaining);
        if (toBase > 0) {
            if (!simulate) stack.getOrCreateTag().putInt(NBT_ENERGY, baseCur + toBase);
            accepted += toBase;
            remaining -= toBase;
        }
        if (remaining <= 0) return accepted;

        // Batteries left → right
        List<ItemStack> batteries = getBatteries(stack);
        boolean mutated = false;
        for (int i = 0; i < batteries.size() && remaining > 0; i++) {
            ItemStack b = batteries.get(i);
            if (b.isEmpty()) continue;
            int took = batteryReceive(b, remaining, simulate);
            accepted += took;
            remaining -= took;
            if (took > 0 && !simulate) mutated = true;
        }
        if (mutated) setBatteries(stack, batteries);
        return accepted;
    }

    /** Drain priority (camera-mode drain): batteries right → left first, then
     *  base buffer. Returns actual FE drained. */
    public static int drainInternal(ItemStack stack, int amount) {
        if (amount <= 0) return 0;
        int remaining = amount;
        int taken = 0;

        // Batteries right → left
        List<ItemStack> batteries = getBatteries(stack);
        boolean mutated = false;
        for (int i = batteries.size() - 1; i >= 0 && remaining > 0; i--) {
            ItemStack b = batteries.get(i);
            if (b.isEmpty()) continue;
            int took = batteryExtract(b, remaining, false);
            taken += took;
            remaining -= took;
            if (took > 0) mutated = true;
        }
        if (mutated) setBatteries(stack, batteries);

        // Base buffer last
        if (remaining > 0) {
            int baseCur = stack.hasTag() ? stack.getTag().getInt(NBT_ENERGY) : 0;
            int fromBase = Math.min(baseCur, remaining);
            if (fromBase > 0) {
                stack.getOrCreateTag().putInt(NBT_ENERGY, baseCur - fromBase);
                taken += fromBase;
            }
        }
        return taken;
    }

    // =================================================== FE CAPABILITY (Phase C)

    @Override
    @Nullable
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new RemoteEnergyCapProvider(stack);
    }

    private static final class RemoteEnergyCapProvider implements ICapabilityProvider {
        private final LazyOptional<IEnergyStorage> energy;
        RemoteEnergyCapProvider(ItemStack stack) {
            this.energy = LazyOptional.of(() -> new StackEnergy(stack));
        }
        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
            return cap == ForgeCapabilities.ENERGY ? energy.cast() : LazyOptional.empty();
        }
    }

    private static final class StackEnergy implements IEnergyStorage {
        private final ItemStack stack;
        StackEnergy(ItemStack stack) { this.stack = stack; }

        @Override public int getEnergyStored() { return TurretRemoteItem.getStoredEnergy(stack); }
        @Override public int getMaxEnergyStored() { return TurretRemoteItem.getMaxEnergy(stack); }
        @Override public boolean canReceive() { return true; }
        @Override public boolean canExtract() { return false; } // drained internally only

        @Override public int receiveEnergy(int max, boolean simulate) {
            return TurretRemoteItem.receiveEnergy(stack, max, simulate);
        }
        @Override public int extractEnergy(int max, boolean simulate) {
            // External actors can't extract; internal drain bypasses the
            // capability and calls drainInternal() directly.
            return 0;
        }
    }

    // =================================================== RIGHT-CLICK
    //
    // Interaction rules:
    //   - Shift + right-click Advanced Turret Mount → bind / unbind
    //   - ANY other right-click (block or air, shift or not) → open GUI
    //
    // The block override uses onItemUseFirst so the remote takes precedence
    // over block.use() — players can't accidentally open the turret's own
    // GUI while holding the remote. Switch off the remote first to interact
    // with blocks normally.

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;

        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);

        // Advanced Turret Mount: shift = bind/unbind ONLY. Non-shift right-click
        // falls through to the block's use() so the turret's own GUI opens —
        // matches the behaviour of every other MenuProvider block.
        if (be instanceof TurretMountBlockEntity) {
            if (player.isShiftKeyDown()) {
                if (level.isClientSide) return InteractionResult.SUCCESS;
                return performBind(stack, player, level, pos);
            }
            return InteractionResult.PASS;
        }

        // Any other block: let the block's own use() run. Chests open, tech
        // chargers accept the remote via right-click-to-insert, furnaces
        // open, etc. If Block.use() doesn't consume the interaction, the
        // pipeline falls through to Item.useOn() which opens the remote GUI.
        return InteractionResult.PASS;
    }

    /** Runs when Block.use() was absent or returned PASS (plain blocks like
     *  stone, dirt). Opens the remote GUI. */
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) openMenu(sp, ctx.getItemInHand(), ctx.getHand());
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) openMenu(sp, stack, hand);
        return InteractionResultHolder.success(stack);
    }

    private static InteractionResult performBind(ItemStack stack, Player player, Level level, BlockPos pos) {
        ResourceLocation dim = level.dimension().location();
        int existing = indexOf(stack, dim, pos);
        if (existing >= 0) {
            removeBinding(stack, existing);
            player.displayClientMessage(
                    Component.literal("Unbound turret @ " + pos.toShortString())
                             .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.CONSUME;
        }
        int before = getBindings(stack).size();
        if (before >= MAX_BINDINGS) {
            player.displayClientMessage(
                    Component.literal("Remote full (" + MAX_BINDINGS + " bindings max)")
                             .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }
        String defaultLabel = String.format("Turret %d,%d,%d", pos.getX(), pos.getY(), pos.getZ());
        addBinding(stack, dim, pos, defaultLabel);
        player.displayClientMessage(
                Component.literal("Bound turret @ " + pos.toShortString()
                        + "  (" + (before + 1) + "/" + MAX_BINDINGS + ")")
                         .withStyle(ChatFormatting.AQUA), true);
        return InteractionResult.CONSUME;
    }

    private static void openMenu(ServerPlayer sp, ItemStack stack, InteractionHand hand) {
        int pruned = pruneStaleBindings(stack, sp.getServer());
        if (pruned > 0) {
            sp.displayClientMessage(
                    Component.literal("Removed " + pruned + " broken turret binding"
                                    + (pruned == 1 ? "" : "s"))
                            .withStyle(ChatFormatting.YELLOW), true);
        }
        NetworkHooks.openScreen(sp, new MenuProvider() {
            @Override public Component getDisplayName() { return stack.getHoverName(); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new TurretRemoteMenu(id, inv, hand);
            }
        }, (FriendlyByteBuf buf) -> buf.writeEnum(hand));
    }

    // =================================================== INVENTORY BAR
    //
    // Renders a small "durability" bar under the item in inventory + hotbar
    // showing the battery percentage. Green when full, fading to red as it
    // depletes. Bar hidden when full so a fully-charged remote looks clean.

    @Override
    public boolean isBarVisible(ItemStack stack) {
        int stored = getStoredEnergy(stack);
        int max    = getMaxEnergy(stack);
        return max > 0 && stored < max;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int stored = getStoredEnergy(stack);
        int max    = getMaxEnergy(stack);
        if (max <= 0) return 0;
        return Math.max(1, Math.round(13f * stored / max));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        int stored = getStoredEnergy(stack);
        int max    = getMaxEnergy(stack);
        float frac = max > 0 ? (float) stored / max : 0f;
        // Green (0x33CC33) at full → yellow → red (0xCC3333) at empty
        int r = Math.round(0x33 + (0xCC - 0x33) * (1f - frac));
        int g = Math.round(0xCC * frac);
        int b = 0x33;
        return (r << 16) | (g << 8) | b;
    }

    // =================================================== TOOLTIP

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        int bindings = getBindings(stack).size();
        int energy = getStoredEnergy(stack);
        int max    = getMaxEnergy(stack);
        int drain = getEffectiveDrainPerTick(stack);
        int batteries = getInstalledBatteryCount(stack);
        tooltip.add(Component.literal(bindings + "/" + MAX_BINDINGS + " bound")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Energy: " + energy + " / " + max + " FE"
                + (batteries > 0 ? "  (+" + batteries + " battery" + (batteries == 1 ? "" : "ies") + ")" : ""))
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Drain: " + drain + " FE/tick while viewing")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Shift+Right-click an Advanced Turret to bind/unbind")
                .withStyle(ChatFormatting.GRAY));
    }
}
