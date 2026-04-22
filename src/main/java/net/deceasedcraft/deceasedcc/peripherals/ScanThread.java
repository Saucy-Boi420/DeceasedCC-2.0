package net.deceasedcraft.deceasedcc.peripherals;

import net.deceasedcraft.deceasedcc.DeceasedCC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared worker pool for off-main-thread scanning work (radar, entity tracker,
 * turtle survey). Server-side only — never submit client-tick-bound work here.
 *
 * <p>We deliberately use a bounded pool so a runaway script spamming scans
 * can't fork thousands of threads. Submissions beyond the queue limit are
 * silently dropped; that path returns the stale cached scan per the Phase 3
 * contract.</p>
 */
public final class ScanThread {
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ThreadFactory TF = r -> {
        Thread t = new Thread(r, "DeceasedCC-Scan-" + SEQ.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setUncaughtExceptionHandler((th, ex) -> DeceasedCC.LOGGER.error("Scan worker crashed", ex));
        return t;
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, TF);

    private ScanThread() {}

    public static ExecutorService pool() {
        return POOL;
    }
}
