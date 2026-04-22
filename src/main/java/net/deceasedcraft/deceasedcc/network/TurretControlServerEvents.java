package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.turrets.ControlledTurretRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server-side invariant enforcement for wireless-control sessions.
 * - Logout: silent release.
 * - Dimension change: force-exit with reason.
 * <p>
 * Deliberately does NOT cancel generic PlayerInteractEvents — 1.6.0's
 * blanket cancellation broke normal gameplay on the player's body. The
 * player's real hand can still interact; the camera just happens to be
 * elsewhere. If that causes odd interactions, we iterate.
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID)
public final class TurretControlServerEvents {
    private TurretControlServerEvents() {}

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent evt) {
        if (!(evt.getEntity() instanceof ServerPlayer sp)) return;
        if (!ControlledTurretRegistry.isControlling(sp.getUUID())) return;
        TurretControlPackets.releaseControl(sp.getUUID(), null, "");
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent evt) {
        if (!(evt.getEntity() instanceof ServerPlayer sp)) return;
        if (!ControlledTurretRegistry.isControlling(sp.getUUID())) return;
        TurretControlPackets.releaseControl(sp.getUUID(), sp, "Dimension changed");
    }
}
