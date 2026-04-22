package net.deceasedcraft.deceasedcc.client;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = DeceasedCC.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                    ModBlockEntities.TURRET_MOUNT.get(), TurretMountBER::new);
            net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                    ModBlockEntities.BASIC_TURRET.get(), BasicTurretBER::new);
            // v2.0 Phase 6c.1 — dynamic camera head swivel renderer.
            net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                    ModBlockEntities.CAMERA.get(), CameraBER::new);
            net.minecraft.client.gui.screens.MenuScreens.register(
                    net.deceasedcraft.deceasedcc.core.ModMenus.TURRET_MOUNT.get(),
                    TurretMountScreen::new);
            net.minecraft.client.gui.screens.MenuScreens.register(
                    net.deceasedcraft.deceasedcc.core.ModMenus.BASIC_TURRET.get(),
                    BasicTurretScreen::new);
            net.minecraft.client.gui.screens.MenuScreens.register(
                    net.deceasedcraft.deceasedcc.core.ModMenus.TURRET_REMOTE.get(),
                    TurretRemoteScreen::new);
        });
    }

    /** Phase 6c.1 — tell the model-loader about block models that aren't
     *  tied to a blockstate variant (so the ModelManager bakes them and
     *  our BER can look them up via getModel). */
    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        event.register(CameraBER.HEAD_MODEL_LOC);
        event.register(CameraBER.HEAD_CEILING_MODEL_LOC);
    }

    /** Every Mob needs a registered EntityRenderer or EntityRenderDispatcher
     *  NPEs the first time it tries to cull-check the entity. */
    @SubscribeEvent
    public static void onEntityRenderers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(
                net.deceasedcraft.deceasedcc.core.ModEntities.TURRET_SHOOTER.get(),
                TurretShooterRenderer::new);
        event.registerEntityRenderer(
                net.deceasedcraft.deceasedcc.core.ModEntities.TURRET_CAMERA.get(),
                TurretCameraRenderer::new);
    }

    private ClientSetup() {}
}
