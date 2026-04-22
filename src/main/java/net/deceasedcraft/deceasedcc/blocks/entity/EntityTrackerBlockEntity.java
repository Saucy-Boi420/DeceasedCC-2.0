package net.deceasedcraft.deceasedcc.blocks.entity;

import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.deceasedcraft.deceasedcc.peripherals.EntityTrackerPeripheral;
import net.deceasedcraft.deceasedcc.peripherals.PeripheralBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public class EntityTrackerBlockEntity extends PeripheralBlockEntity {
    private EntityTrackerPeripheral peripheralRef;
    private CompoundTag pendingWatchData;

    public EntityTrackerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ENTITY_TRACKER.get(), pos, state);
    }

    @Override
    protected IPeripheral createPeripheral() {
        peripheralRef = new EntityTrackerPeripheral(this);
        // Flush any pending watch data that was loaded before the peripheral
        // existed (load() runs before the first createPeripheral() call).
        if (pendingWatchData != null) {
            peripheralRef.loadWatches(pendingWatchData);
            pendingWatchData = null;
        }
        return peripheralRef;
    }

    public EntityTrackerPeripheral trackerPeripheral() {
        peripheral();
        return peripheralRef;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        // Ensure the peripheral is initialised so we save whatever's current,
        // not a stale pending copy.
        if (peripheralRef != null) peripheralRef.saveWatches(tag);
        else if (pendingWatchData != null) tag.merge(pendingWatchData);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (peripheralRef != null) peripheralRef.loadWatches(tag);
        else pendingWatchData = tag.copy();
    }

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState s, net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!(be instanceof EntityTrackerBlockEntity et)) return;
        et.trackerPeripheral().serverTick();
    }
}
