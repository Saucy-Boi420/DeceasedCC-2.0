package net.deceasedcraft.deceasedcc.core;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight counters for turret hot-path operations. Incremented from BE
 * tick code; rolled over into a "last full minute" snapshot every 1200 ticks
 * by the server tick listener. Read back via {@code /deceasedcc debug perf}.
 *
 * <p>AtomicLong is single-server-thread overkill, but the read path
 * ({@code /deceasedcc debug perf}) is on the command thread which may be
 * different — atomic gives us a free safe read without explicit synchronization.</p>
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID)
public final class TurretMetrics {
    private static final long RESET_INTERVAL_TICKS = 20L * 60L; // 1 minute

    private static final AtomicLong scans = new AtomicLong();
    private static final AtomicLong raycasts = new AtomicLong();
    private static final AtomicLong shots = new AtomicLong();
    private static final AtomicLong targetsAcquired = new AtomicLong();

    // Snapshot of the last full minute, exposed to /perf.
    private static volatile long lastMinuteScans = 0;
    private static volatile long lastMinuteRaycasts = 0;
    private static volatile long lastMinuteShots = 0;
    private static volatile long lastMinuteTargets = 0;

    private static long ticksSinceReset = 0;

    private TurretMetrics() {}

    public static void recordScan()           { scans.incrementAndGet(); }
    public static void recordRaycast()        { raycasts.incrementAndGet(); }
    public static void recordShot()           { shots.incrementAndGet(); }
    public static void recordTargetAcquired() { targetsAcquired.incrementAndGet(); }

    public static long getLastMinuteScans()    { return lastMinuteScans; }
    public static long getLastMinuteRaycasts() { return lastMinuteRaycasts; }
    public static long getLastMinuteShots()    { return lastMinuteShots; }
    public static long getLastMinuteTargets()  { return lastMinuteTargets; }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        if (++ticksSinceReset < RESET_INTERVAL_TICKS) return;
        ticksSinceReset = 0;
        lastMinuteScans    = scans.getAndSet(0);
        lastMinuteRaycasts = raycasts.getAndSet(0);
        lastMinuteShots    = shots.getAndSet(0);
        lastMinuteTargets  = targetsAcquired.getAndSet(0);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent ev) {
        scans.set(0);
        raycasts.set(0);
        shots.set(0);
        targetsAcquired.set(0);
        lastMinuteScans = lastMinuteRaycasts = lastMinuteShots = lastMinuteTargets = 0;
        ticksSinceReset = 0;
    }
}
