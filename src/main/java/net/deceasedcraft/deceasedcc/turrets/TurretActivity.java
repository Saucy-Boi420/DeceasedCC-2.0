package net.deceasedcraft.deceasedcc.turrets;

/**
 * LOD activity state for a turret's target-acquisition scan. Determines how
 * often the BE/peripheral runs its full AABB scan + filter pipeline. Firing
 * is unaffected — once a target is locked the gun fires at its native RPM
 * regardless of activity.
 *
 * <p>Transitions (per scan):
 * <ul>
 *   <li>Target acquired → {@link #ENGAGING}, reset empty counter</li>
 *   <li>No target but any LivingEntity in box → {@link #ALERT}, reset counter</li>
 *   <li>Empty scan → increment counter; demote ENGAGING→ALERT immediately;
 *   drop to {@link #IDLE} once counter ≥ idleThresholdScans</li>
 * </ul>
 *
 * <p>Cadence (in ticks between scans):
 * <ul>
 *   <li>ENGAGING: tickRate (default 4)</li>
 *   <li>ALERT: tickRate × alertScanMult (default 4×3 = 12)</li>
 *   <li>IDLE: tickRate × idleScanMult (default 4×5 = 20)</li>
 * </ul>
 *
 * <p>Transient by convention — never serialized to NBT. A turret reloads in
 * IDLE and re-evaluates on its first scan.
 */
public enum TurretActivity {
    ENGAGING,
    ALERT,
    IDLE
}
