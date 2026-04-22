package net.deceasedcraft.deceasedcc.util;

/**
 * Phase 5 — shape selector for entity markers. The renderer carries one
 * pre-built mesh per value; each marker just picks which mesh to emit at
 * its position + scale + colour.
 *
 * <p>Lua string forms (case-insensitive):
 * <ul>
 *   <li>{@code "cube"}</li>
 *   <li>{@code "tetrahedron"} or {@code "tetra"}</li>
 *   <li>{@code "octahedron"} or {@code "diamond"}</li>
 *   <li>{@code "sphere"} or {@code "ball"}</li>
 * </ul>
 * Unknown strings fall back to {@link #CUBE}.
 */
public enum MarkerShape {
    CUBE,
    TETRAHEDRON,
    OCTAHEDRON,
    SPHERE,
    PYRAMID;

    public static MarkerShape fromString(String s) {
        if (s == null) return CUBE;
        switch (s.toLowerCase()) {
            case "cube":        return CUBE;
            case "tetrahedron":
            case "tetra":       return TETRAHEDRON;
            case "octahedron":
            case "diamond":     return OCTAHEDRON;
            case "sphere":
            case "ball":        return SPHERE;
            case "pyramid":
            case "frustum":     return PYRAMID;
            default:            return CUBE;
        }
    }

    /** Safe ordinal lookup — clamps out-of-range to {@link #CUBE}. Used on
     *  the client to decode a packet byte. */
    public static MarkerShape fromOrdinal(int o) {
        MarkerShape[] values = values();
        if (o < 0 || o >= values.length) return CUBE;
        return values[o];
    }
}
