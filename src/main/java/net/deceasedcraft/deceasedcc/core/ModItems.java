package net.deceasedcraft.deceasedcc.core;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.items.ExampleDiskItem;
import net.deceasedcraft.deceasedcc.items.LinkingToolItem;
import net.deceasedcraft.deceasedcc.items.TurretLinkerItem;
import net.deceasedcraft.deceasedcc.items.TurretRemoteItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, DeceasedCC.MODID);

    public static final RegistryObject<Item> TURRET_LINKER = ITEMS.register("turret_linker",
            () -> new TurretLinkerItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> TURRET_REMOTE = ITEMS.register("turret_remote",
            () -> new TurretRemoteItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    // v2.0 Phase 6a.1 — the single universal linker. Replaces
    // hologram_linker / camera_linker / turret_linker. Detects device
    // type at right-click and pairs with the Advanced Network Controller.
    public static final RegistryObject<Item> LINKING_TOOL = ITEMS.register("linking_tool",
            () -> new LinkingToolItem(new Item.Properties().stacksTo(1)));

    // DeceasedCC Example Disk — a CC-mountable floppy packed with Lua
    // examples, one per peripheral / feature. Insert into a CC Disk Drive
    // next to a computer; mounts at /disk/. See items/ExampleDiskItem.
    public static final RegistryObject<Item> EXAMPLE_DISK = ITEMS.register("example_disk",
            () -> new ExampleDiskItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    // --- Turret upgrade items -----------------------------------------------
    // Basic tier is +25% its stat; advanced tier is +50%. Additive stacking —
    // 2 basic fire-rate = +50% total, 3 advanced fire-rate = +150%.
    public static final RegistryObject<Item> UPGRADE_FIRE_RATE_BASIC = upgrade("upgrade_fire_rate_basic", false);
    public static final RegistryObject<Item> UPGRADE_FIRE_RATE_ADVANCED = upgrade("upgrade_fire_rate_advanced", true);
    public static final RegistryObject<Item> UPGRADE_TURN_SPEED_BASIC = upgrade("upgrade_turn_speed_basic", false);
    public static final RegistryObject<Item> UPGRADE_TURN_SPEED_ADVANCED = upgrade("upgrade_turn_speed_advanced", true);
    public static final RegistryObject<Item> UPGRADE_RANGE_BASIC = upgrade("upgrade_range_basic", false);
    public static final RegistryObject<Item> UPGRADE_RANGE_ADVANCED = upgrade("upgrade_range_advanced", true);
    public static final RegistryObject<Item> UPGRADE_POWER_BASIC = upgrade("upgrade_power_basic", false);
    public static final RegistryObject<Item> UPGRADE_POWER_ADVANCED = upgrade("upgrade_power_advanced", true);

    // Transitional (WIP) items — exist solely as Create sequenced_assembly
    // intermediates so each recipe shows its own unique visual while
    // progressing. Hidden from the creative tab.
    public static final RegistryObject<Item> WIP_FIRE_RATE_UPGRADE  = ITEMS.register("wip_fire_rate_upgrade",  () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> WIP_TURN_SPEED_UPGRADE = ITEMS.register("wip_turn_speed_upgrade", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> WIP_RANGE_UPGRADE      = ITEMS.register("wip_range_upgrade",      () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> WIP_POWER_UPGRADE      = ITEMS.register("wip_power_upgrade",      () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> WIP_TURRET_REMOTE      = ITEMS.register("wip_turret_remote",      () -> new Item(new Item.Properties().stacksTo(1)));
    // v1.9: WIP intermediates for the new full-mod sequenced_assembly rework.
    public static final RegistryObject<Item> WIP_BASIC_TURRET              = ITEMS.register("wip_basic_turret",              () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> WIP_TURRET_MOUNT              = ITEMS.register("wip_turret_mount",              () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> WIP_CHUNK_RADAR               = ITEMS.register("wip_chunk_radar",               () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> WIP_ENTITY_TRACKER            = ITEMS.register("wip_entity_tracker",            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> WIP_TURRET_LINKER             = ITEMS.register("wip_turret_linker",             () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> WIP_TURRET_NETWORK_CONTROLLER = ITEMS.register("wip_turret_network_controller", () -> new Item(new Item.Properties().stacksTo(1)));
    // v2.0 WIP intermediates for the new hologram/camera recipes.
    public static final RegistryObject<Item> WIP_HOLOGRAM_PROJECTOR = ITEMS.register("wip_hologram_projector", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> WIP_CAMERA             = ITEMS.register("wip_camera",             () -> new Item(new Item.Properties().stacksTo(1)));
    // Recipe-overhaul WIP for the ANC sequenced_assembly path.
    public static final RegistryObject<Item> WIP_ADVANCED_NETWORK_CONTROLLER = ITEMS.register("wip_advanced_network_controller", () -> new Item(new Item.Properties().stacksTo(1)));

    // Shared sub-component used across every networked device's recipe.
    // The "this device speaks the wireless protocol" chip.
    public static final RegistryObject<Item> NETWORK_CHIP = ITEMS.register("network_chip",
            () -> new Item(new Item.Properties()));

    private static RegistryObject<Item> upgrade(String id, boolean advanced) {
        return ITEMS.register(id, () -> new Item(new Item.Properties()
                .stacksTo(1)
                .rarity(advanced ? Rarity.RARE : Rarity.UNCOMMON)));
    }

    private ModItems() {}
}
