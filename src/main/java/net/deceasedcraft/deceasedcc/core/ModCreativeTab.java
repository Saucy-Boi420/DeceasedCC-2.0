package net.deceasedcraft.deceasedcc.core;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTab {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DeceasedCC.MODID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.deceasedcc.main"))
                    .icon(() -> new ItemStack(ModBlocks.TURRET_MOUNT.get()))
                    .displayItems((params, output) -> {
                        // Linkable devices.
                        output.accept(ModBlocks.CHUNK_RADAR.get());
                        output.accept(ModBlocks.ENTITY_TRACKER.get());
                        output.accept(ModBlocks.BASIC_TURRET.get());
                        output.accept(ModBlocks.TURRET_MOUNT.get());
                        output.accept(ModBlocks.HOLOGRAM_PROJECTOR.get());
                        output.accept(ModBlocks.CAMERA.get());
                        // The unified network controller + linker.
                        output.accept(ModBlocks.ADVANCED_NETWORK_CONTROLLER.get());
                        output.accept(ModItems.LINKING_TOOL.get());
                        output.accept(ModItems.EXAMPLE_DISK.get());
                        // Shared recipe sub-component.
                        output.accept(ModItems.NETWORK_CHIP.get());
                        // Turret stuff kept in the codebase for v1.9
                        // byte-identity but hidden from the creative tab
                        // now that the advanced controller handles turrets
                        // (TURRET_NETWORK_CONTROLLER / TURRET_LINKER).
                        output.accept(ModItems.TURRET_REMOTE.get());
                        output.accept(ModItems.UPGRADE_FIRE_RATE_BASIC.get());
                        output.accept(ModItems.UPGRADE_FIRE_RATE_ADVANCED.get());
                        output.accept(ModItems.UPGRADE_TURN_SPEED_BASIC.get());
                        output.accept(ModItems.UPGRADE_TURN_SPEED_ADVANCED.get());
                        output.accept(ModItems.UPGRADE_RANGE_BASIC.get());
                        output.accept(ModItems.UPGRADE_RANGE_ADVANCED.get());
                        output.accept(ModItems.UPGRADE_POWER_BASIC.get());
                        output.accept(ModItems.UPGRADE_POWER_ADVANCED.get());
                    })
                    .build());

    private ModCreativeTab() {}
}
