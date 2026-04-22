package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.server.CameraSnapshotCoordinator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Phase 8.3 — C2S reply from the capturing client carrying the deflate-
 * compressed ARGB bytes. Server's {@link CameraSnapshotCoordinator}
 * matches by frameId and persists to ScanRegistry.
 *
 * <p>success=false means the client couldn't capture (no level loaded,
 * shader active, render-path busy). Server fires a "failed" Lua event
 * in that case.
 */
public record CameraSnapshotResponsePacket(int frameId,
                                            int width, int height,
                                            boolean success,
                                            byte[] compressedARGB,
                                            String errorMsg) {

    public static void encode(CameraSnapshotResponsePacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.frameId);
        buf.writeVarInt(p.width);
        buf.writeVarInt(p.height);
        buf.writeBoolean(p.success);
        if (p.success) {
            buf.writeByteArray(p.compressedARGB);
        } else {
            buf.writeUtf(p.errorMsg == null ? "" : p.errorMsg, 256);
        }
    }

    public static CameraSnapshotResponsePacket decode(FriendlyByteBuf buf) {
        int frameId = buf.readVarInt();
        int w = buf.readVarInt();
        int h = buf.readVarInt();
        boolean ok = buf.readBoolean();
        byte[] bytes = ok ? buf.readByteArray() : new byte[0];
        String err = ok ? "" : buf.readUtf(256);
        return new CameraSnapshotResponsePacket(frameId, w, h, ok, bytes, err);
    }

    public static void handle(CameraSnapshotResponsePacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> CameraSnapshotCoordinator.onResponse(p));
        c.setPacketHandled(true);
    }
}
