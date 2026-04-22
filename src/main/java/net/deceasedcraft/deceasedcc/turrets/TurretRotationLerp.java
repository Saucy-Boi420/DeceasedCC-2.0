package net.deceasedcraft.deceasedcc.turrets;

/**
 * Client-side keyframe interpolation for turret yaw/pitch. The server sends
 * a rotation every {@code syncIntervalTicks} ticks; holding the received
 * value static between packets produces a visible jolt at the packet
 * cadence. Instead, each arriving packet pushes the previous displayed
 * value into {@code prev}, the new server value into {@code next}, and the
 * BER linearly interpolates {@code prev → next} as {@code t} walks from 0
 * to 1 over the sync interval.
 *
 * <p>Yaw is wrapped via shortest-arc lerp so a jump from 359° → 1° sweeps
 * +2° rather than -358°. Pitch is straight linear (no wrap in our clamp).</p>
 */
public final class TurretRotationLerp {
    // How many ticks the interpolation should take to traverse prev→next.
    // Matches the server's TurretRotationPacket sync cadence.
    public static final float INTERP_TICKS = 4f;

    public float prevYaw, prevPitch;
    public float nextYaw, nextPitch;
    public long lerpStartTick = Long.MIN_VALUE;

    public boolean initialized = false;

    /** Called client-side when a new TurretRotationPacket arrives. */
    public void push(float newYaw, float newPitch, long currentGameTime) {
        if (!initialized) {
            prevYaw = newYaw; prevPitch = newPitch;
            nextYaw = newYaw; nextPitch = newPitch;
            initialized = true;
        } else {
            // Snapshot the currently-displayed value as the new "prev" so
            // the next sweep starts from exactly where the render frame is.
            prevYaw = sampleYaw(currentGameTime, 0f);
            prevPitch = samplePitch(currentGameTime, 0f);
            nextYaw = newYaw;
            nextPitch = newPitch;
        }
        lerpStartTick = currentGameTime;
    }

    public float sampleYaw(long currentGameTime, float partialTick) {
        float t = clamp01((currentGameTime - lerpStartTick + partialTick) / INTERP_TICKS);
        return lerpAngle(prevYaw, nextYaw, t);
    }

    public float samplePitch(long currentGameTime, float partialTick) {
        float t = clamp01((currentGameTime - lerpStartTick + partialTick) / INTERP_TICKS);
        return prevPitch + (nextPitch - prevPitch) * t;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** Shortest-arc lerp for a 0..360 yaw. */
    public static float lerpAngle(float a, float b, float t) {
        float diff = ((b - a + 540f) % 360f) - 180f;
        float out = a + diff * t;
        return ((out % 360f) + 360f) % 360f;
    }
}
