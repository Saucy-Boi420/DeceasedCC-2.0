package net.deceasedcraft.deceasedcc.client;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.turrets.TurretMountMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class TurretMountScreen extends AbstractContainerScreen<TurretMountMenu> {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(DeceasedCC.MODID, "textures/gui/turret_advanced.png");

    private Button ccControlButton;
    private Button filterButton;
    private Button enabledButton;

    public TurretMountScreen(TurretMountMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 186;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        // Three buttons share the y=88 row:
        //   x=8..47   Enabled ON/OFF          (40 wide)
        //   x=50..97  CC: ON/OFF              (48 wide)
        //   x=100..165 Targets: <filter>      (66 wide, hidden when CC=ON)
        enabledButton = Button.builder(enabledLabel(), b -> toggleEnabled())
                .bounds(leftPos + 8, topPos + 88, 40, 14)
                .build();
        addRenderableWidget(enabledButton);

        ccControlButton = Button.builder(ccLabel(), b -> toggleCCControl())
                .bounds(leftPos + 50, topPos + 88, 48, 14)
                .build();
        addRenderableWidget(ccControlButton);

        filterButton = Button.builder(filterLabel(), b -> cycleFilter())
                .bounds(leftPos + 100, topPos + 88, 66, 14)
                .build();
        filterButton.visible = !menu.ccControlOn();
        addRenderableWidget(filterButton);
    }

    private void toggleCCControl() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null) return;
        mc.gameMode.handleInventoryButtonClick(menu.containerId, TurretMountMenu.BUTTON_TOGGLE_CC_CONTROL);
    }

    private void cycleFilter() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null) return;
        mc.gameMode.handleInventoryButtonClick(menu.containerId, TurretMountMenu.BUTTON_CYCLE_BASIC_FILTER);
    }

    private void toggleEnabled() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null) return;
        mc.gameMode.handleInventoryButtonClick(menu.containerId, TurretMountMenu.BUTTON_TOGGLE_ENABLED);
    }

    private Component ccLabel() {
        return Component.literal("CC: " + (menu.ccControlOn() ? "ON" : "OFF"));
    }

    private Component filterLabel() {
        return Component.literal("Targets: " + menu.basicFilter().label());
    }

    private Component enabledLabel() {
        return Component.literal(menu.enabled() ? "ON" : "OFF");
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (ccControlButton != null) ccControlButton.setMessage(ccLabel());
        if (enabledButton != null)   enabledButton.setMessage(enabledLabel());
        if (filterButton != null) {
            filterButton.setMessage(filterLabel());
            filterButton.visible = !menu.ccControlOn();
        }
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui);
        super.render(gui, mouseX, mouseY, partialTick);
        renderTooltip(gui, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        gui.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }
}
