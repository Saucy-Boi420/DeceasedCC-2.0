package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.deceasedcraft.deceasedcc.client.TurretControlClient;
import net.deceasedcraft.deceasedcc.integration.tacz.GunClassifier;
import net.deceasedcraft.deceasedcc.integration.tacz.TaczBridge;
import net.deceasedcraft.deceasedcc.items.TurretRemoteItem;
import net.deceasedcraft.deceasedcc.turrets.ControlledTurretRegistry;
import net.deceasedcraft.deceasedcc.turrets.TurretUpgrade;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Wireless turret control packets (Phase D).
 * <p>
 * C2S: Enter, Release, Aim, Fire
 * S2C: Confirm, ForceExit
 */
public final class TurretControlPackets {

    private TurretControlPackets() {}

    // ======================================================== Helpers

    private static int effectiveFireRateTicks(TurretMountBlockEntity tm) {
        float mult = TurretUpgrade.effectiveMultipliers(tm.state.upgradeSlots)
                .get(TurretUpgrade.Stat.FIRE_RATE);
        int base = TaczBridge.nativeFireRateTicks(tm.state.weapon);
        return Math.max(1, (int) Math.floor(base / mult));
    }

    public static TurretMountBlockEntity findControlledTurret(ServerPlayer player) {
        ControlledTurretRegistry.Entry e = ControlledTurretRegistry.get(player.getUUID());
        if (e == null) return null;
        ServerLevel sl = player.server.getLevel(
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, e.dim()));
        if (sl == null) return null;
        BlockEntity be = sl.getBlockEntity(e.pos());
        return be instanceof TurretMountBlockEntity tm ? tm : null;
    }

    /** Clear the player's control session. Always unregisters + clears
     *  {@code state.controllingPlayer}. Sends a ForceExit packet only if
     *  {@code notify} is non-null (server-initiated exits). */
    public static void releaseControl(UUID playerUuid, ServerPlayer notify, String reason) {
        ControlledTurretRegistry.Entry entry = ControlledTurretRegistry.get(playerUuid);
        ControlledTurretRegistry.unregister(playerUuid);
        if (entry != null) {
            net.minecraft.server.MinecraftServer server =
                    notify != null ? notify.server
                            : net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                ServerLevel sl = server.getLevel(
                        net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.DIMENSION, entry.dim()));
                if (sl != null) {
                    BlockEntity be = sl.getBlockEntity(entry.pos());
                    if (be instanceof TurretMountBlockEntity tm
                            && playerUuid.equals(tm.state.controllingPlayer)) {
                        tm.state.controllingPlayer = null;
                        tm.setChanged();
                    }
                }
            }
        }
        if (notify != null) {
            DeceasedNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> notify),
                    new ForceExit(reason == null ? "" : reason));
        }
    }

    // ======================================================== ENTER (C2S)
    public static final class Enter {
        public final InteractionHand hand;
        public final int bindingIndex;
        public Enter(InteractionHand hand, int bindingIndex) {
            this.hand = hand; this.bindingIndex = bindingIndex;
        }
        public static void encode(Enter p, FriendlyByteBuf buf) {
            buf.writeEnum(p.hand); buf.writeVarInt(p.bindingIndex);
        }
        public static Enter decode(FriendlyByteBuf buf) {
            return new Enter(buf.readEnum(InteractionHand.class), buf.readVarInt());
        }
        public static void handle(Enter p, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player == null) return;
                ItemStack remote = player.getItemInHand(p.hand);
                if (!(remote.getItem() instanceof TurretRemoteItem)) return;
                List<TurretRemoteItem.Binding> bindings = TurretRemoteItem.getBindings(remote);
                if (p.bindingIndex < 0 || p.bindingIndex >= bindings.size()) return;
                TurretRemoteItem.Binding b = bindings.get(p.bindingIndex);

                if (!player.level().dimension().location().equals(b.dim())) {
                    player.displayClientMessage(Component.literal("Turret is in another dimension")
                            .withStyle(ChatFormatting.RED), true);
                    return;
                }
                BlockEntity be = player.level().getBlockEntity(b.pos());
                if (!(be instanceof TurretMountBlockEntity tm)) {
                    player.displayClientMessage(Component.literal("Turret is offline")
                            .withStyle(ChatFormatting.RED), true);
                    return;
                }
                float rangeMult = 1.0f + 0.25f * TurretRemoteItem.getRangeUpgradeCount(remote);
                double maxRange = net.deceasedcraft.deceasedcc.core.ModConfig.REMOTE_BASE_RANGE_BLOCKS.get() * rangeMult;
                if (player.distanceToSqr(b.pos().getCenter()) > maxRange * maxRange) {
                    player.displayClientMessage(Component.literal("Out of range (" + (int) maxRange + "m)")
                            .withStyle(ChatFormatting.RED), true);
                    return;
                }
                if (TurretRemoteItem.getStoredEnergy(remote) <= 0) {
                    player.displayClientMessage(Component.literal("Battery dead")
                            .withStyle(ChatFormatting.RED), true);
                    return;
                }
                if (tm.state.controllingPlayer != null
                        && !tm.state.controllingPlayer.equals(player.getUUID())) {
                    player.displayClientMessage(Component.literal("Turret in use by another player")
                            .withStyle(ChatFormatting.RED), true);
                    return;
                }
                if (ControlledTurretRegistry.isControlling(player.getUUID())) {
                    releaseControl(player.getUUID(), null, "");
                }
                tm.state.controllingPlayer = player.getUUID();
                tm.setChanged();
                ControlledTurretRegistry.register(player.getUUID(), b.dim(), b.pos());
                player.closeContainer();
                // Confirm carries the turret's gun class so the client can
                // pick the right zoom ladder without a server round-trip.
                int gunClassOrdinal = GunClassifier.classOf(tm.state.weapon).ordinal();
                DeceasedNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new Confirm(tm.getBlockPos(), gunClassOrdinal));
            });
            ctx.setPacketHandled(true);
        }
    }

    // ======================================================== CONFIRM (S2C)
    public static final class Confirm {
        public final BlockPos pos;
        public final int gunClassOrdinal;
        public Confirm(BlockPos pos, int gunClassOrdinal) {
            this.pos = pos; this.gunClassOrdinal = gunClassOrdinal;
        }
        public static void encode(Confirm p, FriendlyByteBuf buf) {
            buf.writeBlockPos(p.pos); buf.writeVarInt(p.gunClassOrdinal);
        }
        public static Confirm decode(FriendlyByteBuf buf) {
            return new Confirm(buf.readBlockPos(), buf.readVarInt());
        }
        public static void handle(Confirm p, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> TurretControlClient.onConfirm(p.pos, p.gunClassOrdinal)));
            ctx.setPacketHandled(true);
        }
    }

    // ======================================================== RELEASE (C2S)
    public static final class Release {
        public static void encode(Release p, FriendlyByteBuf buf) { }
        public static Release decode(FriendlyByteBuf buf) { return new Release(); }
        public static void handle(Release p, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player == null) return;
                releaseControl(player.getUUID(), null, "");
            });
            ctx.setPacketHandled(true);
        }
    }

    // ======================================================== FORCE-EXIT (S2C)
    public static final class ForceExit {
        public final String reason;
        public ForceExit(String reason) { this.reason = reason == null ? "" : reason; }
        public static void encode(ForceExit p, FriendlyByteBuf buf) { buf.writeUtf(p.reason, 64); }
        public static ForceExit decode(FriendlyByteBuf buf) { return new ForceExit(buf.readUtf(64)); }
        public static void handle(ForceExit p, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> TurretControlClient.onForceExit(p.reason)));
            ctx.setPacketHandled(true);
        }
    }

    // ======================================================== AIM (C2S)
    public static final class Aim {
        public final float yaw, pitch;
        public Aim(float yaw, float pitch) { this.yaw = yaw; this.pitch = pitch; }
        public static void encode(Aim p, FriendlyByteBuf buf) {
            buf.writeFloat(p.yaw); buf.writeFloat(p.pitch);
        }
        public static Aim decode(FriendlyByteBuf buf) {
            return new Aim(buf.readFloat(), buf.readFloat());
        }
        public static void handle(Aim p, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player == null) return;
                TurretMountBlockEntity tm = findControlledTurret(player);
                if (tm == null) return;
                float yaw = ((p.yaw % 360f) + 360f) % 360f;
                float pitch = Math.max(-90f, Math.min(50f, p.pitch));
                tm.state.yawDeg = yaw;
                tm.state.pitchDeg = pitch;
                tm.setChanged();
            });
            ctx.setPacketHandled(true);
        }
    }

    // ======================================================== FIRE (C2S)
    public static final class Fire {
        public static void encode(Fire p, FriendlyByteBuf buf) { }
        public static Fire decode(FriendlyByteBuf buf) { return new Fire(); }
        public static void handle(Fire p, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player == null) return;
                TurretMountBlockEntity tm = findControlledTurret(player);
                if (tm == null) return;
                long now = tm.getLevel().getGameTime();
                int cooldown = effectiveFireRateTicks(tm);
                if (now - tm.state.lastFireGameTime < cooldown) return;
                // Only advance cooldown when the shot actually fires (not
                // aborted by friendly-fire check or inoperable gun).
                if (tm.fireThroughShooter()) {
                    tm.state.lastFireGameTime = now;
                }
            });
            ctx.setPacketHandled(true);
        }
    }
}
