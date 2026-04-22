package net.deceasedcraft.deceasedcc.blocks.entity;

import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.deceasedcraft.deceasedcc.peripherals.ChunkRadarPeripheral;
import net.deceasedcraft.deceasedcc.peripherals.ChunkScanJob;
import net.deceasedcraft.deceasedcc.peripherals.PeripheralBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class ChunkRadarBlockEntity extends PeripheralBlockEntity {
    private ChunkRadarPeripheral peripheralRef;
    private ChunkScanJob activeJob;

    public ChunkRadarBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CHUNK_RADAR.get(), pos, state);
    }

    @Override
    protected IPeripheral createPeripheral() {
        peripheralRef = new ChunkRadarPeripheral(this);
        return peripheralRef;
    }

    public ChunkRadarPeripheral radarPeripheral() {
        peripheral();
        return peripheralRef;
    }

    public boolean hasActiveJob() { return activeJob != null && !activeJob.isDone(); }

    public ChunkScanJob startJob(ChunkScanJob job) {
        this.activeJob = job;
        return job;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BlockEntity be) {
        if (!(be instanceof ChunkRadarBlockEntity r)) return;
        r.stepActiveJob(level);
    }

    /** Advance this radar's active job by one step and fire the Lua event
     *  if it completes. Called both by this BE's own ticker AND by the
     *  controller's serverTick — the latter so radars in loaded-but-not-
     *  ticking chunks still make progress. Idempotent + safe to call
     *  multiple times per tick (budget bounds each call). */
    public void stepActiveJob(Level level) {
        if (activeJob == null || activeJob.isDone()) return;
        boolean finished = activeJob.step(level);
        if (finished) {
            ChunkScanJob j = activeJob;
            activeJob = null;
            radarPeripheral().fireJobComplete(j);
        }
    }
}
