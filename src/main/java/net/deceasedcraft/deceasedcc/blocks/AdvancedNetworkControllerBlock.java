package net.deceasedcraft.deceasedcc.blocks;

import net.deceasedcraft.deceasedcc.blocks.entity.AdvancedNetworkControllerBlockEntity;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.deceasedcraft.deceasedcc.core.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Phase 6a.1 — the single unified wireless hub. Accepts any combination of
 * turrets / cameras / hologram projectors (and future wireless devices),
 * up to the configured cap at
 * {@link ModConfig#ADV_NETWORK_MAX_CONNECTIONS}.
 *
 * <p>Tooltip explicitly tells the player how the linking workflow works so
 * they don't need to guess after seeing the block in the creative tab.
 */
public class AdvancedNetworkControllerBlock extends BaseEntityBlock {
    public AdvancedNetworkControllerBlock(Properties props) {
        super(props);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AdvancedNetworkControllerBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        if (type != ModBlockEntities.ADVANCED_NETWORK_CONTROLLER.get()) return null;
        // Phase 6c — server-side tick drives the camera scan cadence + ring
        // buffer fill. Only the advanced controller's BE type needs it.
        BlockEntityTicker<AdvancedNetworkControllerBlockEntity> ticker = (l, p, s, be) -> {
            if (l instanceof ServerLevel sl) be.serverTick(sl);
        };
        return (BlockEntityTicker<T>) ticker;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable BlockGetter level,
                                 List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.deceasedcc.advanced_network_controller.usage")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.deceasedcc.advanced_network_controller.types")
                .withStyle(ChatFormatting.DARK_GRAY));
        int cap = ModConfig.ADV_NETWORK_MAX_CONNECTIONS.get();
        tooltip.add(Component.translatable(
                        "tooltip.deceasedcc.advanced_network_controller.capacity", cap)
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
