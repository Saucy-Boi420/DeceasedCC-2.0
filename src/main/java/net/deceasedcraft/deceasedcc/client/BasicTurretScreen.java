package net.deceasedcraft.deceasedcc.client;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.turrets.BasicTurretMenu;
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
public class BasicTurretScreen extends AbstractContainerScreen<BasicTurretMenu> {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(DeceasedCC.MODID, "textures/gui/turret_basic.png");

    private Button filterButton;
    private Button enabledButton;

    public BasicTurretScreen(BasicTurretMenu menu, Inventory inv, Component title) {
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
        filterButton = Button.builder(filterLabel(), b -> cycleFilter())
                .bounds(leftPos + 8, topPos + 86, 78, 14)
                .build();
        addRenderableWidget(filterButton);
        enabledButton = Button.builder(enabledLabel(), b -> toggleEnabled())
                .bounds(leftPos + 90, topPos + 86, 78, 14)
                .build();
        addRenderableWidget(enabledButton);
    }

    private void cycleFilter() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null) return;
        mc.gameMode.handleInventoryButtonClick(menu.containerId, BasicTurretMenu.BUTTON_CYCLE_FILTER);
    }

    private void toggleEnabled() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null) return;
        mc.gameMode.handleInventoryButtonClick(menu.containerId, BasicTurretMenu.BUTTON_TOGGLE_ENABLED);
    }

    private Component filterLabel() {
        return Component.literal("Targets: " + menu.currentMode().label());
    }

    private Component enabledLabel() {
        return Component.literal(menu.enabled() ? "Enabled: ON" : "Enabled: OFF");
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (filterButton != null)  filterButton.setMessage(filterLabel());
        if (enabledButton != null) enabledButton.setMessage(enabledLabel());
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
