package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.client.HologramClientCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record HologramTransformPacket(BlockPos pos,
                                       float sx, float sy, float sz,
                                       float ox, float oy, float oz,
                                       float yaw, float pitch, float roll,
                                       int colorARGB,
                                       float alphaMultiplier,
                                       boolean liveCameraMode,
                                       long pairedCameraPosPacked,
                                       int feedWidth, int feedHeight, float feedFov) {

    public static final long NO_PAIR = Long.MIN_VALUE;

    public static void encode(HologramTransformPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeFloat(p.sx); buf.writeFloat(p.sy); buf.writeFloat(p.sz);
        buf.writeFloat(p.ox); buf.writeFloat(p.oy); buf.writeFloat(p.oz);
        buf.writeFloat(p.yaw); buf.writeFloat(p.pitch); buf.writeFloat(p.roll);
        buf.writeInt(p.colorARGB);
        buf.writeFloat(p.alphaMultiplier);
        buf.writeBoolean(p.liveCameraMode);
        buf.writeLong(p.pairedCameraPosPacked);
        buf.writeVarInt(p.feedWidth);
        buf.writeVarInt(p.feedHeight);
        buf.writeFloat(p.feedFov);
    }

    public static HologramTransformPacket decode(FriendlyByteBuf buf) {
        return new HologramTransformPacket(
                buf.readBlockPos(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readInt(),
                buf.readFloat(),
                buf.readBoolean(),
                buf.readLong(),
                buf.readVarInt(), buf.readVarInt(), buf.readFloat());
    }

    public static void handle(HologramTransformPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> HologramClientCache.acceptTransform(p)));
        c.setPacketHandled(true);
    }
}
