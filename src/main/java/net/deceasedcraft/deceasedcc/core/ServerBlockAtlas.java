package net.deceasedcraft.deceasedcc.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side shadow of the client's {@code BlockColorAtlas}. Clients send
 * their generated atlas via {@code AtlasSyncPacket} on login. The server
 * stores it here so {@code hologramSetFromScan(..., { useBlockAtlas =
 * true })} can look up per-block colors when packing scan palettes.
 *
 * <p>Shared across all dimensions / players. First client to log in with an
 * atlas populates the map; subsequent logins from other clients refresh it
 * (entries with the same block id get overwritten — harmless if two
 * clients agree). Cleared on server shutdown.</p>
 */
public final class ServerBlockAtlas {
    private ServerBlockAtlas() {}

    private static final Map<String, Integer> COLORS = new ConcurrentHashMap<>();

    public static void accept(Map<String, Integer> entries) {
        COLORS.putAll(entries);
    }

    public static Integer get(String blockId) {
        return COLORS.get(blockId);
    }

    public static int size() { return COLORS.size(); }

    public static void clear() { COLORS.clear(); }
}
