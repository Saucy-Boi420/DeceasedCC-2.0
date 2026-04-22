package net.deceasedcraft.deceasedcc.client;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.network.AtlasSyncPacket;
import net.deceasedcraft.deceasedcc.network.DeceasedNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;

/**
 * Drives the {@link BlockColorAtlas} lifecycle:
 *
 * <ol>
 *   <li>On the first client tick (when the block registries + bakery are
 *   ready), load the cached atlas or generate a fresh one.</li>
 *   <li>On every player login to a server, ship the atlas to the server
 *   via {@link AtlasSyncPacket}. Works for integrated singleplayer and
 *   dedicated servers alike.</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientAtlasHook {
    private ClientAtlasHook() {}

    private static boolean atlasReady = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent ev) {
        if (atlasReady) return;
        if (ev.phase != TickEvent.Phase.END) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        // Wait until the baked model manager has something for stone —
        // proxy for "textures + baked models are resolved".
        if (mc == null || mc.getModelManager() == null) return;
        try {
            var state = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            var m = mc.getBlockRenderer().getBlockModel(state);
            if (m == null) return;
            // Has a particle sprite only after the atlas loads.
            if (m.getParticleIcon() == null) return;
        } catch (Throwable t) { return; }
        atlasReady = true;
        try {
            BlockColorAtlas.loadOrGenerate();
        } catch (Throwable t) {
            DeceasedCC.LOGGER.warn("BlockColorAtlas init failed: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn ev) {
        if (BlockColorAtlas.COLORS.isEmpty()) return;
        DeceasedNetwork.CHANNEL.sendToServer(
                new AtlasSyncPacket(new HashMap<>(BlockColorAtlas.COLORS)));
    }
}
