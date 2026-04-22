package net.deceasedcraft.deceasedcc.blocks.entity;

import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.core.ModBlockEntities;
import net.deceasedcraft.deceasedcc.core.ModConfig;
import net.deceasedcraft.deceasedcc.peripherals.PeripheralBlockEntity;
import net.deceasedcraft.deceasedcc.turrets.TurretNetworkPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TurretNetworkControllerBlockEntity extends PeripheralBlockEntity {
    private final List<BlockPos> linked = new ArrayList<>();
    private TurretNetworkPeripheral peripheralRef;

    public TurretNetworkControllerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.TURRET_NETWORK_CONTROLLER.get(), pos, blockState);
    }

    @Override
    protected IPeripheral createPeripheral() {
        peripheralRef = new TurretNetworkPeripheral(this);
        return peripheralRef;
    }

    public TurretNetworkPeripheral networkPeripheral() {
        peripheral();
        return peripheralRef;
    }

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState s, net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!(be instanceof TurretNetworkControllerBlockEntity tn)) return;
        tn.networkPeripheral().serverTick();
    }

    public List<BlockPos> getLinkedTurrets() {
        return Collections.unmodifiableList(linked);
    }

    public boolean linkTurret(BlockPos pos) {
        if (pos.distManhattan(getBlockPos()) > ModConfig.TURRET_NETWORK_RANGE.get()) return false;
        if (level == null) return false;
        if (!(level.getBlockEntity(pos) instanceof TurretMountBlockEntity)) return false;
        if (!linked.contains(pos)) linked.add(pos);
        setChanged();
        return true;
    }

    public void unlinkTurret(BlockPos pos) {
        if (linked.remove(pos)) setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ListTag list = new ListTag();
        for (BlockPos p : linked) {
            CompoundTag ct = new CompoundTag();
            ct.putLong("pos", p.asLong());
            list.add(ct);
        }
        tag.put("linked", list);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        linked.clear();
        ListTag list = tag.getList("linked", 10);
        for (int i = 0; i < list.size(); i++) {
            linked.add(BlockPos.of(list.getCompound(i).getLong("pos")));
        }
    }
}
