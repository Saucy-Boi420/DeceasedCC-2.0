package net.deceasedcraft.deceasedcc.core;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts the packaged peripheral API reference to
 * {@code config/deceasedcc/PERIPHERAL_API.md} on first launch so server admins
 * and script authors can browse it without digging into the jar.
 * <p>
 * Runs on a detached daemon thread so the one-shot disk I/O never blocks the
 * main server startup. Rewrites on version change so doc updates ship with
 * the jar; leaves user edits alone between versions by keying the marker on
 * mod version.
 */
public final class DocsExtractor {
    private static final String RESOURCE = "/assets/deceasedcc/PERIPHERAL_API.md";
    private static final String DEST_FILE = "PERIPHERAL_API.md";
    private static final String VERSION_MARKER = ".version";

    private DocsExtractor() {}

    public static void scheduleAsync(String modVersion) {
        Thread t = new Thread(() -> run(modVersion), "DeceasedCC-DocsExtractor");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thr, ex) ->
                DeceasedCC.LOGGER.warn("DocsExtractor crashed: {}", ex.toString()));
        t.start();
    }

    private static void run(String modVersion) {
        try {
            Path cfgDir = FMLPaths.CONFIGDIR.get().resolve(DeceasedCC.MODID);
            Files.createDirectories(cfgDir);
            Path marker = cfgDir.resolve(VERSION_MARKER);
            Path dest = cfgDir.resolve(DEST_FILE);

            if (Files.exists(dest) && Files.exists(marker)) {
                String existing = Files.readString(marker).trim();
                if (existing.equals(modVersion)) return;
            }

            try (InputStream in = DocsExtractor.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    DeceasedCC.LOGGER.warn("DocsExtractor: resource {} not found in jar", RESOURCE);
                    return;
                }
                Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(marker, modVersion);
        } catch (Throwable t) {
            DeceasedCC.LOGGER.warn("DocsExtractor failed: {}", t.toString());
        }
    }
}
