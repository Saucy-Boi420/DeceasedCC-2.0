package net.deceasedcraft.deceasedcc.peripherals;

import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.util.LazyOptional;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Common base for every block entity in this mod that exposes a ComputerCraft
 * peripheral. Subclasses just supply their {@link IPeripheral} instance via
 * {@link #createPeripheral()} — we take care of capability wiring, invalidation
 * on unload, and standard BlockEntity scaffolding.
 */
public abstract class PeripheralBlockEntity extends BlockEntity {
    // Looked up by name to avoid requiring CC:T classes at classload time for
    // non-peripheral code paths. ComputerCraftAPI.PERIPHERAL_CAPABILITY is the
    // canonical reference we want.
    public static final Capability<IPeripheral> PERIPHERAL_CAP =
            CapabilityManager.get(new CapabilityToken<>() {});

    private LazyOptional<IPeripheral> peripheralCap = LazyOptional.empty();
    private IPeripheral cachedPeripheral;

    protected PeripheralBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    protected abstract IPeripheral createPeripheral();

    public IPeripheral peripheral() {
        if (cachedPeripheral == null) cachedPeripheral = createPeripheral();
        return cachedPeripheral;
    }

    @Override
    @NotNull
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == PERIPHERAL_CAP) {
            if (!peripheralCap.isPresent()) {
                peripheralCap = LazyOptional.of(this::peripheral);
            }
            return peripheralCap.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        peripheralCap.invalidate();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        peripheralCap.invalidate();
    }
}
