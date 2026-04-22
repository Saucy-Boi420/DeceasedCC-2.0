package net.deceasedcraft.deceasedcc.items;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Phase 6a.1 — BlockItem wrapper that adds a gray hover-text line to any
 * device block whose BE is recognised by
 * {@link net.deceasedcraft.deceasedcc.peripherals.AdvancedNetworkControllerPeripheral}
 * as linkable.
 *
 * <p>Applied via {@code ModBlocks.registerLinkable(...)}. Using a BlockItem
 * subclass instead of overriding {@code Block.appendHoverText} on each
 * block class means we can decorate v1.9 turret blocks without touching
 * the turret .class files (keeps the byte-identical-to-v1.9 guarantee
 * documented in the STATUS LOG).
 */
public class LinkableBlockItem extends BlockItem {
    public LinkableBlockItem(Block block, Properties props) {
        super(block, props);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                 List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.deceasedcc.linkable")
                .withStyle(ChatFormatting.GRAY));
    }
}
