package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.blocks.entity.CameraBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Phase 8.3 — C2S packet fired by the Camera Direction GUI on Apply.
 * Server validates the player is within reasonable reach of the camera
 * BE, then calls {@code CameraBlockEntity.setDirection(yaw, pitch)}.
 */
public record CameraSetDirectionPacket(BlockPos pos, float yaw, float pitch) {

    public static void encode(CameraSetDirectionPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeFloat(p.yaw);
        buf.writeFloat(p.pitch);
    }

    public static CameraSetDirectionPacket decode(FriendlyByteBuf buf) {
        return new CameraSetDirectionPacket(buf.readBlockPos(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(CameraSetDirectionPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            ServerPlayer player = c.getSender();
            if (player == null) return;
            // Reach check: player must be within 8 blocks of the camera.
            double dx = player.getX() - (p.pos.getX() + 0.5);
            double dy = player.getY() - (p.pos.getY() + 0.5);
            double dz = player.getZ() - (p.pos.getZ() + 0.5);
            if (dx*dx + dy*dy + dz*dz > 64.0) return;
            BlockEntity be = player.level().getBlockEntity(p.pos);
            if (be instanceof CameraBlockEntity cam) {
                float pitch = Math.max(-90f, Math.min(90f, p.pitch));
                // Normalize yaw to (-180, 180].
                float yaw = ((p.yaw % 360f) + 540f) % 360f - 180f;
                cam.setDirection(yaw, pitch);
            }
        });
        c.setPacketHandled(true);
    }
}
