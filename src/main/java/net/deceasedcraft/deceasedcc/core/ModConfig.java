package net.deceasedcraft.deceasedcc.core;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * v1.9 grouped configuration. Split into two specs:
 *
 * <ul>
 *   <li><b>SERVER</b> — gameplay rules (per-world, syncs to joining clients).
 *       File: {@code <world>/serverconfig/deceasedcc-server.toml}</li>
 *   <li><b>CLIENT</b> — client-side rendering toggles (per-MC-installation).
 *       File: {@code config/deceasedcc-client.toml}</li>
 * </ul>
 *
 * <p>The old {@code deceasedcc-common.toml} from v1.8 is orphaned by this
 * split. Delete it; values are re-derived from defaults.</p>
 */
public final class ModConfig {

    // =========================================================== ENUMS

    public enum FriendlyFireMode { DEFAULT, FORCE_OFF, FORCE_ON }
    public enum OwnerExemptMode  { DEFAULT, FORCE_ON }

    // =========================================================== SERVER

    public static final ForgeConfigSpec SERVER_SPEC;

    // ---- basicTurret
    public static final ForgeConfigSpec.BooleanValue BASIC_TURRET_ENABLED;
    public static final ForgeConfigSpec.IntValue     BASIC_TURRET_RANGE;
    public static final ForgeConfigSpec.DoubleValue  BASIC_TURRET_FIRE_COOLDOWN_MULT;
    public static final ForgeConfigSpec.DoubleValue  BASIC_TURRET_RANGE_MULT;
    public static final ForgeConfigSpec.DoubleValue  BASIC_TURRET_DAMAGE_MULT;
    public static final ForgeConfigSpec.IntValue     BASIC_TURRET_SENTRY_RADIUS;

    // ---- advancedTurret
    public static final ForgeConfigSpec.BooleanValue ADVANCED_TURRET_ENABLED;
    public static final ForgeConfigSpec.IntValue     TURRET_ADVANCED_RANGE;       // toml: advancedTurret.range
    public static final ForgeConfigSpec.DoubleValue  ADVANCED_TURRET_FIRE_COOLDOWN_MULT;
    public static final ForgeConfigSpec.DoubleValue  ADVANCED_TURRET_DAMAGE_MULT;
    public static final ForgeConfigSpec.IntValue     ADVANCED_TURRET_SENTRY_RADIUS;

    // ---- turrets (global)
    public static final ForgeConfigSpec.IntValue     TURRET_TICK_RATE;
    public static final ForgeConfigSpec.DoubleValue  TURRET_MAX_TURN_SPEED;
    public static final ForgeConfigSpec.IntValue     TURRET_NETWORK_RANGE;
    public static final ForgeConfigSpec.IntValue     TURRET_MAX_RANGE_HARD_CAP;
    public static final ForgeConfigSpec.EnumValue<FriendlyFireMode> TURRET_FRIENDLY_FIRE_GLOBAL;
    public static final ForgeConfigSpec.EnumValue<OwnerExemptMode>  TURRET_OWNER_EXEMPT_GLOBAL;
    public static final ForgeConfigSpec.BooleanValue TURRET_PAUSE_IF_FRIENDLY_IN_LOS;
    public static final ForgeConfigSpec.IntValue     TURRET_ACTIVATION_RECOMPUTE_TICKS;
    public static final ForgeConfigSpec.IntValue     TURRET_MAX_CHAIN_DEPTH;
    public static final ForgeConfigSpec.IntValue     TURRET_ALERT_SCAN_MULT;
    public static final ForgeConfigSpec.IntValue     TURRET_IDLE_SCAN_MULT;
    public static final ForgeConfigSpec.IntValue     TURRET_IDLE_THRESHOLD_SCANS;
    public static final ForgeConfigSpec.BooleanValue TURRET_PER_TICK_TRY_CATCH_ENABLED;

    // ---- tacz
    public static final ForgeConfigSpec.DoubleValue  BULLET_SPEED_MULTIPLIER;

    // ---- chunkRadar
    public static final ForgeConfigSpec.IntValue     CHUNK_RADAR_MAX_RADIUS;
    public static final ForgeConfigSpec.IntValue     CHUNK_RADAR_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue     CHUNK_RADAR_MAX_BLOCKS_PER_TICK;
    public static final ForgeConfigSpec.IntValue     CHUNK_RADAR_CONCURRENT_WORKERS;

    // ---- entityTracker
    public static final ForgeConfigSpec.IntValue     ENTITY_TRACKER_MAX_RADIUS;
    public static final ForgeConfigSpec.BooleanValue ENTITY_TRACKER_EXPOSE_PLAYER_UUIDS;
    public static final ForgeConfigSpec.IntValue     ENTITY_TRACKER_COOLDOWN_TICKS;

    // ---- remote
    public static final ForgeConfigSpec.IntValue     REMOTE_BASE_RANGE_BLOCKS;
    public static final ForgeConfigSpec.BooleanValue REMOTE_DIMENSION_CHANGE_KICKS;
    public static final ForgeConfigSpec.IntValue     REMOTE_DRAIN_RATE_FE_PER_TICK;

    // ---- advancedNetworkController (v2.0 Phase 6)
    public static final ForgeConfigSpec.IntValue ADV_NETWORK_MAX_CONNECTIONS;

    // ---- camera (v2.0 Phase 6c)
    public static final ForgeConfigSpec.IntValue    CAMERA_CAPTURE_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue    CAMERA_BUFFER_DURATION_SECONDS;
    public static final ForgeConfigSpec.DoubleValue CAMERA_CONE_ANGLE_DEGREES;
    public static final ForgeConfigSpec.DoubleValue CAMERA_CONE_RANGE;
    // Phase 8.1 — 2D CCTV view caps (loadFromCamera2D).
    public static final ForgeConfigSpec.IntValue    CAMERA_VIEW_MAX_WIDTH;
    public static final ForgeConfigSpec.IntValue    CAMERA_VIEW_MAX_HEIGHT;
    public static final ForgeConfigSpec.DoubleValue CAMERA_VIEW_MAX_FOV_DEGREES;

    /** Phase 8.3 auto-migration — if user's toml is still at a known
     *  pre-8.3 default (Phase 8.1 shipped 1280×720), bump to Phase 8.3
     *  defaults (1920×1080). Respects user customization: if they
     *  explicitly set a different value, we leave it alone. Called from
     *  DeceasedCC common setup AFTER configs load. */
    public static void migratePhase83CameraCaps() {
        if (CAMERA_VIEW_MAX_WIDTH.get() == 1280) {
            CAMERA_VIEW_MAX_WIDTH.set(1920);
            net.deceasedcraft.deceasedcc.DeceasedCC.LOGGER.info(
                    "[Config] Migrated camera.viewMaxWidth 1280 → 1920 (Phase 8.3 default).");
        }
        if (CAMERA_VIEW_MAX_HEIGHT.get() == 720) {
            CAMERA_VIEW_MAX_HEIGHT.set(1080);
            net.deceasedcraft.deceasedcc.DeceasedCC.LOGGER.info(
                    "[Config] Migrated camera.viewMaxHeight 720 → 1080 (Phase 8.3 default).");
        }
    }

    // ---- hologram-from-scan (v2.0 Phase 7c)
    public static final ForgeConfigSpec.IntValue    HOLOGRAM_SCAN_MAX_VOXELS;

    // ---- Phase 10 — content-update rate limit (setVoxelGrid / setImage spam)
    public static final ForgeConfigSpec.IntValue    HOLOGRAM_MAX_UPDATES_PER_SECOND;

    // ---- debug
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOG_TICK_COSTS;

    // =========================================================== CLIENT

    public static final ForgeConfigSpec CLIENT_SPEC;

    // ---- client (placeholders for future client-side toggles)
    public static final ForgeConfigSpec.BooleanValue CLIENT_SHOW_RANGE_OVERLAY;
    public static final ForgeConfigSpec.BooleanValue CLIENT_SHOW_ACTIVITY_LABEL;
    public static final ForgeConfigSpec.BooleanValue CLIENT_FLIP_GUN_RENDER;
    // Phase 10 — hologram render distance cull
    public static final ForgeConfigSpec.IntValue     CLIENT_HOLOGRAM_RENDER_DISTANCE;

    static {
        // ----------------------------------- SERVER
        ForgeConfigSpec.Builder s = new ForgeConfigSpec.Builder();

        s.push("basicTurret");
        BASIC_TURRET_ENABLED = s.comment(
                        "Master toggle for basic turret functionality. False = block exists but never targets/fires.")
                .define("enabled", true);
        BASIC_TURRET_RANGE = s.comment(
                        "Base target-search radius for basic turrets, in blocks. Multiplied by upgrades and rangeMult.")
                .defineInRange("range", 16, 4, 64);
        BASIC_TURRET_FIRE_COOLDOWN_MULT = s.comment(
                        "Fire-cooldown multiplier for basic turrets. 4.0 = quarter native RPM, 2.0 = half RPM, 1.0 = native.")
                .defineInRange("fireCooldownMult", 4.0, 0.1, 10.0);
        BASIC_TURRET_RANGE_MULT = s.comment(
                        "Range multiplier for basic turrets. Stacks multiplicatively with upgrades. 1.0 = stock.")
                .defineInRange("rangeMult", 1.0, 0.1, 4.0);
        BASIC_TURRET_DAMAGE_MULT = s.comment(
                        "Damage multiplier for basic turret bullets. NOT YET WIRED — placeholder for a TACZ damage hook.")
                .defineInRange("damageMult", 1.0, 0.1, 5.0);
        BASIC_TURRET_SENTRY_RADIUS = s.comment(
                        "Player-proximity radius (blocks) that triggers basic-turret sentry-swivel idle animation.")
                .defineInRange("sentryRadius", 16, 0, 64);
        s.pop();

        s.push("advancedTurret");
        ADVANCED_TURRET_ENABLED = s.comment(
                        "Master toggle for advanced turret functionality. False = peripheral exists but never targets/fires.")
                .define("enabled", true);
        TURRET_ADVANCED_RANGE = s.comment(
                        "Base target-search radius for the Advanced Turret Mount, in blocks. Multiplied by upgrades.")
                .defineInRange("range", 64, 4, 128);
        ADVANCED_TURRET_FIRE_COOLDOWN_MULT = s.comment(
                        "Fire-cooldown multiplier for advanced turret AUTO-FIRE (basic-mode + CC forced-target paths).",
                        "2.0 = half native RPM, 1.0 = native. Does NOT affect peripheral.fire() — that uses state.fireRateTicks.")
                .defineInRange("fireCooldownMult", 2.0, 0.1, 10.0);
        ADVANCED_TURRET_DAMAGE_MULT = s.comment(
                        "Damage multiplier for advanced turret bullets. NOT YET WIRED — placeholder for a TACZ damage hook.")
                .defineInRange("damageMult", 1.0, 0.1, 5.0);
        ADVANCED_TURRET_SENTRY_RADIUS = s.comment(
                        "Player-proximity radius (blocks) that triggers advanced-turret sentry-swivel idle animation.")
                .defineInRange("sentryRadius", 16, 0, 64);
        s.pop();

        s.push("turrets");
        TURRET_TICK_RATE = s.comment(
                        "Base ENGAGING-mode scan cadence in ticks. ALERT/IDLE multiply this further (see scan mults).")
                .defineInRange("tickRate", 4, 1, 40);
        TURRET_MAX_TURN_SPEED = s.comment(
                        "Maximum turret turn speed in degrees per tick. Upgrades scale the base; the cap clamps un-upgraded.")
                .defineInRange("maxTurnSpeedDegPerTick", 48.0, 0.5, 180.0);
        TURRET_NETWORK_RANGE = s.comment(
                        "Maximum block distance between a Turret Network Controller and a turret it links to.")
                .defineInRange("networkRange", 32, 4, 128);
        TURRET_MAX_RANGE_HARD_CAP = s.comment(
                        "Absolute hard cap on effective turret range, applied after upgrades and config multipliers.",
                        "Prevents pathological setups (e.g. 4 advanced range upgrades on a max-range basic turret).")
                .defineInRange("maxRangeHardCap", 128, 4, 256);
        TURRET_FRIENDLY_FIRE_GLOBAL = s.comment(
                        "Global override for friendly-fire setting. DEFAULT = use per-turret setting.",
                        "FORCE_OFF = no turret will ever shoot a player. FORCE_ON = all turrets may shoot players.",
                        "NOT YET WIRED — placeholder.")
                .defineEnum("friendlyFireGlobal", FriendlyFireMode.DEFAULT);
        TURRET_OWNER_EXEMPT_GLOBAL = s.comment(
                        "Global override for owner-exempt setting. DEFAULT = use per-turret setting.",
                        "FORCE_ON = the placer is always exempt regardless of turret config. NOT YET WIRED.")
                .defineEnum("ownerExemptGlobal", OwnerExemptMode.DEFAULT);
        TURRET_PAUSE_IF_FRIENDLY_IN_LOS = s.comment(
                        "If true, turrets pause auto-fire when any player stands in the bullet's path.",
                        "False = turrets shoot through players (e.g. for adversarial PvP servers).")
                .define("pauseIfFriendlyInLOS", true);
        TURRET_ACTIVATION_RECOMPUTE_TICKS = s.comment(
                        "How often (server ticks) the turret activation registry recomputes which turrets are 'live'.",
                        "Lower = faster reaction when a player walks into range; higher = cheaper. 20 = 1 second.")
                .defineInRange("activationRecomputeTicks", 20, 4, 200);
        TURRET_MAX_CHAIN_DEPTH = s.comment(
                        "Sanity cap on the daisy-chain BFS depth in the activation registry.")
                .defineInRange("maxChainDepth", 16, 1, 128);
        TURRET_ALERT_SCAN_MULT = s.comment(
                        "ALERT scan cadence multiplier. Effective cadence = tickRate × this. Default 3 = 12 ticks (0.6s).")
                .defineInRange("alertScanMult", 3, 1, 20);
        TURRET_IDLE_SCAN_MULT = s.comment(
                        "IDLE scan cadence multiplier. Effective cadence = tickRate × this. Default 5 = 20 ticks (1s).")
                .defineInRange("idleScanMult", 5, 1, 40);
        TURRET_IDLE_THRESHOLD_SCANS = s.comment(
                        "Consecutive empty scans before transitioning ALERT → IDLE.")
                .defineInRange("idleThresholdScans", 3, 1, 20);
        TURRET_PER_TICK_TRY_CATCH_ENABLED = s.comment(
                        "Wrap every turret tick body in a TickGuard so a thrown exception isolates to the offending turret",
                        "instead of crashing the server. Disable only when actively debugging stack traces.")
                .define("perTickTryCatchEnabled", true);
        s.pop();

        s.push("tacz");
        BULLET_SPEED_MULTIPLIER = s.comment(
                        "Velocity multiplier applied to TACZ bullets fired by our turret shooters.")
                .defineInRange("bulletSpeedMultiplier", 2.0, 0.1, 10.0);
        s.pop();

        s.push("chunkRadar");
        CHUNK_RADAR_MAX_RADIUS = s.comment(
                        "Max cubic scan radius for the Chunk Radar (1..64; 48 = 3 chunks).")
                .defineInRange("maxRadius", 48, 1, 64);
        CHUNK_RADAR_COOLDOWN_SECONDS = s.comment(
                        "Cooldown between scans, in seconds.")
                .defineInRange("cooldownSeconds", 5, 1, 300);
        CHUNK_RADAR_MAX_BLOCKS_PER_TICK = s.comment(
                        "Maximum blocks scanned per server tick by the chunk radar. Lower if big",
                        "scans are stalling the server tick; raise for faster completion on beefy",
                        "hardware. Applies per active scan job.")
                .defineInRange("maxBlocksPerTick", 4096, 64, 65536);
        CHUNK_RADAR_CONCURRENT_WORKERS = s.comment(
                        "Reserved for future parallel-scan support. Currently each scan runs on one",
                        "worker (main-thread-paced); this value is read but has no effect until the",
                        "async scan rework lands.")
                .defineInRange("concurrentWorkers", 1, 1, 8);
        s.pop();

        s.push("entityTracker");
        ENTITY_TRACKER_MAX_RADIUS = s.comment(
                        "Max entity scan radius.")
                .defineInRange("maxRadius", 64, 1, 128);
        ENTITY_TRACKER_EXPOSE_PLAYER_UUIDS = s.comment(
                        "If false, returns a hash in place of a real player UUID.")
                .define("exposePlayerUUIDs", false);
        ENTITY_TRACKER_COOLDOWN_TICKS = s.comment(
                        "Server-tick cadence for the entity tracker's periodic watch/proximity",
                        "pass. Default 4 = 5 Hz. Raise (e.g. 10) if many trackers are running at",
                        "once and you see tick-time pressure; lower (e.g. 2) for snappier proximity",
                        "event latency at the cost of more CPU.")
                .defineInRange("cooldownTicks", 4, 1, 200);
        s.pop();

        s.push("remote");
        REMOTE_BASE_RANGE_BLOCKS = s.comment(
                        "Base wireless control range (blocks) for the Turret Remote, before upgrade multipliers.")
                .defineInRange("baseRangeBlocks", 64, 4, 512);
        REMOTE_DIMENSION_CHANGE_KICKS = s.comment(
                        "If true, the remote force-exits camera/control mode when the player changes dimensions.")
                .define("dimensionChangeKicks", true);
        REMOTE_DRAIN_RATE_FE_PER_TICK = s.comment(
                        "Base FE drain per tick while in camera/control mode (before power-upgrade discount).")
                .defineInRange("drainRateFEPerTick", 10, 0, 100);
        s.pop();

        s.push("advancedNetworkController");
        ADV_NETWORK_MAX_CONNECTIONS = s.comment(
                        "Total peripherals an Advanced Network Controller may link to (any combination of turrets /",
                        "cameras / hologram projectors / future wireless devices). Default 8 — e.g. 4 turrets +",
                        "3 cameras + 1 hologram, or 8 turrets, or 2 of each type, etc.")
                .defineInRange("maxConnections", 8, 1, 64);
        s.pop();

        s.push("camera");
        CAMERA_CAPTURE_INTERVAL_TICKS = s.comment(
                        "Server ticks between successive camera frustum scans. 10 = 2 frames/sec.")
                .defineInRange("captureIntervalTicks", 10, 1, 200);
        CAMERA_BUFFER_DURATION_SECONDS = s.comment(
                        "Rolling buffer duration per camera, in seconds. 60 = 120 frames at 10-tick cadence.")
                .defineInRange("bufferDurationSeconds", 60, 1, 600);
        CAMERA_CONE_ANGLE_DEGREES = s.comment(
                        "Full cone angle (degrees) of the camera's view frustum. 60 = 30° half-angle.")
                .defineInRange("coneAngleDegrees", 60.0, 1.0, 180.0);
        CAMERA_CONE_RANGE = s.comment(
                        "Maximum distance (blocks) at which a camera can see entities.")
                .defineInRange("coneRange", 32.0, 1.0, 128.0);
        CAMERA_VIEW_MAX_WIDTH = s.comment(
                        "Upper bound on 2D CCTV render width (pixels). Lua picks the actual width",
                        "per call via the opts table; requests above this cap throw a clean error.",
                        "Default 1280 = 720p ceiling. Going higher fragments deflate-compressed",
                        "ARGB packets above ~1 MB; the network holds up but clients see latency.")
                .defineInRange("viewMaxWidth", 1920, 16, 2048);
        CAMERA_VIEW_MAX_HEIGHT = s.comment(
                        "Upper bound on 2D CCTV render height (pixels). Same caveats as viewMaxWidth.",
                        "Default 720. Aspect ratio is Lua-controlled — pick any width/height combo",
                        "within the caps.")
                .defineInRange("viewMaxHeight", 1080, 16, 2048);
        CAMERA_VIEW_MAX_FOV_DEGREES = s.comment(
                        "Upper bound on 2D CCTV field-of-view (degrees). Wide angles exaggerate",
                        "barrel distortion in a pinhole projection; 150° is the practical limit before",
                        "blocks near the frustum edge splat to huge pixel rects.")
                .defineInRange("viewMaxFovDegrees", 150.0, 10.0, 170.0);
        s.pop();

        s.push("hologram");
        HOLOGRAM_SCAN_MAX_VOXELS = s.comment(
                        "Max voxel cell count for hologramSetFromScan. A ScanFile spanning a volume",
                        "larger than this throws a Lua error instead of silently nuking framerate.",
                        "Default 65536 ≈ 40x40x40. Raise if you have a beefy GPU and want larger",
                        "base blueprints; lower if clients are lagging.")
                .defineInRange("scanMaxVoxels", 65536, 1024, 1048576);
        HOLOGRAM_MAX_UPDATES_PER_SECOND = s.comment(
                        "Max content-update rate per hologram projector (setImage, setVoxelGrid,",
                        "setMarkers, setComposite, etc.). Excess calls are SILENTLY coalesced on the",
                        "server — the Lua script keeps running, but packets go out at most this many",
                        "times per second per projector. Prevents Lua loops from flooding the network",
                        "channel (a 64³ voxel grid is ~20 KB deflated; 20 Hz = 400 KB/s per projector).",
                        "Default 2/sec balances responsiveness vs bandwidth. Raise for smoother",
                        "animations (costs bandwidth); lower if multiple projectors lag your clients.")
                .defineInRange("maxUpdatesPerSecond", 2, 1, 20);
        s.pop();

        s.push("debug");
        DEBUG_LOG_TICK_COSTS = s.comment(
                        "Periodically log per-turret tick cost averages to the server console. Verbose; off by default.")
                .define("logTickCosts", false);
        s.pop();

        SERVER_SPEC = s.build();

        // ----------------------------------- CLIENT
        ForgeConfigSpec.Builder c = new ForgeConfigSpec.Builder();

        c.push("client");
        CLIENT_SHOW_RANGE_OVERLAY = c.comment(
                        "Render a wireframe sphere showing turret range when holding a Turret Linker.",
                        "NOT YET WIRED — placeholder for a future client-side overlay.")
                .define("showRangeOverlay", false);
        CLIENT_SHOW_ACTIVITY_LABEL = c.comment(
                        "Render the ENGAGING/ALERT/IDLE LOD state above each turret. Useful for debugging activation.",
                        "NOT YET WIRED — placeholder for a future client-side overlay.")
                .define("showActivityLabel", false);
        CLIENT_FLIP_GUN_RENDER = c.comment(
                        "If the gun model on your turrets renders BACKWARDS (stock pointing at the target",
                        "instead of the muzzle), set this to true. Some TACZ / tp_shooting / gun-pack",
                        "combinations render the FIXED ItemDisplayContext with a 180° offset from what our",
                        "BER assumes; this toggle adds the compensating flip locally. Purely cosmetic,",
                        "client-side only. Aim and bullet direction are unaffected either way.")
                .define("flipGunRender", false);
        CLIENT_HOLOGRAM_RENDER_DISTANCE = c.comment(
                        "Maximum distance (blocks) from the player's camera at which hologram",
                        "projectors still render. Beyond this, the quad/voxel-mesh is culled to",
                        "save frame time. Lower if you see hologram-related FPS drops in dense",
                        "base builds; raise if you want distant holograms still visible.")
                .defineInRange("hologramRenderDistance", 128, 16, 512);
        c.pop();

        CLIENT_SPEC = c.build();
    }

    private ModConfig() {}
}
