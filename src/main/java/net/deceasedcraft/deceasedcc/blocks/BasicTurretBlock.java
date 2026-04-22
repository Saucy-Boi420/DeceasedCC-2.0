package net.deceasedcraft.deceasedcc.blocks;

import net.deceasedcraft.deceasedcc.blocks.entity.BasicTurretBlockEntity;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.deceasedcraft.deceasedcc.turrets.BasicTurretContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
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

public class BasicTurretBlock extends BaseEntityBlock {
    // Bottom-half slab — simple, clean silhouette. The gun sits above
    // the slab (see BasicTurretBER) so the cube model doesn't occlude it.
    private static final VoxelShape SHAPE = net.minecraft.world.level.block.Block.box(0, 0, 0, 16, 8, 16);

    public BasicTurretBlock(Properties props) { super(props); }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                             net.minecraft.world.entity.LivingEntity placer,
                             net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player p
                && level.getBlockEntity(pos) instanceof BasicTurretBlockEntity bt) {
            bt.state.placerUuid = p.getUUID();
            bt.setChanged();
        }
    }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new BasicTurretBlockEntity(pos, state); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public VoxelShape getShape(BlockState s, net.minecraft.world.level.BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
    @Override public VoxelShape getCollisionShape(BlockState s, net.minecraft.world.level.BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
    @Override public VoxelShape getOcclusionShape(BlockState s, net.minecraft.world.level.BlockGetter l, BlockPos p) { return SHAPE; }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof BasicTurretBlockEntity bt)) return InteractionResult.PASS;
        // Fallback ownership claim — see TurretMountBlock.use.
        if (bt.state.placerUuid == null) {
            bt.state.placerUuid = sp.getUUID();
            bt.setChanged();
        }
        MenuProvider provider = new SimpleMenuProvider(
                (id, inv, p) -> new net.deceasedcraft.deceasedcc.turrets.BasicTurretMenu(
                        id, inv, new BasicTurretContainer(bt), bt),
                Component.literal("Basic Turret"));
        NetworkHooks.openScreen(sp, provider, buf -> buf.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return type == ModBlockEntities.BASIC_TURRET.get()
                ? (BlockEntityTicker<T>) (BlockEntityTicker<BasicTurretBlockEntity>) BasicTurretBlockEntity::serverTick
                : null;
    }

    /** Drop the weapon + ammo when the turret is removed. The block itself is
     *  handled by the loot table. */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BasicTurretBlockEntity bt) {
                Containers.dropContents(level, pos, new net.deceasedcraft.deceasedcc.turrets.BasicTurretContainer(bt));
                if (bt.state.shooterUuid != null) {
                    net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge.forgetShooter(bt.state.shooterUuid);
                    if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                        var e = sl.getEntity(bt.state.shooterUuid);
                        if (e != null) e.discard();
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
