package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class DeceasedNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(DeceasedCC.MODID, "main"))
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .networkProtocolVersion(() -> PROTOCOL)
            .simpleChannel();

    private static int nextId = 0;

    public static void register() {
        // Turret rotation (server → client) — yaw/pitch + sentry flag sync.
        CHANNEL.messageBuilder(TurretRotationPacket.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TurretRotationPacket::encode)
                .decoder(TurretRotationPacket::decode)
                .consumerMainThread(TurretRotationPacket::handle)
                .add();
        // Turret Remote rename (client → server).
        CHANNEL.messageBuilder(TurretRemoteRenamePacket.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER)
                .encoder(TurretRemoteRenamePacket::encode)
                .decoder(TurretRemoteRenamePacket::decode)
                .consumerMainThread(TurretRemoteRenamePacket::handle)
                .add();

        // Wireless control — Phase D
        CHANNEL.messageBuilder(TurretControlPackets.Enter.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER)
                .encoder(TurretControlPackets.Enter::encode)
                .decoder(TurretControlPackets.Enter::decode)
                .consumerMainThread(TurretControlPackets.Enter::handle)
                .add();
        CHANNEL.messageBuilder(TurretControlPackets.Confirm.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TurretControlPackets.Confirm::encode)
                .decoder(TurretControlPackets.Confirm::decode)
                .consumerMainThread(TurretControlPackets.Confirm::handle)
                .add();
        CHANNEL.messageBuilder(TurretControlPackets.Release.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER)
                .encoder(TurretControlPackets.Release::encode)
                .decoder(TurretControlPackets.Release::decode)
                .consumerMainThread(TurretControlPackets.Release::handle)
                .add();
        CHANNEL.messageBuilder(TurretControlPackets.ForceExit.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TurretControlPackets.ForceExit::encode)
                .decoder(TurretControlPackets.ForceExit::decode)
                .consumerMainThread(TurretControlPackets.ForceExit::handle)
                .add();
        CHANNEL.messageBuilder(TurretControlPackets.Aim.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER)
                .encoder(TurretControlPackets.Aim::encode)
                .decoder(TurretControlPackets.Aim::decode)
                .consumerMainThread(TurretControlPackets.Aim::handle)
                .add();
        CHANNEL.messageBuilder(TurretControlPackets.Fire.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER)
                .encoder(TurretControlPackets.Fire::encode)
                .decoder(TurretControlPackets.Fire::decode)
                .consumerMainThread(TurretControlPackets.Fire::handle)
                .add();

        // Phase 2 — Hologram projector S2C packets (content, transform,
        // visibility, clear). Content is the big one (up to 128×128 ARGB
        // Deflate-compressed); the others are a few bytes each.
        CHANNEL.messageBuilder(HologramDataPacket.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HologramDataPacket::encode)
                .decoder(HologramDataPacket::decode)
                .consumerMainThread(HologramDataPacket::handle)
                .add();
        CHANNEL.messageBuilder(HologramVisibilityPacket.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HologramVisibilityPacket::encode)
                .decoder(HologramVisibilityPacket::decode)
                .consumerMainThread(HologramVisibilityPacket::handle)
                .add();
        CHANNEL.messageBuilder(HologramTransformPacket.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HologramTransformPacket::encode)
                .decoder(HologramTransformPacket::decode)
                .consumerMainThread(HologramTransformPacket::handle)
                .add();
        CHANNEL.messageBuilder(HologramClearPacket.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HologramClearPacket::encode)
                .decoder(HologramClearPacket::decode)
                .consumerMainThread(HologramClearPacket::handle)
                .add();

        // Phase 3 — voxel grid payload (Deflate-compressed indexes + palette).
        CHANNEL.messageBuilder(HologramVoxelPacket.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HologramVoxelPacket::encode)
                .decoder(HologramVoxelPacket::decode)
                .consumerMainThread(HologramVoxelPacket::handle)
                .add();

        // Phase 5 — entity-marker overlay (MARKERS-only or COMPOSITE on top
        // of a voxel grid).
        CHANNEL.messageBuilder(HologramMarkersPacket.class, nextId++, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HologramMarkersPacket::encode)
                .decoder(HologramMarkersPacket::decode)
                .consumerMainThread(HologramMarkersPacket::handle)
                .add();

        // Phase 8.3 — Camera snapshot round-trip. Server asks one client
        // to capture (S2C), client returns the compressed ARGB (C2S).
        CHANNEL.messageBuilder(CameraSnapshotRequestPacket.class, nextId++,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CameraSnapshotRequestPacket::encode)
                .decoder(CameraSnapshotRequestPacket::decode)
                .consumerMainThread(CameraSnapshotRequestPacket::handle)
                .add();
        CHANNEL.messageBuilder(CameraSnapshotResponsePacket.class, nextId++,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER)
                .encoder(CameraSnapshotResponsePacket::encode)
                .decoder(CameraSnapshotResponsePacket::decode)
                .consumerMainThread(CameraSnapshotResponsePacket::handle)
                .add();

        // Phase 8.3 — Camera direction GUI (client → server apply).
        CHANNEL.messageBuilder(CameraSetDirectionPacket.class, nextId++,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER)
                .encoder(CameraSetDirectionPacket::encode)
                .decoder(CameraSetDirectionPacket::decode)
                .consumerMainThread(CameraSetDirectionPacket::handle)
                .add();

        // Block color atlas — client generates on first load and sends to
        // server for use by hologramSetFromScan(useBlockAtlas=true).
        CHANNEL.messageBuilder(AtlasSyncPacket.class, nextId++,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER)
                .encoder(AtlasSyncPacket::encode)
                .decoder(AtlasSyncPacket::decode)
                .consumerMainThread(AtlasSyncPacket::handle)
                .add();
    }

    private DeceasedNetwork() {}
}
