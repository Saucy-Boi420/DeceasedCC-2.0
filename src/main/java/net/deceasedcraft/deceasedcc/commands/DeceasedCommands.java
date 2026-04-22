package net.deceasedcraft.deceasedcc.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.blocks.entity.BasicTurretBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.deceasedcraft.deceasedcc.core.TurretMetrics;
import net.deceasedcraft.deceasedcc.core.TurretRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * /deceasedcc command tree. Permission level 2 (op) for everything.
 *
 * Subcommands:
 *   /deceasedcc debug turrets [page]   list registered turrets in caller's dim
 *   /deceasedcc debug perf             dump last-minute scan/raycast/shot counters
 *   /deceasedcc reload                 informational; Forge auto-reloads on file change
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID)
public final class DeceasedCommands {
    private static final int ROWS_PER_PAGE = 20;

    private DeceasedCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent ev) {
        register(ev.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("deceasedcc")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("debug")
                    .then(Commands.literal("turrets")
                        .executes(ctx -> debugTurrets(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> debugTurrets(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "page")))))
                    .then(Commands.literal("perf")
                        .executes(ctx -> debugPerf(ctx.getSource()))))
                .then(Commands.literal("reload")
                    .executes(ctx -> reload(ctx.getSource())))
        );
    }

    // -------------------------------------------------------- debug turrets

    private static int debugTurrets(CommandSourceStack source, int page) {
        ServerLevel level = source.getLevel();
        ResourceKey<Level> dimKey = level.dimension();
        List<TurretRegistry.TurretNode> nodes = new ArrayList<>(TurretRegistry.snapshot(dimKey));
        // Sort by (x, z, y) for stable pagination as turrets get placed/removed.
        nodes.sort((a, b) -> {
            int dx = Integer.compare(a.pos.getX(), b.pos.getX());
            if (dx != 0) return dx;
            int dz = Integer.compare(a.pos.getZ(), b.pos.getZ());
            if (dz != 0) return dz;
            return Integer.compare(a.pos.getY(), b.pos.getY());
        });

        int total = nodes.size();
        int totalPages = Math.max(1, (total + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        if (page > totalPages) page = totalPages;
        int start = (page - 1) * ROWS_PER_PAGE;
        int end = Math.min(start + ROWS_PER_PAGE, total);

        final int finalPage = page;
        final int finalTotalPages = totalPages;
        source.sendSuccess(() -> Component.literal(
                String.format("=== Turrets in %s — page %d/%d (total %d) ===",
                        dimKey.location(), finalPage, finalTotalPages, total))
                .withStyle(ChatFormatting.GOLD), false);

        if (total == 0) {
            source.sendSuccess(() -> Component.literal("(no turrets registered in this dimension)")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                String.format("%-15s %-9s %-6s %-9s %-7s", "pos", "type", "range", "activity", "linked"))
                .withStyle(ChatFormatting.GRAY), false);

        for (int i = start; i < end; i++) {
            TurretRegistry.TurretNode n = nodes.get(i);
            String posStr = String.format("%d,%d,%d", n.pos.getX(), n.pos.getY(), n.pos.getZ());
            String type = n.isAdvanced ? "advanced" : "basic";
            int linked = TurretRegistry.linkedCount(dimKey, n.pos);
            String activity = readActivity(level, n);
            String row = String.format("%-15s %-9s %-6d %-9s %-7d",
                    posStr, type, n.effectiveRange, activity, linked);
            source.sendSuccess(() -> Component.literal(row), false);
        }
        return total;
    }

    private static String readActivity(ServerLevel level, TurretRegistry.TurretNode n) {
        // The registry's activation gate suppresses ticks for turrets with
        // no nearby player — their state.activity stays at whatever it was
        // last set to, which is misleading. Report DORMANT for any turret
        // the registry currently considers inactive.
        if (!TurretRegistry.isActive(level, n.pos)) return "DORMANT";
        BlockEntity be = level.getBlockEntity(n.pos);
        if (be instanceof BasicTurretBlockEntity bt) return bt.state.activity.name();
        if (be instanceof TurretMountBlockEntity tm) return tm.state.activity.name();
        return "?";
    }

    // -------------------------------------------------------- debug perf

    private static int debugPerf(CommandSourceStack source) {
        var server = source.getServer();
        source.sendSuccess(() -> Component.literal("=== DeceasedCC turret metrics (last full minute) ===")
                .withStyle(ChatFormatting.GOLD), false);
        long scans = TurretMetrics.getLastMinuteScans();
        long raycasts = TurretMetrics.getLastMinuteRaycasts();
        long shots = TurretMetrics.getLastMinuteShots();
        long targets = TurretMetrics.getLastMinuteTargets();
        source.sendSuccess(() -> Component.literal(String.format(
                "scans=%,d  raycasts=%,d  shots=%,d  targets-acquired=%,d",
                scans, raycasts, shots, targets)), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "rates: %.1f scans/sec, %.1f raycasts/sec, %.1f shots/sec",
                scans / 60.0, raycasts / 60.0, shots / 60.0))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("--- per dimension ---")
                .withStyle(ChatFormatting.GOLD), false);
        for (ServerLevel lvl : server.getAllLevels()) {
            ResourceKey<Level> dim = lvl.dimension();
            int totalTurrets = TurretRegistry.countRegistered(dim);
            int activeTurrets = TurretRegistry.countActive(dim);
            source.sendSuccess(() -> Component.literal(String.format(
                    "[%s] registered=%d active=%d",
                    dim.location(), totalTurrets, activeTurrets)), false);
        }
        return 1;
    }

    // -------------------------------------------------------- reload

    private static int reload(CommandSourceStack source) {
        java.nio.file.Path worldRoot = source.getServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        java.nio.file.Path serverCfg = worldRoot.resolve("serverconfig").resolve("deceasedcc-server.toml");
        if (!java.nio.file.Files.exists(serverCfg)) {
            source.sendFailure(Component.literal("Config file not found: " + serverCfg));
            return 0;
        }
        try {
            // Re-read the file from disk and push the values into the spec.
            // ForgeConfigSpec.acceptConfig() is the public hook that updates
            // every ConfigValue<T> the spec built in its static initializer.
            com.electronwill.nightconfig.core.file.CommentedFileConfig cfg =
                    com.electronwill.nightconfig.core.file.CommentedFileConfig.builder(serverCfg)
                            .preserveInsertionOrder()
                            .build();
            try {
                cfg.load();
                net.deceasedcraft.deceasedcc.core.ModConfig.SERVER_SPEC.acceptConfig(cfg);
            } finally {
                cfg.close();
            }
            source.sendSuccess(() -> Component.literal(
                    "DeceasedCC server config reloaded from " + serverCfg.getFileName())
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            DeceasedCC.LOGGER.error("Config reload failed", t);
            source.sendFailure(Component.literal("Reload failed: " + t.getMessage()));
            return 0;
        }
    }
}
