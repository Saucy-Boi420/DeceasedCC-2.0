package net.deceasedcraft.deceasedcc.turrets;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side O(1) lookup: which player is wireless-controlling which
 * turret. Maintained in lock-step with {@code TurretState.controllingPlayer}
 * by the Enter / Release / ForceExit paths.
 *
 * Pure in-memory; not persisted. Server restart drops all sessions —
 * acceptable, since the camera is client-local anyway.
 */
public final class ControlledTurretRegistry {

    public record Entry(ResourceLocation dim, BlockPos pos) {}

    private static final Map<UUID, Entry> CONTROLLING = new ConcurrentHashMap<>();

    private ControlledTurretRegistry() {}

    public static void register(UUID player, ResourceLocation dim, BlockPos pos) {
        CONTROLLING.put(player, new Entry(dim, pos));
    }

    public static void unregister(UUID player) {
        CONTROLLING.remove(player);
    }

    public static Entry get(UUID player) {
        return CONTROLLING.get(player);
    }

    public static boolean isControlling(UUID player) {
        return CONTROLLING.containsKey(player);
    }

    public static Collection<BlockPos> snapshot() {
        java.util.List<BlockPos> out = new java.util.ArrayList<>(CONTROLLING.size());
        for (Entry e : CONTROLLING.values()) out.add(e.pos);
        return out;
    }
}
