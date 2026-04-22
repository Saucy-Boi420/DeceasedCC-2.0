package net.deceasedcraft.deceasedcc;

import net.deceasedcraft.deceasedcc.core.Integrations;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.deceasedcraft.deceasedcc.core.ModBlocks;
import net.deceasedcraft.deceasedcc.core.ModConfig;
import net.deceasedcraft.deceasedcc.core.ModCreativeTab;
import net.deceasedcraft.deceasedcc.core.ModEntities;
import net.deceasedcraft.deceasedcc.core.ModItems;
import net.deceasedcraft.deceasedcc.core.ModMenus;
import net.deceasedcraft.deceasedcc.network.DeceasedNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(DeceasedCC.MODID)
public final class DeceasedCC {
    public static final String MODID = "deceasedcc";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DeceasedCC() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();

        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SERVER_SPEC, "deceasedcc-server.toml");
        ModLoadingContext.get().registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC, "deceasedcc-client.toml");

        ModBlocks.BLOCKS.register(bus);
        ModItems.ITEMS.register(bus);
        ModBlockEntities.BLOCK_ENTITIES.register(bus);
        ModCreativeTab.TABS.register(bus);
        ModMenus.MENUS.register(bus);
        ModEntities.ENTITIES.register(bus);

        bus.addListener((net.minecraftforge.event.entity.EntityAttributeCreationEvent event) ->
                event.put(ModEntities.TURRET_SHOOTER.get(),
                        net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity.createAttributes().build()));

        // Phase 8.3 auto-migration: bump pre-8.3 camera view-cap defaults.
        // Runs whenever configs load/reload (ModConfigEvent fires AFTER the
        // toml is read, so getters return real values).
        bus.addListener((ModConfigEvent.Loading event) -> {
            if (event.getConfig().getSpec() == ModConfig.SERVER_SPEC) {
                ModConfig.migratePhase83CameraCaps();
            }
        });
        bus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == ModConfig.SERVER_SPEC) {
                ModConfig.migratePhase83CameraCaps();
            }
        });

        bus.addListener(this::commonSetup);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            bus.addListener(this::clientSetup);
        }

        MinecraftForge.EVENT_BUS.register(this);
        Integrations.logDetectedMods();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(DeceasedNetwork::register);
        // Register the Example Disk as a CC media provider so it mounts at
        // /disk/ when inserted into a CC Disk Drive.
        event.enqueueWork(() -> dan200.computercraft.api.ComputerCraftAPI.registerMediaProvider(
                net.deceasedcraft.deceasedcc.items.ExampleDiskItem.PROVIDER));
        // Guard against third-party GunFireEvent handlers (e.g. shotsfired)
        // that blindly cast the shooter to Player. Cancels our non-Player
        // turret shots' post-shot event so those handlers skip.
        net.deceasedcraft.deceasedcc.integration.tacz.GunFireEventGuard.register();
        // Rewrite incoming TACZ-projectile damage from a turret shooter into
        // one of three randomized turret damage families (turret / controlled
        // / self) so death messages identify the kill source correctly.
        net.deceasedcraft.deceasedcc.integration.tacz.TurretDamageRewriter.register();
        // Drop the peripheral API reference into config/deceasedcc/ on a
        // worker thread so server startup stays free of disk I/O.
        net.deceasedcraft.deceasedcc.core.DocsExtractor.scheduleAsync(
                net.minecraftforge.fml.ModList.get().getModContainerById(MODID)
                        .map(c -> c.getModInfo().getVersion().toString())
                        .orElse("unknown"));
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // Handled by @EventBusSubscriber in client/ClientSetup.
    }
}
