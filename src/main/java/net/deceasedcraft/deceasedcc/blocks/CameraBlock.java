package net.deceasedcraft.deceasedcc.blocks;

import net.deceasedcraft.deceasedcc.blocks.entity.CameraBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Surveillance Camera block — attaches to whichever face the player clicked.
 *
 * <p>Placement rules:
 * <ul>
 *   <li>Click the top of a block → floor mount; lens aims 180° from the
 *       placer's horizontal look (so it stares back at them).</li>
 *   <li>Click the bottom of a block → ceiling mount; same lens aim rule.</li>
 *   <li>Click a side → wall mount; lens points in the clicked-face direction
 *       (straight out from the wall).</li>
 * </ul>
 *
 * <p>Unlike the Projector, the Camera DOES pop off (and drop its item) when
 * its support block is removed — standard torch/lever behaviour.
 *
 * <p>The initial yaw/pitch is stored on the {@link CameraBlockEntity} and
 * persists across world reload. Live pitch/yaw re-aim (peripheral API,
 * GUI sliders) is deferred to Phase 6b; Phase 1 just seeds the aim so the
 * stub API returns sensible data.
 */
public class CameraBlock extends BaseEntityBlock {
    public static final EnumProperty<AttachFace> FACE    = BlockStateProperties.ATTACH_FACE;
    public static final DirectionProperty         FACING = BlockStateProperties.HORIZONTAL_FACING;

    // Camera body occupies roughly the centre of the block; leave a bit of
    // clearance around the geometry so collision doesn't feel boxy. Shapes
    // key off FACE (+ FACING for WALL mounts).
    private static final VoxelShape SHAPE_FLOOR    = Block.box(3, 0, 3, 13, 14, 13);
    private static final VoxelShape SHAPE_CEILING  = Block.box(3, 2, 3, 13, 16, 13);
    // Wall shapes: mount plate hugs the wall (south face when FACING=NORTH,
    // etc.) and body/lens extend outward to the opposite side.
    private static final VoxelShape SHAPE_WALL_N   = Block.box(3, 3, 0,  13, 13, 16); // mount on S, body extends to N
    private static final VoxelShape SHAPE_WALL_S   = Block.box(3, 3, 0,  13, 13, 16); // mount on N, body extends to S
    private static final VoxelShape SHAPE_WALL_E   = Block.box(0, 3, 3,  16, 13, 13); // mount on W, body extends to E
    private static final VoxelShape SHAPE_WALL_W   = Block.box(0, 3, 3,  16, 13, 13); // mount on E, body extends to W

    public CameraBlock(Properties props) {
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
        Direction clickedFace = ctx.getClickedFace();
        Direction horizBack = ctx.getHorizontalDirection().getOpposite();

        if (clickedFace == Direction.UP) {
            return this.defaultBlockState()
                    .setValue(FACE, AttachFace.FLOOR)
                    .setValue(FACING, horizBack);
        }
        if (clickedFace == Direction.DOWN) {
            return this.defaultBlockState()
                    .setValue(FACE, AttachFace.CEILING)
                    .setValue(FACING, horizBack);
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

    // --- support-block tracking (pop off when the block we're mounted to is removed) ---

    private static BlockPos supportPos(BlockPos pos, BlockState state) {
        AttachFace face = state.getValue(FACE);
        if (face == AttachFace.FLOOR)   return pos.below();
        if (face == AttachFace.CEILING) return pos.above();
        // WALL: mount is OPPOSITE the lens/facing direction.
        return pos.relative(state.getValue(FACING).getOpposite());
    }

    private static Direction supportFaceDir(BlockState state) {
        AttachFace face = state.getValue(FACE);
        if (face == AttachFace.FLOOR)   return Direction.UP;
        if (face == AttachFace.CEILING) return Direction.DOWN;
        return state.getValue(FACING);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos sp = supportPos(pos, state);
        Direction faceTowardCamera = supportFaceDir(state);
        return level.getBlockState(sp).isFaceSturdy(level, sp, faceTowardCamera);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                   LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!state.canSurvive(level, pos)) {
            if (level instanceof Level lvl && !lvl.isClientSide) {
                // Drop the item BEFORE the state flips to AIR — otherwise the
                // loot table has nothing to drop from.
                Block.dropResources(state, lvl, pos);
            }
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    // --- initial aim from placer (look back at them) ---

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || placer == null) return;
        if (level.getBlockEntity(pos) instanceof CameraBlockEntity cam) {
            // Aim directly at the placer's eye from the camera block centre.
            // The old "flip placer yaw 180°" trick only worked at pitch=0 —
            // if the player was looking down to place a camera on the floor,
            // flipping by 180° gave a tilt-up that didn't match their actual
            // eye position. atan2 against the delta vector points at the
            // eye regardless of player orientation.
            Vec3 camCenter = Vec3.atCenterOf(pos);
            Vec3 eye = placer.getEyePosition();
            double dx = eye.x - camCenter.x;
            double dy = eye.y - camCenter.y;
            double dz = eye.z - camCenter.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            // MC yaw convention: yaw=0 points +Z. A target at (dx, dz)
            // relative to the camera means the lens look vector should be
            // (dx, dz) normalised, which is yaw = atan2(-dx, dz).
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            // MC pitch: positive = looking down. To look UP at a higher
            // player (dy > 0), pitch should be negative.
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
            cam.setInitialAim(yaw, pitch);
        }
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CameraBlockEntity(pos, state);
    }

    /** Phase 8.3 — right-click with empty hand opens the direction GUI.
     *  Non-empty hand passes through (so you can still use tools normally). */
    @Override
    public net.minecraft.world.InteractionResult use(BlockState state,
                                                      Level level, BlockPos pos,
                                                      net.minecraft.world.entity.player.Player player,
                                                      net.minecraft.world.InteractionHand hand,
                                                      net.minecraft.world.phys.BlockHitResult hit) {
        if (!player.getItemInHand(hand).isEmpty()) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        if (level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof CameraBlockEntity cam) {
                openDirectionScreen(pos, cam.getYawDeg(), cam.getPitchDeg());
            }
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Isolated in a helper so the client-only Screen class isn't referenced
     *  from the block class at load time (dedicated servers don't have it). */
    private static void openDirectionScreen(BlockPos pos, float yaw, float pitch) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new net.deceasedcraft.deceasedcc.client.gui.CameraDirectionScreen(
                        pos, yaw, pitch));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
