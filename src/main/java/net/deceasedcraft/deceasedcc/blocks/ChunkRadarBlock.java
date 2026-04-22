package net.deceasedcraft.deceasedcc.blocks;

import net.deceasedcraft.deceasedcc.blocks.entity.ChunkRadarBlockEntity;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class ChunkRadarBlock extends BaseEntityBlock {
    public ChunkRadarBlock(Properties props) {
        super(props);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChunkRadarBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return type == ModBlockEntities.CHUNK_RADAR.get()
                ? (BlockEntityTicker<T>) (BlockEntityTicker<ChunkRadarBlockEntity>) ChunkRadarBlockEntity::serverTick
                : null;
    }
}
