package net.deceasedcraft.deceasedcc.integration.tacz;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;

/**
 * Reads the {@code AmmoId} NBT from a fired TACZ ammo stack, maps it to the
 * DeceasedCraft modpack's matching {@code deceasedcraft:bullet_casing_*}
 * item (registered via KubeJS in the pack's startup scripts), and pushes one
 * casing into any adjacent inventory. If no adjacent inventory accepts it —
 * or the pack isn't installed and the casing item doesn't resolve — the
 * casing is silently dropped (keeps the world clean and avoids spamming
 * item entities when someone runs the mod without the pack).
 */
public final class CasingEjector {
    private static final Map<String, String> AMMO_TO_CASING = Map.ofEntries(
            Map.entry("tacz:9mm",    "deceasedcraft:bullet_casing_9mm_round"),
            Map.entry("tacz:45acp",  "deceasedcraft:bullet_casing_45acp"),
            Map.entry("tacz:357mag", "deceasedcraft:bullet_casing_357magnum"),
            Map.entry("tacz:50ae",   "deceasedcraft:bullet_casing_ae50"),
            Map.entry("tacz:12g",    "deceasedcraft:bullet_casing_12g"),
            Map.entry("tacz:556x45", "deceasedcraft:bullet_casing_556x45"),
            Map.entry("tacz:57x28",  "deceasedcraft:bullet_casing_57x28"),
            Map.entry("tacz:762x39", "deceasedcraft:bullet_casing_762x39"),
            Map.entry("tacz:30_06",  "deceasedcraft:bullet_casing_30_06"),
            Map.entry("tacz:338",    "deceasedcraft:bullet_casing_338"),
            Map.entry("tacz:308",    "deceasedcraft:bullet_casing_308"),
            Map.entry("tacz:762x54", "deceasedcraft:bullet_casing_762x54"),
            Map.entry("tacz:50bmg",  "deceasedcraft:bullet_casing_50bmg"),
            Map.entry("tacz:40mm",   "deceasedcraft:bullet_casing_40mm"),
            Map.entry("tacz:37mm",   "deceasedcraft:bullet_casing_37mm")
    );

    private CasingEjector() {}

    public static void ejectFor(Level level, BlockPos turretPos, ItemStack ammoSnapshot) {
        if (level == null || level.isClientSide || ammoSnapshot.isEmpty()) return;
        String ammoId = resolveAmmoId(ammoSnapshot);
        if (ammoId == null) return;
        String casingId = AMMO_TO_CASING.get(ammoId);
        if (casingId == null) return;
        Item casingItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(casingId));
        if (casingItem == null) return;

        ItemStack casing = new ItemStack(casingItem);
        for (Direction dir : Direction.values()) {
            BlockEntity be = level.getBlockEntity(turretPos.relative(dir));
            if (be == null) continue;
            LazyOptional<IItemHandler> cap = be.getCapability(ForgeCapabilities.ITEM_HANDLER, dir.getOpposite());
            IItemHandler handler = cap.orElse(null);
            if (handler == null) continue;
            casing = ItemHandlerHelper.insertItemStacked(handler, casing, false);
            if (casing.isEmpty()) return;
        }
    }

    private static String resolveAmmoId(ItemStack ammo) {
        if (!ammo.hasTag()) return null;
        String raw = ammo.getTag().getString("AmmoId");
        return raw.isEmpty() ? null : raw;
    }
}
