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
 * S2C — carries a voxel grid payload for a hologram projector (Phase 3).
 *
 * <p>Wire format:
 * <pre>
 *   pos              : BlockPos
 *   mode             : HologramMode (enum ordinal) — MODE_3D_CULLED or MODE_3D_FULL
 *   sizeX, sizeY, sizeZ : VarInt each (≤64 per hologram.txt)
 *   paletteLen       : VarInt
 *   palette          : paletteLen × int (packed ARGB)
 *   compressedLen    : VarInt
 *   compressedIndexes: byte[] — Deflate-compressed index stream (1 byte per
 *                      voxel, row-major X→Y→Z; 0 = empty, 1..N = palette[0..N-1])
 * </pre>
 *
 * <p>Typical payload: 64×64×64 grid with a handful of palette entries
 * compresses from 256 KiB raw to ~10-40 KiB, which fits comfortably in a
 * single Forge simple-channel packet.
 */
public record HologramVoxelPacket(BlockPos pos, HologramMode mode,
                                   int sizeX, int sizeY, int sizeZ,
                                   int[] palette, byte[] compressedIndexes) {

    public static void encode(HologramVoxelPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeEnum(p.mode);
        buf.writeVarInt(p.sizeX);
        buf.writeVarInt(p.sizeY);
        buf.writeVarInt(p.sizeZ);
        buf.writeVarInt(p.palette.length);
        for (int c : p.palette) buf.writeInt(c);
        buf.writeVarInt(p.compressedIndexes.length);
        buf.writeBytes(p.compressedIndexes);
    }

    public static HologramVoxelPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        HologramMode mode = buf.readEnum(HologramMode.class);
        int sx = buf.readVarInt();
        int sy = buf.readVarInt();
        int sz = buf.readVarInt();
        int pLen = buf.readVarInt();
        int[] pal = new int[pLen];
        for (int i = 0; i < pLen; i++) pal[i] = buf.readInt();
        int cLen = buf.readVarInt();
        byte[] comp = new byte[cLen];
        buf.readBytes(comp);
        return new HologramVoxelPacket(pos, mode, sx, sy, sz, pal, comp);
    }

    public static void handle(HologramVoxelPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> HologramClientCache.acceptVoxel(p)));
        c.setPacketHandled(true);
    }
}
