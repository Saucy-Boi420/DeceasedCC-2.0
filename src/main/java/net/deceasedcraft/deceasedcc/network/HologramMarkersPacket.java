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
 * S2C — carries the entity-marker overlay for a hologram projector.
 *
 * <p>Wire format per marker: 3 × float pos, 1 byte shape, 1 int argb,
 * 3 × float scale (x/y/z), 2 × float rotation (yawDeg, pitchDeg) = 29 bytes.
 * 256-marker cap → ~7.5 KiB packet.
 */
public record HologramMarkersPacket(
        BlockPos pos,
        HologramMode mode,
        int sizeX, int sizeY, int sizeZ,
        int count,
        float[] xs, float[] ys, float[] zs,
        byte[] shapes,
        int[] colors,
        float[] scaleXs, float[] scaleYs, float[] scaleZs,
        float[] yaws, float[] pitches) {

    public static void encode(HologramMarkersPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeEnum(p.mode);
        buf.writeVarInt(p.sizeX);
        buf.writeVarInt(p.sizeY);
        buf.writeVarInt(p.sizeZ);
        buf.writeVarInt(p.count);
        for (int i = 0; i < p.count; i++) {
            buf.writeFloat(p.xs[i]);
            buf.writeFloat(p.ys[i]);
            buf.writeFloat(p.zs[i]);
            buf.writeByte(p.shapes[i]);
            buf.writeInt(p.colors[i]);
            buf.writeFloat(p.scaleXs[i]);
            buf.writeFloat(p.scaleYs[i]);
            buf.writeFloat(p.scaleZs[i]);
            buf.writeFloat(p.yaws[i]);
            buf.writeFloat(p.pitches[i]);
        }
    }

    public static HologramMarkersPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        HologramMode mode = buf.readEnum(HologramMode.class);
        int sx = buf.readVarInt();
        int sy = buf.readVarInt();
        int sz = buf.readVarInt();
        int count = buf.readVarInt();
        float[] xs = new float[count];
        float[] ys = new float[count];
        float[] zs = new float[count];
        byte[] shapes = new byte[count];
        int[] colors = new int[count];
        float[] sxs = new float[count];
        float[] sys = new float[count];
        float[] szs = new float[count];
        float[] yaws = new float[count];
        float[] pitches = new float[count];
        for (int i = 0; i < count; i++) {
            xs[i]      = buf.readFloat();
            ys[i]      = buf.readFloat();
            zs[i]      = buf.readFloat();
            shapes[i]  = buf.readByte();
            colors[i]  = buf.readInt();
            sxs[i]     = buf.readFloat();
            sys[i]     = buf.readFloat();
            szs[i]     = buf.readFloat();
            yaws[i]    = buf.readFloat();
            pitches[i] = buf.readFloat();
        }
        return new HologramMarkersPacket(pos, mode, sx, sy, sz, count,
                xs, ys, zs, shapes, colors, sxs, sys, szs, yaws, pitches);
    }

    public static void handle(HologramMarkersPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> HologramClientCache.acceptMarkers(p)));
        c.setPacketHandled(true);
    }
}
