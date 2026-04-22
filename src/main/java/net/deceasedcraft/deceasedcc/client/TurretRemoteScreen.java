package net.deceasedcraft.deceasedcc.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.items.TurretRemoteItem;
import net.deceasedcraft.deceasedcc.network.DeceasedNetwork;
import net.deceasedcraft.deceasedcc.network.TurretControlPackets;
import net.deceasedcraft.deceasedcc.network.TurretRemoteRenamePacket;
import net.deceasedcraft.deceasedcc.turrets.TurretRemoteMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public class TurretRemoteScreen extends AbstractContainerScreen<TurretRemoteMenu> {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(DeceasedCC.MODID, "textures/gui/turret_remote.png");

    // Geometry baked into the GUI texture.
    private static final int LIST_X = 7,  LIST_Y = 17, LIST_W = 129, LIST_H = 49;
    private static final int BAR_X  = 154, BAR_Y  = 17, BAR_W  = 14,  BAR_H  = 49;
    private static final int UP_X   = 138, UP_Y   = 17, UP_W   = 14,  UP_H   = 14;
    private static final int DOWN_X = 138, DOWN_Y = 52, DOWN_W = 14,  DOWN_H = 14;
    private static final int ROW_HEIGHT = 10;
    private static final int VISIBLE_ROWS = LIST_H / ROW_HEIGHT; // = 4

    // Client-local UI state
    private int scrollOffset = 0;
    private int selectedIndex = -1;        // highlighted binding (Phase D will act on it)
    private int renameIndex   = -1;        // binding currently being renamed
    private EditBox renameBox;

    public TurretRemoteScreen(TurretRemoteMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = TurretRemoteMenu.GUI_HEIGHT;       // 206 (expanded for battery row)
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        // Rename edit box — added once, toggled visible during rename
        this.renameBox = new EditBox(
                this.font,
                this.leftPos + LIST_X + 4, this.topPos + LIST_Y + 3,
                LIST_W - 8, 12,
                Component.literal(""));
        this.renameBox.setMaxLength(TurretRemoteRenamePacket.MAX_LABEL_LENGTH);
        this.renameBox.setBordered(true);
        this.renameBox.setVisible(false);
        this.renameBox.setValue("");
        addRenderableWidget(this.renameBox);
    }

    // ================================================ RENDER

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui);
        super.render(gui, mouseX, mouseY, partialTick);
        renderTooltip(gui, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderTexture(0, TEXTURE);
        gui.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);

        ItemStack remote = menu.getRemoteStack();
        if (!(remote.getItem() instanceof TurretRemoteItem)) return;

        drawEnergyBar(gui, remote);
        drawBindingList(gui, remote, mouseX, mouseY);
        drawBatteryCharges(gui, remote);
    }

    /** Per-battery charge bar — 14 wide × 2 tall at the bottom of each battery
     *  slot, filled left-to-right based on that battery's stored FE. Only drawn
     *  for occupied slots; empty slots just show the electric-bolt hint from
     *  the GUI texture. */
    private void drawBatteryCharges(GuiGraphics gui, ItemStack remote) {
        java.util.List<ItemStack> batteries = TurretRemoteItem.getBatteries(remote);
        int[] slotX = { 43, 61, 79, 97, 115 };
        int slotY = 86;
        for (int i = 0; i < batteries.size() && i < slotX.length; i++) {
            ItemStack b = batteries.get(i);
            if (b.isEmpty()) continue;
            int stored = b.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                    .map(net.minecraftforge.energy.IEnergyStorage::getEnergyStored).orElse(0);
            int max = b.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                    .map(net.minecraftforge.energy.IEnergyStorage::getMaxEnergyStored)
                    .orElse(TurretRemoteItem.BATTERY_CAP_EACH);
            int barW = 14;
            int barH = 2;
            int bx = leftPos + slotX[i] + 1;
            int by = topPos + slotY + 16 - barH - 1;
            int fill = max > 0 ? Math.round((float) stored / max * barW) : 0;
            gui.fill(bx, by, bx + barW, by + barH, 0xFF202020);
            if (fill > 0) {
                gui.fill(bx, by, bx + fill, by + barH, 0xFF33CC33);
            }
        }
    }

    private void drawEnergyBar(GuiGraphics gui, ItemStack remote) {
        int stored = TurretRemoteItem.getStoredEnergy(remote);
        int max    = TurretRemoteItem.getMaxEnergy(remote);
        int innerX = leftPos + BAR_X + 1;
        int innerY = topPos  + BAR_Y + 1;
        int innerW = BAR_W - 1;
        int innerH = BAR_H - 1;
        int fillH  = max > 0 ? Math.round((float) stored / max * innerH) : 0;
        if (fillH <= 0) return;
        int yTop = innerY + (innerH - fillH);
        int yBot = innerY + innerH;
        gui.fill(innerX, yTop, innerX + innerW, yBot, 0xFF33CC33);
        gui.fill(innerX, yTop, innerX + innerW, yTop + 1, 0xFF77FF77);
    }

    private void drawBindingList(GuiGraphics gui, ItemStack remote, int mouseX, int mouseY) {
        List<TurretRemoteItem.Binding> bindings = TurretRemoteItem.getBindings(remote);
        int ox = leftPos + LIST_X + 3;
        int oy = topPos  + LIST_Y + 3;

        if (bindings.isEmpty()) {
            gui.drawString(font, "No bindings yet.", ox, oy, 0x606060, false);
            gui.drawString(font, "Shift+Right-click an",  ox, oy + 12, 0x808080, false);
            gui.drawString(font, "Advanced Turret to bind.", ox, oy + 22, 0x808080, false);
            return;
        }

        // Clamp scroll offset in case bindings shrank externally
        int maxOffset = Math.max(0, bindings.size() - VISIBLE_ROWS);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int bindingIdx = scrollOffset + i;
            if (bindingIdx >= bindings.size()) break;
            // Skip drawing the row whose label is currently being edited — the
            // EditBox widget overlaps this area and renders itself.
            if (bindingIdx == renameIndex && renameBox != null && renameBox.isVisible()) continue;

            TurretRemoteItem.Binding b = bindings.get(bindingIdx);
            int rowY = oy + i * ROW_HEIGHT;

            // Selection highlight
            if (bindingIdx == selectedIndex) {
                gui.fill(leftPos + LIST_X + 1, rowY - 1,
                        leftPos + LIST_X + LIST_W - 1, rowY + ROW_HEIGHT - 2,
                        0x5033CCCC);
            }

            String line = (bindingIdx + 1) + ". " + b.label();
            if (font.width(line) > LIST_W - 6) {
                line = font.plainSubstrByWidth(line, LIST_W - 12) + "\u2026";
            }
            gui.drawString(font, line, ox, rowY, 0x303030, false);
        }
    }

    // ================================================ INPUT

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        // Wheel scrolls the binding list when the mouse is over it.
        if (isOverList(mouseX, mouseY)) {
            if (scrollDelta > 0)      scrollUp();
            else if (scrollDelta < 0) scrollDown();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollDelta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Arrow buttons first (left-click only)
        if (button == 0) {
            if (isOverRect(mouseX, mouseY, UP_X, UP_Y, UP_W, UP_H)) {
                scrollUp();
                return true;
            }
            if (isOverRect(mouseX, mouseY, DOWN_X, DOWN_Y, DOWN_W, DOWN_H)) {
                scrollDown();
                return true;
            }
        }
        // Binding list rows
        if (isOverList(mouseX, mouseY)) {
            int rowIdx = rowAt(mouseX, mouseY);
            int bindingIdx = scrollOffset + rowIdx;
            List<TurretRemoteItem.Binding> bindings = TurretRemoteItem.getBindings(menu.getRemoteStack());
            if (bindingIdx >= 0 && bindingIdx < bindings.size()) {
                if (button == 0) {
                    // Left-click: take over the turret wirelessly. Server
                    // validates range / energy / dimension and responds with
                    // Confirm → client attaches the camera and closes this
                    // GUI.
                    selectedIndex = bindingIdx;
                    cancelRename();
                    DeceasedNetwork.CHANNEL.sendToServer(
                            new TurretControlPackets.Enter(menu.getHand(), bindingIdx));
                    return true;
                }
                if (button == 1) {
                    // Right-click: start renaming this binding
                    beginRename(bindingIdx, bindings.get(bindingIdx).label());
                    return true;
                }
            } else {
                // Clicked in the list area but no row — clear selection
                selectedIndex = -1;
                cancelRename();
            }
        } else {
            // Clicked outside list — if rename was open, commit it before
            // letting the super handle the click (so inventory clicks still
            // go through).
            if (renameIndex >= 0) commitRename();
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (renameIndex >= 0 && renameBox != null && renameBox.isFocused()) {
            // Enter (257) = commit, Escape (256) = cancel
            if (keyCode == 257 || keyCode == 335 /* numpad enter */) {
                commitRename();
                return true;
            }
            if (keyCode == 256) {
                cancelRename();
                return true;
            }
            // Let the EditBox handle text input
            return renameBox.keyPressed(keyCode, scanCode, modifiers)
                    || renameBox.canConsumeInput()
                    || super.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ================================================ HELPERS

    private boolean isOverList(double mouseX, double mouseY) {
        return isOverRect(mouseX, mouseY, LIST_X, LIST_Y, LIST_W, LIST_H);
    }

    private boolean isOverRect(double mouseX, double mouseY, int x, int y, int w, int h) {
        int relX = (int)(mouseX - leftPos);
        int relY = (int)(mouseY - topPos);
        return relX >= x && relX < x + w && relY >= y && relY < y + h;
    }

    private int rowAt(double mouseX, double mouseY) {
        int relY = (int)(mouseY - (topPos + LIST_Y + 3));
        return Math.max(0, relY / ROW_HEIGHT);
    }

    private void scrollUp() {
        if (scrollOffset > 0) {
            scrollOffset--;
            cancelRename();
        }
    }

    private void scrollDown() {
        int total = TurretRemoteItem.getBindings(menu.getRemoteStack()).size();
        int maxOffset = Math.max(0, total - VISIBLE_ROWS);
        if (scrollOffset < maxOffset) {
            scrollOffset++;
            cancelRename();
        }
    }

    private void beginRename(int bindingIdx, String currentLabel) {
        renameIndex = bindingIdx;
        int visualRow = bindingIdx - scrollOffset;
        if (visualRow < 0 || visualRow >= VISIBLE_ROWS) {
            // Scroll so the row is in view
            scrollOffset = Math.max(0, Math.min(bindingIdx,
                    TurretRemoteItem.getBindings(menu.getRemoteStack()).size() - VISIBLE_ROWS));
            visualRow = bindingIdx - scrollOffset;
        }
        renameBox.setX(this.leftPos + LIST_X + 2);
        renameBox.setY(this.topPos + LIST_Y + 3 + visualRow * ROW_HEIGHT - 1);
        renameBox.setWidth(LIST_W - 4);
        renameBox.setValue(currentLabel);
        renameBox.moveCursorToEnd();
        renameBox.setHighlightPos(0);
        renameBox.setVisible(true);
        this.setFocused(renameBox);
        renameBox.setFocused(true);
    }

    private void commitRename() {
        if (renameIndex < 0 || renameBox == null) return;
        String newLabel = renameBox.getValue().trim();
        if (newLabel.isEmpty()) {
            cancelRename();
            return;
        }
        DeceasedNetwork.CHANNEL.sendToServer(
                new TurretRemoteRenamePacket(menu.getHand(), renameIndex, newLabel));
        cancelRename();
    }

    private void cancelRename() {
        renameIndex = -1;
        if (renameBox != null) {
            renameBox.setVisible(false);
            renameBox.setFocused(false);
            this.setFocused(null);
        }
    }
}
