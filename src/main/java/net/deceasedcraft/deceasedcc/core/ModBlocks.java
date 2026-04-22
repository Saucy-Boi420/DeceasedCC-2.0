package net.deceasedcraft.deceasedcc.core;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.blocks.AdvancedNetworkControllerBlock;
import net.deceasedcraft.deceasedcc.blocks.BasicTurretBlock;
import net.deceasedcraft.deceasedcc.blocks.CameraBlock;
import net.deceasedcraft.deceasedcc.blocks.ChunkRadarBlock;
import net.deceasedcraft.deceasedcc.blocks.EntityTrackerBlock;
import net.deceasedcraft.deceasedcc.blocks.HologramProjectorBlock;
import net.deceasedcraft.deceasedcc.blocks.TurretMountBlock;
import net.deceasedcraft.deceasedcc.blocks.TurretNetworkControllerBlock;
import net.deceasedcraft.deceasedcc.items.LinkableBlockItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, DeceasedCC.MODID);

    private static BlockBehaviour.Properties tech() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .strength(3.5F, 8.0F)
                .requiresCorrectToolForDrops();
    }

    // v2.0 Phase 7a — switched to registerLinkable so the creative-tab
    // tooltip advertises controller compatibility. Underlying block class
    // unchanged.
    public static final RegistryObject<Block> CHUNK_RADAR = registerLinkable("chunk_radar", () -> new ChunkRadarBlock(tech()));
    public static final RegistryObject<Block> ENTITY_TRACKER = registerLinkable("entity_tracker", () -> new EntityTrackerBlock(tech()));
    // v2.0 Phase 6a.1 — turret blocks get the "linkable" item wrapper so
    // their creative-tab tooltip says they can be linked to the Advanced
    // Network Controller. No change to Block or BE class — the wrapper is
    // purely on the BlockItem side. Byte-identical-to-v1.9 preserved.
    public static final RegistryObject<Block> TURRET_MOUNT = registerLinkable("turret_mount", () -> new TurretMountBlock(tech()));
    public static final RegistryObject<Block> BASIC_TURRET = registerLinkable("basic_turret", () -> new BasicTurretBlock(tech()));
    public static final RegistryObject<Block> TURRET_NETWORK_CONTROLLER = register("turret_network_controller", () -> new TurretNetworkControllerBlock(tech()));
    // v2.0 — these use noOcclusion() so adjacent blocks don't cull their faces
    // against our non-full-cube geometry (was causing xray through adjacent blocks).
    public static final RegistryObject<Block> HOLOGRAM_PROJECTOR = registerLinkable("hologram_projector", () -> new HologramProjectorBlock(tech().noOcclusion()));
    public static final RegistryObject<Block> CAMERA = registerLinkable("camera", () -> new CameraBlock(tech().noOcclusion()));
    // v2.0 Phase 6a.1 — the single unified wireless hub. Replaces the
    // split hologram_controller / camera_network_controller shipped
    // earlier today.
    public static final RegistryObject<Block> ADVANCED_NETWORK_CONTROLLER = register("advanced_network_controller", () -> new AdvancedNetworkControllerBlock(tech()));

    private static <B extends Block> RegistryObject<B> register(String name, Supplier<B> supplier) {
        RegistryObject<B> block = BLOCKS.register(name, supplier);
        ModItems.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    /** Variant of {@link #register} whose BlockItem is a
     *  {@link LinkableBlockItem} — adds a tooltip "Can connect to Advanced
     *  Network Controller" without modifying the underlying Block class.
     *  Used for turrets + cameras + projectors so the turret v1.9 Block
     *  classes stay byte-identical. */
    private static <B extends Block> RegistryObject<B> registerLinkable(String name, Supplier<B> supplier) {
        RegistryObject<B> block = BLOCKS.register(name, supplier);
        ModItems.ITEMS.register(name, () -> new LinkableBlockItem(block.get(), new Item.Properties()));
        return block;
    }

    private ModBlocks() {}
}
