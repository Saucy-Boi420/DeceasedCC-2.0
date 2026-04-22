package net.deceasedcraft.deceasedcc.core;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-turret crash isolation. Wraps each turret's serverTick body in a
 * try/catch so a thrown exception logs and back-offs the offending turret
 * instead of cascading into a server crash.
 *
 * <p>After {@value #CONSECUTIVE_FAULT_THRESHOLD} consecutive faults at the
 * same position, the turret is put in a {@value #COOLDOWN_TICKS}-tick
 * cooldown — it skips its tick body entirely until the cooldown expires.
 * On any successful tick, the consecutive counter resets.</p>
 *
 * <p>Honours {@link ModConfig#TURRET_PER_TICK_TRY_CATCH_ENABLED} as an
 * escape hatch — disable it during active debugging to let exceptions
 * bubble up so a stack trace surfaces in console.</p>
 *
 * <p>Single-threaded by construction (server thread only).</p>
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID)
public final class TurretTickGuard {
    private static final int CONSECUTIVE_FAULT_THRESHOLD = 5;
    private static final long COOLDOWN_TICKS = 20L * 60L; // 60 seconds

    private static final class FaultState {
        int consecutive;
        long cooldownUntilTick;
    }

    private static final Map<BlockPos, FaultState> FAULTS = new HashMap<>();

    private TurretTickGuard() {}

    /** Run {@code body} in a guarded context. If it throws, the exception is
     *  logged and the offending turret is counted; if {@code body} runs to
     *  completion, the consecutive-fault counter resets to 0. */
    public static void runTick(Level level, BlockPos pos, Runnable body) {
        if (!ModConfig.TURRET_PER_TICK_TRY_CATCH_ENABLED.get()) {
            body.run();
            return;
        }
        long now = level.getGameTime();
        FaultState f = FAULTS.get(pos);
        if (f != null && now < f.cooldownUntilTick) return;
        try {
            body.run();
            if (f != null && f.consecutive != 0) f.consecutive = 0;
        } catch (Throwable t) {
            FaultState fs = (f == null) ? new FaultState() : f;
            fs.consecutive++;
            DeceasedCC.LOGGER.error(
                    "Turret tick failed at {} ({} consecutive)",
                    pos, fs.consecutive, t);
            if (fs.consecutive >= CONSECUTIVE_FAULT_THRESHOLD) {
                fs.cooldownUntilTick = now + COOLDOWN_TICKS;
                fs.consecutive = 0;
                DeceasedCC.LOGGER.error(
                        "Turret at {} put in {}-tick cooldown after {} consecutive faults",
                        pos, COOLDOWN_TICKS, CONSECUTIVE_FAULT_THRESHOLD);
            }
            if (f == null) FAULTS.put(pos.immutable(), fs);
        }
    }

    /** Drop a turret's fault state. Called from BE.setRemoved so a freshly-
     *  placed replacement at the same pos starts clean. */
    public static void clear(BlockPos pos) {
        FAULTS.remove(pos);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent ev) {
        FAULTS.clear();
    }
}
