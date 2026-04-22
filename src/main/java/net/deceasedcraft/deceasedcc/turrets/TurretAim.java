package net.deceasedcraft.deceasedcc.turrets;

/** Shared aim-smoothing helpers so Basic and Advanced turrets both honour
 *  the configured turn speed instead of snapping to the target instantly. */
public final class TurretAim {
    private TurretAim() {}

    /** Returns a yaw value stepped toward {@code desired} by at most
     *  {@code maxStepDeg} degrees, picking the shortest angular path
     *  (wrap-around safe). Inputs and output are always in [0, 360). */
    public static float approachYaw(float current, float desired, float maxStepDeg) {
        float delta = ((desired - current) % 360f + 540f) % 360f - 180f;
        if (Math.abs(delta) <= maxStepDeg) return normalize(desired);
        return normalize(current + Math.signum(delta) * maxStepDeg);
    }

    /** Returns a pitch stepped toward {@code desired} by at most
     *  {@code maxStepDeg}. No wrap-around: pitch is a simple signed value. */
    public static float approachPitch(float current, float desired, float maxStepDeg) {
        float delta = desired - current;
        if (Math.abs(delta) <= maxStepDeg) return desired;
        return current + Math.signum(delta) * maxStepDeg;
    }

    private static float normalize(float deg) {
        float n = deg % 360f;
        if (n < 0) n += 360f;
        return n;
    }
}
