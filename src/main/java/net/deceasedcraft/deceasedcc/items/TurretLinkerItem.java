package net.deceasedcraft.deceasedcc.items;

import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.TurretNetworkControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Right-click a Turret Mount to stash its pos on the item, then right-click a
 * Turret Network Controller to link the mount to the controller. Pos limits
 * are enforced by the controller against {@code turret.networkRange}.
 */
public class TurretLinkerItem extends Item {
    private static final String NBT_POS = "linkedTurretPos";

    public TurretLinkerItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockPos pos = ctx.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);
        ItemStack stack = ctx.getItemInHand();
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;

        if (be instanceof TurretMountBlockEntity) {
            CompoundTag tag = stack.getOrCreateTag();
            tag.putLong(NBT_POS, pos.asLong());
            player.displayClientMessage(Component.literal("Turret stored: " + pos), true);
            return InteractionResult.CONSUME;
        }

        if (be instanceof TurretNetworkControllerBlockEntity controller) {
            CompoundTag tag = stack.getTag();
            if (tag == null || !tag.contains(NBT_POS)) {
                player.displayClientMessage(Component.literal("No turret stored — right-click a Turret Mount first."), true);
                return InteractionResult.CONSUME;
            }
            BlockPos turretPos = BlockPos.of(tag.getLong(NBT_POS));
            if (controller.linkTurret(turretPos)) {
                player.displayClientMessage(Component.literal("Linked " + turretPos + " to this controller."), true);
                tag.remove(NBT_POS);
            } else {
                player.displayClientMessage(Component.literal("Link failed: out of range or no turret at stored pos."), true);
            }
            return InteractionResult.CONSUME;
        }

        return InteractionResult.PASS;
    }
}
