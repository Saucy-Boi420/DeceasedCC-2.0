package net.deceasedcraft.deceasedcc.core;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.blocks.entity.AdvancedNetworkControllerBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.BasicTurretBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.CameraBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.ChunkRadarBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.EntityTrackerBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.HologramProjectorBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.TurretNetworkControllerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, DeceasedCC.MODID);

    public static final RegistryObject<BlockEntityType<ChunkRadarBlockEntity>> CHUNK_RADAR =
            BLOCK_ENTITIES.register("chunk_radar",
                    () -> BlockEntityType.Builder.of(ChunkRadarBlockEntity::new, ModBlocks.CHUNK_RADAR.get()).build(null));

    public static final RegistryObject<BlockEntityType<EntityTrackerBlockEntity>> ENTITY_TRACKER =
            BLOCK_ENTITIES.register("entity_tracker",
                    () -> BlockEntityType.Builder.of(EntityTrackerBlockEntity::new, ModBlocks.ENTITY_TRACKER.get()).build(null));

    public static final RegistryObject<BlockEntityType<TurretMountBlockEntity>> TURRET_MOUNT =
            BLOCK_ENTITIES.register("turret_mount",
                    () -> BlockEntityType.Builder.of(TurretMountBlockEntity::new, ModBlocks.TURRET_MOUNT.get()).build(null));

    public static final RegistryObject<BlockEntityType<BasicTurretBlockEntity>> BASIC_TURRET =
            BLOCK_ENTITIES.register("basic_turret",
                    () -> BlockEntityType.Builder.of(BasicTurretBlockEntity::new, ModBlocks.BASIC_TURRET.get()).build(null));

    public static final RegistryObject<BlockEntityType<TurretNetworkControllerBlockEntity>> TURRET_NETWORK_CONTROLLER =
            BLOCK_ENTITIES.register("turret_network_controller",
                    () -> BlockEntityType.Builder.of(TurretNetworkControllerBlockEntity::new, ModBlocks.TURRET_NETWORK_CONTROLLER.get()).build(null));

    public static final RegistryObject<BlockEntityType<HologramProjectorBlockEntity>> HOLOGRAM_PROJECTOR =
            BLOCK_ENTITIES.register("hologram_projector",
                    () -> BlockEntityType.Builder.of(HologramProjectorBlockEntity::new, ModBlocks.HOLOGRAM_PROJECTOR.get()).build(null));

    public static final RegistryObject<BlockEntityType<CameraBlockEntity>> CAMERA =
            BLOCK_ENTITIES.register("camera",
                    () -> BlockEntityType.Builder.of(CameraBlockEntity::new, ModBlocks.CAMERA.get()).build(null));

    // v2.0 Phase 6a.1 — the single unified wireless hub. Replaces the
    // split hologram_controller / camera_network_controller.
    public static final RegistryObject<BlockEntityType<AdvancedNetworkControllerBlockEntity>> ADVANCED_NETWORK_CONTROLLER =
            BLOCK_ENTITIES.register("advanced_network_controller",
                    () -> BlockEntityType.Builder.of(AdvancedNetworkControllerBlockEntity::new, ModBlocks.ADVANCED_NETWORK_CONTROLLER.get()).build(null));

    private ModBlockEntities() {}
}
