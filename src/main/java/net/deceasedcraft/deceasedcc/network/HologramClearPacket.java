package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.client.HologramClientCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record HologramClearPacket(BlockPos pos) {

    public static void encode(HologramClearPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
    }

    public static HologramClearPacket decode(FriendlyByteBuf buf) {
        return new HologramClearPacket(buf.readBlockPos());
    }

    public static void handle(HologramClearPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> HologramClientCache.acceptClear(p)));
        c.setPacketHandled(true);
    }
}
