package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.blocks.entity.HologramProjectorBlockEntity.HologramMode;
import net.deceasedcraft.deceasedcc.client.HologramClientCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C — carries the compressed ARGB image bytes for a hologram projector's
 * current 2D payload (Phase 2). Deflate compressed on the server, inflated
 * and uploaded to a DynamicTexture on the client.
 */
public record HologramDataPacket(BlockPos pos, HologramMode mode,
                                  int width, int height, byte[] compressed) {

    public static void encode(HologramDataPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeEnum(p.mode);
        buf.writeVarInt(p.width);
        buf.writeVarInt(p.height);
        buf.writeVarInt(p.compressed.length);
        buf.writeBytes(p.compressed);
    }

    public static HologramDataPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        HologramMode mode = buf.readEnum(HologramMode.class);
        int w = buf.readVarInt();
        int h = buf.readVarInt();
        int n = buf.readVarInt();
        byte[] bytes = new byte[n];
        buf.readBytes(bytes);
        return new HologramDataPacket(pos, mode, w, h, bytes);
    }

    public static void handle(HologramDataPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> HologramClientCache.acceptImage(p)));
        c.setPacketHandled(true);
    }
}
