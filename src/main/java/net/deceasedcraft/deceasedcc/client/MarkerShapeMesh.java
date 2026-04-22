package net.deceasedcraft.deceasedcc.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.deceasedcraft.deceasedcc.util.MarkerShape;
import org.joml.Matrix4f;

import java.util.EnumMap;
import java.util.Map;

/**
 * Phase 5 — pre-built meshes for the four marker shapes.
 *
 * <p>Each shape is stored as a flat {@code float[][]} where every row is one
 * quad: 12 floats = 4 vertices × (x, y, z). Triangles are emitted as
 * degenerate quads (the 4th vertex is a copy of the 3rd) because our
 * RenderType uses {@link com.mojang.blaze3d.vertex.VertexFormat.Mode#QUADS}.
 *
 * <p>All shapes are unit-sized, centred on the origin: extents fit within
 * a 1×1×1 cube from -0.5 to +0.5. At render time the caller multiplies by
 * {@code markerScale * cellScale} and translates to the marker's grid
 * position.
 *
 * <p>Shape face counts (spec H11 performance target: 100 markers, no stutter):
 * <ul>
 *   <li>{@link MarkerShape#CUBE}: 6 quads</li>
 *   <li>{@link MarkerShape#TETRAHEDRON}: 4 triangles</li>
 *   <li>{@link MarkerShape#OCTAHEDRON}: 8 triangles</li>
 *   <li>{@link MarkerShape#SPHERE}: icosahedron, 20 triangles</li>
 * </ul>
 * Worst case (100 spheres) = 2000 quads, well within the 60-FPS budget.
 */
public final class MarkerShapeMesh {
    private MarkerShapeMesh() {}

    private static final Map<MarkerShape, float[][]> MESHES = new EnumMap<>(MarkerShape.class);

    static {
        MESHES.put(MarkerShape.CUBE,         buildCube());
        MESHES.put(MarkerShape.TETRAHEDRON,  buildTetrahedron());
        MESHES.put(MarkerShape.OCTAHEDRON,   buildOctahedron());
        MESHES.put(MarkerShape.SPHERE,       buildIcosahedron());
        MESHES.put(MarkerShape.PYRAMID,      buildPyramid());
    }

    public static float[][] mesh(MarkerShape shape) {
        return MESHES.get(shape);
    }

    /**
     * Emit one marker into {@code vc}. Vertex format must match the
     * hologram RenderType ({@code POSITION_COLOR_TEX_LIGHTMAP} with the
     * shared 1×1 white texture) — we sample UV (0.5, 0.5) and let the
     * vertex colour drive the final rendered tint.
     *
     * @param markerLX marker centre X in voxel-local coords (pre-cellScale)
     * @param markerLY same, Y
     * @param markerLZ same, Z
     * @param cellScale 1 / maxDim so the whole grid fits in a unit cube
     * @param markerScale per-marker scale multiplier (voxel cells)
     * @param r/g/b/a final modulated colour bytes, already alpha-capped
     * @param light packed block/sky lightmap (FULL_BRIGHT)
     */
    public static void renderMarker(VertexConsumer vc, Matrix4f matrix,
                                     MarkerShape shape,
                                     float markerLX, float markerLY, float markerLZ,
                                     float cellScale,
                                     float scaleX, float scaleY, float scaleZ,
                                     float yawDeg, float pitchDeg,
                                     int r, int g, int b, int a, int light) {
        float[][] quads = MESHES.get(shape);
        if (quads == null) return;
        // Shape vertices are in unit-cube local space. Apply per-axis
        // marker scale, then the projector-wide cellScale, then rotate
        // around the marker centre by yaw/pitch (MC convention: yaw 0 = +Z,
        // pitch 0 = horizontal), then translate to the marker's grid pos.
        float sxMul = scaleX * cellScale;
        float syMul = scaleY * cellScale;
        float szMul = scaleZ * cellScale;
        float yawRad   = (float) Math.toRadians(yawDeg);
        float pitchRad = (float) Math.toRadians(pitchDeg);
        float cy = (float) Math.cos(yawRad);
        float sy = (float) Math.sin(yawRad);
        float cp = (float) Math.cos(pitchRad);
        float sp = (float) Math.sin(pitchRad);
        boolean rotate = (yawDeg != 0f) || (pitchDeg != 0f);
        for (float[] q : quads) {
            for (int i = 0; i < 4; i++) {
                float vx = q[i * 3    ] * sxMul;
                float vy = q[i * 3 + 1] * syMul;
                float vz = q[i * 3 + 2] * szMul;
                if (rotate) {
                    // MC convention: pitch (around +X) first, then yaw
                    // (around +Y by -yaw degrees). A model-space +Z apex
                    // with yaw=0/pitch=0 lands at world +Z; yaw=90 ⇒ -X.
                    // Pitch:  y' =  cp*y - sp*z ; z' = sp*y + cp*z
                    float py = cp * vy - sp * vz;
                    float pz = sp * vy + cp * vz;
                    vy = py; vz = pz;
                    // Yaw (by -yaw):  x' = cy*x - sy*z ; z' = sy*x + cy*z
                    float px  =  cy * vx - sy * vz;
                    float pz2 =  sy * vx + cy * vz;
                    vx = px; vz = pz2;
                }
                vc.vertex(matrix, markerLX + vx, markerLY + vy, markerLZ + vz)
                        .color(r, g, b, a)
                        .uv(0.5f, 0.5f)
                        .uv2(light)
                        .endVertex();
            }
        }
    }

    // -----------------------------------------------------------------
    // shape builders
    // -----------------------------------------------------------------

    private static float[][] buildCube() {
        final float h = 0.5f;
        return new float[][] {
                // -X face (normal -X)
                { -h, -h, -h,   -h, -h,  h,   -h,  h,  h,   -h,  h, -h },
                // +X face
                {  h, -h, -h,    h,  h, -h,    h,  h,  h,    h, -h,  h },
                // -Y face
                { -h, -h, -h,    h, -h, -h,    h, -h,  h,   -h, -h,  h },
                // +Y face
                { -h,  h, -h,   -h,  h,  h,    h,  h,  h,    h,  h, -h },
                // -Z face
                { -h, -h, -h,   -h,  h, -h,    h,  h, -h,    h, -h, -h },
                // +Z face
                { -h, -h,  h,    h, -h,  h,    h,  h,  h,   -h,  h,  h },
        };
    }

    private static float[][] buildTetrahedron() {
        final float h = 0.5f;
        // 4 vertices at alternating corners of a cube — regular tetrahedron.
        float[] v0 = {  h,  h,  h };
        float[] v1 = { -h, -h,  h };
        float[] v2 = { -h,  h, -h };
        float[] v3 = {  h, -h, -h };
        return new float[][] {
                triQuad(v0, v1, v2),
                triQuad(v0, v3, v1),
                triQuad(v0, v2, v3),
                triQuad(v1, v3, v2),
        };
    }

    private static float[][] buildOctahedron() {
        final float h = 0.5f;
        float[] xp = {  h, 0, 0 };
        float[] xm = { -h, 0, 0 };
        float[] yp = { 0,  h, 0 };
        float[] ym = { 0, -h, 0 };
        float[] zp = { 0, 0,  h };
        float[] zm = { 0, 0, -h };
        return new float[][] {
                // top cap (pointed at yp)
                triQuad(yp, xp, zp),
                triQuad(yp, zp, xm),
                triQuad(yp, xm, zm),
                triQuad(yp, zm, xp),
                // bottom cap (pointed at ym)
                triQuad(ym, zp, xp),
                triQuad(ym, xm, zp),
                triQuad(ym, zm, xm),
                triQuad(ym, xp, zm),
        };
    }

    /**
     * Build a regular icosahedron (12 vertices, 20 triangular faces) as
     * 20 degenerate quads. Vertex positions are standard golden-ratio
     * coordinates, normalised to a bounding sphere of radius 0.5 so it
     * fits the unit-cube convention.
     */
    private static float[][] buildIcosahedron() {
        double phi = (1.0 + Math.sqrt(5.0)) / 2.0;
        // Radius of the vertex cloud before normalisation.
        double r = Math.sqrt(1.0 + phi * phi);
        double a = 1.0   * 0.5 / r;   // ± small
        double b = phi   * 0.5 / r;   // ± large
        float[][] V = new float[][] {
                { 0f,  (float) a,  (float) b }, //  0
                { 0f, -(float) a,  (float) b }, //  1
                { 0f,  (float) a, -(float) b }, //  2
                { 0f, -(float) a, -(float) b }, //  3
                {  (float) a,  (float) b, 0f }, //  4
                { -(float) a,  (float) b, 0f }, //  5
                {  (float) a, -(float) b, 0f }, //  6
                { -(float) a, -(float) b, 0f }, //  7
                {  (float) b, 0f,  (float) a }, //  8
                { -(float) b, 0f,  (float) a }, //  9
                {  (float) b, 0f, -(float) a }, // 10
                { -(float) b, 0f, -(float) a }, // 11
        };
        int[][] tris = {
                {0, 1, 8}, {0, 8, 4}, {0, 4, 5}, {0, 5, 9}, {0, 9, 1},
                {1, 9, 7}, {1, 7, 6}, {1, 6, 8},
                {2, 3, 11},{2, 11, 5},{2, 5, 4}, {2, 4, 10},{2, 10, 3},
                {3, 10, 6},{3, 6, 7}, {3, 7, 11},
                {4, 8, 10},{5, 11, 9},{6, 10, 8},{7, 9, 11},
        };
        float[][] out = new float[tris.length][12];
        for (int i = 0; i < tris.length; i++) {
            out[i] = triQuad(V[tris[i][0]], V[tris[i][1]], V[tris[i][2]]);
        }
        return out;
    }

    /** Square-based pyramid with apex at model-space -Z = -0.5 and base
     *  at +Z = +0.5 (corners at ±0.5 X/Y). Designed so a camera-FOV marker
     *  placed at the midpoint between the camera and its look target, with
     *  scaleZ = range, places the apex at the camera and the base at the
     *  far end. scaleX/scaleY stretch the base to match the FOV aspect
     *  ratio. */
    private static float[][] buildPyramid() {
        final float h = 0.5f;
        float[] apex = {  0f,  0f, -h };
        float[] c00  = { -h, -h,  h };
        float[] c10  = {  h, -h,  h };
        float[] c11  = {  h,  h,  h };
        float[] c01  = { -h,  h,  h };
        return new float[][] {
                triQuad(apex, c10, c00),    // -Y side (winding reversed for -Z apex)
                triQuad(apex, c11, c10),    // +X side
                triQuad(apex, c01, c11),    // +Y side
                triQuad(apex, c00, c01),    // -X side
                triQuad(c00, c10, c11),     // base tri 1
                triQuad(c00, c11, c01),     // base tri 2
        };
    }

    /** Pack a triangle into a 4-vertex quad (v0, v1, v2, v2) for the
     *  QUADS-mode render type. */
    private static float[] triQuad(float[] v0, float[] v1, float[] v2) {
        return new float[] {
                v0[0], v0[1], v0[2],
                v1[0], v1[1], v1[2],
                v2[0], v2[1], v2[2],
                v2[0], v2[1], v2[2],
        };
    }
}
