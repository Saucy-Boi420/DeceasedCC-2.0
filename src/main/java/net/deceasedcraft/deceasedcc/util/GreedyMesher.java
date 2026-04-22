package net.deceasedcraft.deceasedcc.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Greedy-meshing for hologram voxel grids (Phase 3, spec Q6 "implement from
 * scratch"). Reduces a per-voxel index grid to a list of axis-aligned
 * rectangular quads, merging adjacent coplanar same-colour faces. Typical
 * reduction on a hollow cube / terrain slice is 90%+ vs. naive per-voxel
 * face emission.
 *
 * <h3>Input</h3>
 * <ul>
 *   <li>{@code sx, sy, sz} — grid dimensions, ≤64 each (hologram.txt cap).</li>
 *   <li>{@code indexes}   — {@code sx·sy·sz} bytes, row-major X→Y→Z.
 *                           Byte value {@code 0} means empty; values
 *                           {@code 1..N} map to {@code palette[0..N-1]}.</li>
 *   <li>{@code palette}   — ARGB ints.</li>
 *   <li>{@code forceAllFaces} — when {@code true} (MODE_3D_FULL), every
 *                           surface-adjacent face is emitted plus internal
 *                           faces at heterogeneous-colour boundaries; when
 *                           {@code false} (MODE_3D_CULLED), only silhouette
 *                           faces (solid↔empty) are emitted.</li>
 * </ul>
 *
 * <h3>Output</h3>
 * List of {@link Quad} instances in voxel-local coordinates (origin [0,0,0],
 * spanning up to [sx,sy,sz]). The renderer scales/translates as needed.
 *
 * <h3>Algorithm</h3>
 * Classical John Lin sweep, one axis at a time:
 * <ol>
 *   <li>For each axis {@code d} (0=X, 1=Y, 2=Z) and each integer slab
 *       position {@code s ∈ [0..sizeD]}:</li>
 *   <li>Build two 2D masks of per-cell palette indices — one for faces
 *       whose normal points {@code +d}, one for {@code -d}.</li>
 *   <li>Greedy-rectangle-pack each mask: for every non-zero cell, grow
 *       along U as far as cells match, then along V as long as every cell
 *       in the width slab matches, then clear the rectangle and emit a
 *       {@link Quad}.</li>
 * </ol>
 */
public final class GreedyMesher {
    private GreedyMesher() {}

    /**
     * A single output quad. Corners are in voxel-local coordinates; the
     * renderer applies the projector-relative transform on top. Winding is
     * CCW viewed from the {@code +normal} side — but the hologram renderer
     * disables back-face culling, so winding is cosmetic.
     */
    public static final class Quad {
        public final float x0, y0, z0;
        public final float x1, y1, z1;
        public final float x2, y2, z2;
        public final float x3, y3, z3;
        public final int colorARGB;

        Quad(float x0, float y0, float z0,
             float x1, float y1, float z1,
             float x2, float y2, float z2,
             float x3, float y3, float z3,
             int colorARGB) {
            this.x0 = x0; this.y0 = y0; this.z0 = z0;
            this.x1 = x1; this.y1 = y1; this.z1 = z1;
            this.x2 = x2; this.y2 = y2; this.z2 = z2;
            this.x3 = x3; this.y3 = y3; this.z3 = z3;
            this.colorARGB = colorARGB;
        }
    }

    public static List<Quad> mesh(int sx, int sy, int sz, byte[] indexes,
                                   int[] palette, boolean forceAllFaces) {
        if (indexes == null || palette == null) return List.of();
        if (sx <= 0 || sy <= 0 || sz <= 0) return List.of();
        if (indexes.length != sx * sy * sz) return List.of();
        int[] dims = { sx, sy, sz };
        List<Quad> out = new ArrayList<>();
        // For MODE_3D_CULLED, flood-fill from grid-boundary empty cells so
        // we can tell which side of a 1-voxel-thick wall is "outside the
        // enclosed volume". Without this, a 1-thick wall in the MIDDLE of
        // the grid (e.g., the east face of a cube that doesn't touch the
        // +X grid edge, as in the two-cubes-with-gap test) gets its face
        // emitted on the wrong side and visibly cuts through the cube.
        boolean[] exterior = forceAllFaces ? null : computeExterior(dims, indexes);

        for (int d = 0; d < 3; d++) {
            int u = (d + 1) % 3;
            int v = (d + 2) % 3;
            int du = dims[u];
            int dv = dims[v];

            int[] maskPos = new int[du * dv]; // +d normal faces, palette-index+0..N
            int[] maskNeg = new int[du * dv]; // -d normal faces
            int[] xyz = new int[3];

            for (int slab = 0; slab <= dims[d]; slab++) {
                // Fill masks for this slab. 'a' is voxel at slab-1 (the
                // cell on the -d side); 'b' is the cell on the +d side.
                xyz[d] = slab;
                for (int vv = 0; vv < dv; vv++) {
                    xyz[v] = vv;
                    for (int uu = 0; uu < du; uu++) {
                        xyz[u] = uu;

                        int a = sampleAt(dims, indexes, xyz, d, -1);
                        int b = sampleAt(dims, indexes, xyz, d,  0);

                        int pPos, pNeg;
                        if (forceAllFaces) {
                            // MODE_3D_FULL: include heterogeneous-colour
                            // solid/solid faces. No dedup (internal walls
                            // matter for heterogeneous visualisation).
                            pPos = (a != 0 && (b == 0 || a != b)) ? a : 0;
                            pNeg = (b != 0 && (a == 0 || a != b)) ? b : 0;
                        } else {
                            // MODE_3D_CULLED: silhouette + 1-voxel-thick
                            // wall dedup. Naive silhouette emits both the
                            // outer and inner face of a 1-voxel-thick
                            // shell, producing a visible "double wall"
                            // (outer face at slab=p plane, inner at
                            // slab=p+1 plane, 1 voxel apart). The user
                            // wants 1-thick walls to render as a SINGLE
                            // translucent plane, not a 1-voxel sandwich.
                            //
                            // Detection: a solid voxel at position p is
                            // 1-thick along d if its neighbours at p-1
                            // AND p+1 are both empty (or OOB). Then
                            // naive silhouette emits two faces (one per
                            // side). We keep only ONE, preferring the
                            // outer grid-boundary face when possible.
                            //
                            // aLeft = voxel two cells back from b (= p-2
                            // relative to b's neighbour a); used to test
                            // whether a is 1-thick for maskPos.
                            // bRight = voxel one past b on the +d side;
                            // used to test whether b is 1-thick for
                            // maskNeg.
                            int aLeft  = sampleAt(dims, indexes, xyz, d, -2);
                            int bRight = sampleAt(dims, indexes, xyz, d,  1);

                            pPos = (a != 0 && b == 0) ? a : 0;
                            pNeg = (a == 0 && b != 0) ? b : 0;

                            // 1-thick-wall face placement. For any solid
                            // voxel with empty neighbours on both +d and
                            // -d sides, emit ONE face on the side whose
                            // neighbour is "more exterior" (OOB beats
                            // exterior-empty beats interior-empty). This
                            // puts the face on the cube's visible outer
                            // edge regardless of where the cube sits in
                            // the grid. Tiebreak: prefer the -d side.
                            //
                            // maskPos emits a's +d face at slab. Keep it
                            // iff b is STRICTLY more exterior than aLeft.
                            if (pPos != 0 && aLeft == 0) {
                                int aLeftClass = classifyNeighbour(dims, exterior, xyz, d, -2);
                                int bClass     = classifyNeighbour(dims, exterior, xyz, d,  0);
                                if (bClass <= aLeftClass) pPos = 0;
                            }
                            // maskNeg emits b's -d face at slab. Keep it
                            // iff a is AT LEAST as exterior as bRight
                            // (ties go to -d).
                            if (pNeg != 0 && bRight == 0) {
                                int aClass      = classifyNeighbour(dims, exterior, xyz, d, -1);
                                int bRightClass = classifyNeighbour(dims, exterior, xyz, d,  1);
                                if (aClass < bRightClass) pNeg = 0;
                            }
                        }
                        int idx = uu + vv * du;
                        maskPos[idx] = pPos;
                        maskNeg[idx] = pNeg;
                    }
                }
                greedyPack(maskPos, du, dv, slab, d, u, v, true,  palette, out);
                greedyPack(maskNeg, du, dv, slab, d, u, v, false, palette, out);
            }
        }
        return out;
    }

    /** Returns the voxel byte at ({@code xyz} shifted by {@code offset} along
     *  {@code axis}) as an unsigned int, or {@code 0} if out-of-bounds. */
    private static int sampleAt(int[] dims, byte[] indexes, int[] xyz, int axis, int offset) {
        int shifted = xyz[axis] + offset;
        if (shifted < 0 || shifted >= dims[axis]) return 0;
        int ix = (axis == 0) ? shifted : xyz[0];
        int iy = (axis == 1) ? shifted : xyz[1];
        int iz = (axis == 2) ? shifted : xyz[2];
        // Other axes are inside [0..dims) already since the outer loop
        // iterated them that way.
        int flat = ix + iy * dims[0] + iz * dims[0] * dims[1];
        return indexes[flat] & 0xFF;
    }

    /**
     * Classify the cell at ({@code xyz} shifted by {@code offset} along
     * {@code axis}) by how "exterior" it is:
     * <ul>
     *   <li>{@code 2} — out-of-bounds (beyond the grid; always exterior).</li>
     *   <li>{@code 1} — in-bounds, empty, reachable from OOB through
     *       only-empty paths.</li>
     *   <li>{@code 0} — in-bounds, either solid OR empty but enclosed by
     *       solid walls (interior pocket).</li>
     * </ul>
     * Used by the 1-thick-wall face-placement logic to decide which side of
     * a thin wall to render on.
     */
    private static int classifyNeighbour(int[] dims, boolean[] exterior,
                                          int[] xyz, int axis, int offset) {
        int shifted = xyz[axis] + offset;
        if (shifted < 0 || shifted >= dims[axis]) return 2;
        int ix = (axis == 0) ? shifted : xyz[0];
        int iy = (axis == 1) ? shifted : xyz[1];
        int iz = (axis == 2) ? shifted : xyz[2];
        int flat = ix + iy * dims[0] + iz * dims[0] * dims[1];
        return exterior[flat] ? 1 : 0;
    }

    /**
     * Mark every empty cell reachable from a grid-boundary empty cell via
     * face-adjacent steps. Solid cells block propagation. The result tells
     * the 1-thick-wall dedup which side of each thin wall faces the
     * outside world vs. an enclosed pocket.
     */
    private static boolean[] computeExterior(int[] dims, byte[] indexes) {
        int sx = dims[0], sy = dims[1], sz = dims[2];
        boolean[] exterior = new boolean[sx * sy * sz];
        ArrayDeque<int[]> queue = new ArrayDeque<>();

        // Seed: every boundary-layer empty cell is exterior.
        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    boolean onBoundary = x == 0 || x == sx - 1
                                      || y == 0 || y == sy - 1
                                      || z == 0 || z == sz - 1;
                    if (!onBoundary) continue;
                    int flat = x + y * sx + z * sx * sy;
                    if ((indexes[flat] & 0xFF) == 0) {
                        exterior[flat] = true;
                        queue.add(new int[] { x, y, z });
                    }
                }
            }
        }

        int[][] deltas = {
                {  1,  0,  0 }, { -1,  0,  0 },
                {  0,  1,  0 }, {  0, -1,  0 },
                {  0,  0,  1 }, {  0,  0, -1 },
        };
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            for (int[] d : deltas) {
                int nx = p[0] + d[0];
                int ny = p[1] + d[1];
                int nz = p[2] + d[2];
                if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) continue;
                int nFlat = nx + ny * sx + nz * sx * sy;
                if (exterior[nFlat]) continue;
                if ((indexes[nFlat] & 0xFF) != 0) continue; // solid blocks propagation
                exterior[nFlat] = true;
                queue.add(new int[] { nx, ny, nz });
            }
        }
        return exterior;
    }

    private static void greedyPack(int[] mask, int du, int dv,
                                    int slab, int d, int u, int v,
                                    boolean positiveNormal,
                                    int[] palette, List<Quad> out) {
        for (int j = 0; j < dv; j++) {
            for (int i = 0; i < du; ) {
                int c = mask[i + j * du];
                if (c == 0) { i++; continue; }

                // Grow width along u.
                int w = 1;
                while (i + w < du && mask[(i + w) + j * du] == c) w++;

                // Grow height along v as long as the whole w-slab matches.
                int h = 1;
                boolean rowOk = true;
                while (j + h < dv && rowOk) {
                    for (int k = 0; k < w; k++) {
                        if (mask[(i + k) + (j + h) * du] != c) { rowOk = false; break; }
                    }
                    if (rowOk) h++;
                }

                emitQuad(out, slab, d, u, v, i, j, w, h, positiveNormal,
                         paletteColour(palette, c));

                // Mark the rectangle consumed.
                for (int b = 0; b < h; b++) {
                    for (int a = 0; a < w; a++) {
                        mask[(i + a) + (j + b) * du] = 0;
                    }
                }
                i += w;
            }
        }
    }

    private static int paletteColour(int[] palette, int oneBasedIdx) {
        int p = oneBasedIdx - 1;
        if (p < 0 || p >= palette.length) return 0xFFFF00FF; // magenta = missing palette
        return palette[p];
    }

    /**
     * Build a quad in voxel-local coordinates from the (slab, i, j, w, h)
     * indices plus the axis mapping. Corners are ordered CCW viewed from the
     * {@code +axis} side when {@code positiveNormal} is true.
     */
    private static void emitQuad(List<Quad> out,
                                  int slab, int d, int u, int v,
                                  int i, int j, int w, int h,
                                  boolean positiveNormal, int color) {
        // Plane is at coord=slab along axis d. Corners span [i..i+w] along u
        // and [j..j+h] along v.
        float[] c0 = axisCorner(d, u, v, slab, i,     j    );
        float[] c1 = axisCorner(d, u, v, slab, i + w, j    );
        float[] c2 = axisCorner(d, u, v, slab, i + w, j + h);
        float[] c3 = axisCorner(d, u, v, slab, i,     j + h);

        if (!positiveNormal) {
            // Flip winding so CCW-from-normal-side matches the -d face.
            float[] tmp = c1; c1 = c3; c3 = tmp;
        }
        out.add(new Quad(
                c0[0], c0[1], c0[2],
                c1[0], c1[1], c1[2],
                c2[0], c2[1], c2[2],
                c3[0], c3[1], c3[2],
                color));
    }

    private static float[] axisCorner(int d, int u, int v,
                                       int slabCoord, int uCoord, int vCoord) {
        float[] p = new float[3];
        p[d] = slabCoord;
        p[u] = uCoord;
        p[v] = vCoord;
        return p;
    }
}
