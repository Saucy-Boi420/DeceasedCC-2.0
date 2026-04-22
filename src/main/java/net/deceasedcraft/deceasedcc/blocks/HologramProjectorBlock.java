package net.deceasedcraft.deceasedcc.blocks;

import net.deceasedcraft.deceasedcc.blocks.entity.HologramProjectorBlockEntity;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Hologram Projector block — attaches to whichever face the player clicked.
 *
 * <p>Placement rules:
 * <ul>
 *   <li>Click the top of a block, or shift-right-click anywhere → floor mount
 *       (bottom-down regardless of clicked face).</li>
 *   <li>Click the bottom of a block → ceiling mount.</li>
 *   <li>Click a side → wall mount; emitter points away from the wall.</li>
 * </ul>
 *
 * <p>Unlike the Camera, the Projector does NOT pop off when its support block
 * is removed — the hologram emitter is considered self-supporting once placed.
 */
public class HologramProjectorBlock extends BaseEntityBlock {
    public static final EnumProperty<AttachFace> FACE     = BlockStateProperties.ATTACH_FACE;
    public static final DirectionProperty         FACING  = BlockStateProperties.HORIZONTAL_FACING;

    // Shapes per (FACE, FACING) combo. Projector is rotationally symmetric
    // around its emitter axis, so WALL shapes vary only by which side face
    // the mount is glued to; FLOOR/CEILING shapes are identical regardless
    // of HORIZONTAL_FACING.
    private static final VoxelShape SHAPE_FLOOR    = Block.box(0, 0, 0, 16, 13, 16);
    private static final VoxelShape SHAPE_CEILING  = Block.box(0, 3, 0, 16, 16, 16);
    private static final VoxelShape SHAPE_WALL_N   = Block.box(0, 0, 3, 16, 16, 16); // mount on S, extends N
    private static final VoxelShape SHAPE_WALL_S   = Block.box(0, 0, 0, 16, 16, 13); // mount on N, extends S
    private static final VoxelShape SHAPE_WALL_E   = Block.box(3, 0, 0, 16, 16, 16); // mount on W, extends E
    private static final VoxelShape SHAPE_WALL_W   = Block.box(0, 0, 0, 13, 16, 16); // mount on E, extends W

    public HologramProjectorBlock(Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Player player = ctx.getPlayer();
        boolean forceFloor = player != null && player.isSecondaryUseActive();
        Direction clickedFace = ctx.getClickedFace();
        // HORIZONTAL_FACING for floor/ceiling placements is irrelevant visually
        // (projector is symmetric) but we still store it for consistency with
        // the Camera block's placement rules.
        Direction horiz = ctx.getHorizontalDirection().getOpposite();

        if (forceFloor || clickedFace == Direction.UP) {
            return this.defaultBlockState()
                    .setValue(FACE, AttachFace.FLOOR)
                    .setValue(FACING, horiz);
        }
        if (clickedFace == Direction.DOWN) {
            return this.defaultBlockState()
                    .setValue(FACE, AttachFace.CEILING)
                    .setValue(FACING, horiz);
        }
        return this.defaultBlockState()
                .setValue(FACE, AttachFace.WALL)
                .setValue(FACING, clickedFace);
    }

    private static VoxelShape shapeFor(BlockState state) {
        AttachFace face = state.getValue(FACE);
        if (face == AttachFace.FLOOR)   return SHAPE_FLOOR;
        if (face == AttachFace.CEILING) return SHAPE_CEILING;
        return switch (state.getValue(FACING)) {
            case NORTH -> SHAPE_WALL_N;
            case SOUTH -> SHAPE_WALL_S;
            case EAST  -> SHAPE_WALL_E;
            case WEST  -> SHAPE_WALL_W;
            default    -> SHAPE_FLOOR;
        };
    }

    @Override public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return shapeFor(s); }
    @Override public VoxelShape getCollisionShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return shapeFor(s); }
    @Override public VoxelShape getOcclusionShape(BlockState s, BlockGetter l, BlockPos p) { return shapeFor(s); }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HologramProjectorBlockEntity(pos, state);
    }

    /** Phase 8.2 — server-side ticker wires the BE's heartbeat check so
     *  live-camera mode auto-clears when the Lua script stops calling
     *  loadFromCamera2D (terminated / world unload / error). Without this,
     *  a terminated script leaves clients stuck capturing every frame. */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                    BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModBlockEntities.HOLOGRAM_PROJECTOR.get(),
                HologramProjectorBlockEntity::serverTick);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
