package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.blocks.entity.BasicTurretBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Lightweight turret sync — yaw + pitch + sentry-active flag. Vanilla
 * BlockEntity sync via {@code sendBlockUpdated} skips BE data when the
 * block state is unchanged, so this packet handles the live updates.
 */
public record TurretRotationPacket(BlockPos pos, float yaw, float pitch, boolean sentry) {
    public static void encode(TurretRotationPacket p, FriendlyByteBuf buf) {
        buf.writeBlockPos(p.pos);
        buf.writeFloat(p.yaw);
        buf.writeFloat(p.pitch);
        buf.writeBoolean(p.sentry);
    }

    public static TurretRotationPacket decode(FriendlyByteBuf buf) {
        return new TurretRotationPacket(buf.readBlockPos(), buf.readFloat(), buf.readFloat(), buf.readBoolean());
    }

    public static void handle(TurretRotationPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> applyOnClient(p)));
        c.setPacketHandled(true);
    }

    private static void applyOnClient(TurretRotationPacket p) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        long tick = mc.level.getGameTime();
        var be = mc.level.getBlockEntity(p.pos);
        if (be instanceof TurretMountBlockEntity tm) {
            tm.state.yawDeg = p.yaw;
            tm.state.pitchDeg = p.pitch;
            tm.state.sentryActive = p.sentry;
            tm.rotationLerp.push(p.yaw, p.pitch, tick);
        } else if (be instanceof BasicTurretBlockEntity bt) {
            bt.state.yawDeg = p.yaw;
            bt.state.pitchDeg = p.pitch;
            bt.state.sentryActive = p.sentry;
            bt.rotationLerp.push(p.yaw, p.pitch, tick);
        }
    }
}
