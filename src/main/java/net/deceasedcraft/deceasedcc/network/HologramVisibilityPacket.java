package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.client.HologramClientCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record HologramVisibilityPacket(BlockPos pos, boolean visible) {

    public static void encode(HologramVisibilityPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeBoolean(p.visible);
    }

    public static HologramVisibilityPacket decode(FriendlyByteBuf buf) {
        return new HologramVisibilityPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(HologramVisibilityPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> HologramClientCache.acceptVisibility(p)));
        c.setPacketHandled(true);
    }
}
