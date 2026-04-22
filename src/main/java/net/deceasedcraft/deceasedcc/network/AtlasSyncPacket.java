package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.core.ServerBlockAtlas;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Client → server handoff of the generated block-color atlas. Sent once
 * after the client has either loaded the cached atlas or finished
 * generating a fresh one. The server stores the entries in
 * {@link ServerBlockAtlas} so {@code hologramSetFromScan(..., {
 * useBlockAtlas = true })} can look them up.
 */
public record AtlasSyncPacket(Map<String, Integer> entries) {

    public static void encode(AtlasSyncPacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.entries.size());
        for (var e : p.entries.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeInt(e.getValue());
        }
    }

    public static AtlasSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, Integer> entries = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            String k = buf.readUtf();
            int v = buf.readInt();
            entries.put(k, v);
        }
        return new AtlasSyncPacket(entries);
    }

    public static void handle(AtlasSyncPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            ServerBlockAtlas.accept(p.entries);
            DeceasedCC.LOGGER.info("ServerBlockAtlas: accepted {} entries (total {})",
                    p.entries.size(), ServerBlockAtlas.size());
        });
        c.setPacketHandled(true);
    }
}
