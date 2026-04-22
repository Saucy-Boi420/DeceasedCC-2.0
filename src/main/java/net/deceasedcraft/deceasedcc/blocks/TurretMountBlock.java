package net.deceasedcraft.deceasedcc.blocks;

import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.deceasedcraft.deceasedcc.turrets.TurretMountContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

public class TurretMountBlock extends BaseEntityBlock {
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                             net.minecraft.world.entity.LivingEntity placer,
                             net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player p
                && level.getBlockEntity(pos) instanceof TurretMountBlockEntity tm) {
            tm.state.placerUuid = p.getUUID();
            tm.setChanged();
        }
    }
    // Bottom-half slab — gun sits above it (see TurretMountBER).
    private static final VoxelShape SHAPE = net.minecraft.world.level.block.Block.box(0, 0, 0, 16, 8, 16);

    public TurretMountBlock(Properties props) { super(props); }

    @Override public VoxelShape getShape(BlockState s, net.minecraft.world.level.BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
    @Override public VoxelShape getCollisionShape(BlockState s, net.minecraft.world.level.BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
    @Override public VoxelShape getOcclusionShape(BlockState s, net.minecraft.world.level.BlockGetter l, BlockPos p) { return SHAPE; }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new TurretMountBlockEntity(pos, state); }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // Pass-through for the linking tool so the item's useOn can capture
        // this turret into the ANC; otherwise the GUI would always win.
        if (player.getItemInHand(hand).getItem()
                instanceof net.deceasedcraft.deceasedcc.items.LinkingToolItem) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof TurretMountBlockEntity tm)) return InteractionResult.PASS;
        // Fallback ownership claim: if this turret was placed before owner-
        // tracking existed (or via a non-Block-path like /setblock) it has
        // no placer. Claim it for whoever opens the GUI first.
        if (tm.state.placerUuid == null) {
            tm.state.placerUuid = sp.getUUID();
            tm.setChanged();
        }
        MenuProvider provider = new SimpleMenuProvider(
                (id, inv, p) -> new net.deceasedcraft.deceasedcc.turrets.TurretMountMenu(
                        id, inv, new TurretMountContainer(tm), tm),
                Component.literal("Advanced Turret Mount"));
        NetworkHooks.openScreen(sp, provider, buf -> buf.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }

    // MODEL (not ENTITYBLOCK_ANIMATED) so the block's cube model renders
    // normally; the BlockEntityRenderer layered on top only draws the hovering
    // gun, not the base block itself.
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return type == ModBlockEntities.TURRET_MOUNT.get() ? (BlockEntityTicker<T>) (BlockEntityTicker<TurretMountBlockEntity>) TurretMountBlockEntity::serverTick : null;
    }

    /** Drop the weapon + ammo when the turret is removed (broken, exploded,
     *  or replaced). The block itself is handled by the loot table. The
     *  block-type check prevents dropping contents on state-only changes. */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TurretMountBlockEntity tm) {
                Containers.dropContents(level, pos, new TurretMountContainer(tm));
                // Despawn the invisible shooter entity this turret owned.
                if (tm.state.shooterUuid != null) {
                    net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.forgetShooter(tm.state.shooterUuid);
                    if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                        var e = sl.getEntity(tm.state.shooterUuid);
                        if (e != null) e.discard();
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
