package net.deceasedcraft.deceasedcc.core;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.turrets.TurretCameraEntity;
import net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, DeceasedCC.MODID);

    public static final RegistryObject<EntityType<TurretShooterEntity>> TURRET_SHOOTER =
            ENTITIES.register("turret_shooter", () ->
                    EntityType.Builder.of(TurretShooterEntity::new, MobCategory.MISC)
                            .sized(0.25f, 0.25f)
                            .fireImmune()
                            .noSummon()
                            // Tracking range drives which clients can receive
                            // TACZ sound packets referencing this shooter.
                            // 16 chunks = ~256 blocks, plenty for gunshot earshot.
                            .clientTrackingRange(16)
                            .build("turret_shooter"));

    /** Client-local camera anchor for the Turret Remote's view mode. Never
     *  spawned server-side. */
    public static final RegistryObject<EntityType<TurretCameraEntity>> TURRET_CAMERA =
            ENTITIES.register("turret_camera", () ->
                    EntityType.Builder.<TurretCameraEntity>of(TurretCameraEntity::new, MobCategory.MISC)
                            .sized(0.1f, 0.1f)
                            .fireImmune()
                            .noSummon()
                            .noSave()
                            .clientTrackingRange(0)
                            .build("turret_camera"));

    private ModEntities() {}
}
