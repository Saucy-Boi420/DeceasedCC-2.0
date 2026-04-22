package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.client.camera.CameraSnapshotClient;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Phase 8.3 — S2C request asking ONE client to capture a camera-view
 * snapshot and return the ARGB bytes via {@link CameraSnapshotResponsePacket}.
 * Server picks the closest eligible client per snapshot; only that client
 * receives the packet.
 */
public record CameraSnapshotRequestPacket(int frameId,
                                           BlockPos cameraPos,
                                           float yaw, float pitch,
                                           int width, int height,
                                           float fov) {

    public static void encode(CameraSnapshotRequestPacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.frameId);
        buf.writeBlockPos(p.cameraPos);
        buf.writeFloat(p.yaw);
        buf.writeFloat(p.pitch);
        buf.writeVarInt(p.width);
        buf.writeVarInt(p.height);
        buf.writeFloat(p.fov);
    }

    public static CameraSnapshotRequestPacket decode(FriendlyByteBuf buf) {
        return new CameraSnapshotRequestPacket(
                buf.readVarInt(),
                buf.readBlockPos(),
                buf.readFloat(), buf.readFloat(),
                buf.readVarInt(), buf.readVarInt(),
                buf.readFloat());
    }

    public static void handle(CameraSnapshotRequestPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CameraSnapshotClient.onRequest(p)));
        c.setPacketHandled(true);
    }
}
