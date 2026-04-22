package net.deceasedcraft.deceasedcc.core;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.turrets.TurretMountMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, DeceasedCC.MODID);

    public static final RegistryObject<MenuType<TurretMountMenu>> TURRET_MOUNT = MENUS.register(
            "turret_mount",
            () -> IForgeMenuType.create(TurretMountMenu::clientFactory));

    public static final RegistryObject<MenuType<net.deceasedcraft.deceasedcc.turrets.BasicTurretMenu>> BASIC_TURRET = MENUS.register(
            "basic_turret",
            () -> IForgeMenuType.create(net.deceasedcraft.deceasedcc.turrets.BasicTurretMenu::clientFactory));

    public static final RegistryObject<MenuType<net.deceasedcraft.deceasedcc.turrets.TurretRemoteMenu>> TURRET_REMOTE = MENUS.register(
            "turret_remote",
            () -> IForgeMenuType.create(net.deceasedcraft.deceasedcc.turrets.TurretRemoteMenu::clientFactory));

    private ModMenus() {}
}
