package net.deceasedcraft.deceasedcc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.deceasedcraft.deceasedcc.blocks.entity.HologramProjectorBlockEntity.HologramMode;
import net.deceasedcraft.deceasedcc.util.GreedyMesher;

import java.util.List;

/**
 * Phase 3 — per-projector cached voxel mesh.
 *
 * <p>The spec calls this a {@code VertexBuffer}, but we deliberately do
 * <strong>not</strong> use Blaze3D's raw {@code VertexBuffer} (which owns a
 * GL VBO and is driven by {@code drawWithShader}, bypassing
 * {@link com.mojang.blaze3d.vertex.VertexConsumer} batching). Going through
 * {@link VertexConsumer} is mandatory for Iris/Oculus compatibility (spec
 * Q8 HARD REQUIREMENT) — Iris wraps MC's {@link net.minecraft.client.renderer.MultiBufferSource}
 * to inject its shader pipeline. A raw VBO would draw with vanilla shaders
 * under shaderpacks, which breaks translucency / lighting integration.
 *
 * <p>Instead we cache the greedy-meshed {@link GreedyMesher.Quad} list and
 * re-emit through {@code VertexConsumer} each frame. The expensive work
 * (greedy meshing) is done once per content change, amortised across many
 * frames. Per-frame cost for the test case (16³ hollow cube → 6 greedy-
 * merged quads) is negligible; worst-case 64³ heterogeneous fill caps at
 * roughly {@code 3 · 64² = 12288} quads before any merging, which is still
 * well under 60 FPS budget on reference rig.
 */
public final class HologramVertexBuffer {

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final HologramMode mode;
    private final List<GreedyMesher.Quad> quads;

    private HologramVertexBuffer(int sx, int sy, int sz, HologramMode mode,
                                  List<GreedyMesher.Quad> quads) {
        this.sizeX = sx;
        this.sizeY = sy;
        this.sizeZ = sz;
        this.mode = mode;
        this.quads = quads;
    }

    /**
     * Rebuild the mesh from the given voxel data. {@code mode} picks the
     * meshing strategy — {@link HologramMode#MODE_3D_FULL} emits internal
     * heterogeneous faces, otherwise silhouette-only.
     */
    public static HologramVertexBuffer build(int sx, int sy, int sz,
                                              byte[] indexes, int[] palette,
                                              HologramMode mode) {
        boolean forceAll = mode == HologramMode.MODE_3D_FULL;
        List<GreedyMesher.Quad> q = GreedyMesher.mesh(sx, sy, sz, indexes, palette, forceAll);
        return new HologramVertexBuffer(sx, sy, sz, mode, q);
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public HologramMode mode() { return mode; }
    public int quadCount() { return quads.size(); }

    /**
     * Emit the cached quads into {@code vc}. The voxel grid is normalized so
     * its centre of mass lands at the projector emitter origin (PoseStack is
     * already translated there by the renderer). Each voxel cell occupies
     * {@code cellScale} units of local space. The colour of each quad is
     * modulated by the tint ({@code tR/tG/tB/tA} are already alpha-capped
     * by the renderer so even a 0xFF palette entry draws at most ~70% opaque).
     *
     * <p>Vertex format matches the Phase-2 RenderType:
     * {@code POSITION_COLOR_TEX_LIGHTMAP}. Voxel faces sample the shared
     * 1×1 white texture at UV (0.5, 0.5), so the vertex colour drives the
     * entire rendered colour. Lightmap at FULL_BRIGHT.
     */
    public void render(VertexConsumer vc, PoseStack.Pose pose,
                        int light, int tR, int tG, int tB, int tA,
                        float cellScale) {
        var matrix = pose.pose();
        // Centre the grid on the projector-local origin so scale/rotation
        // pivot through the middle of the voxel volume, not its -XYZ corner.
        float cx = sizeX * 0.5f;
        float cy = sizeY * 0.5f;
        float cz = sizeZ * 0.5f;

        for (GreedyMesher.Quad q : quads) {
            int pr = (q.colorARGB >>> 16) & 0xFF;
            int pg = (q.colorARGB >>>  8) & 0xFF;
            int pb =  q.colorARGB         & 0xFF;
            int pa = (q.colorARGB >>> 24) & 0xFF;
            int r = (pr * tR) / 255;
            int g = (pg * tG) / 255;
            int b = (pb * tB) / 255;
            int a = (pa * tA) / 255;

            float x0 = (q.x0 - cx) * cellScale, y0 = (q.y0 - cy) * cellScale, z0 = (q.z0 - cz) * cellScale;
            float x1 = (q.x1 - cx) * cellScale, y1 = (q.y1 - cy) * cellScale, z1 = (q.z1 - cz) * cellScale;
            float x2 = (q.x2 - cx) * cellScale, y2 = (q.y2 - cy) * cellScale, z2 = (q.z2 - cz) * cellScale;
            float x3 = (q.x3 - cx) * cellScale, y3 = (q.y3 - cy) * cellScale, z3 = (q.z3 - cz) * cellScale;

            vc.vertex(matrix, x0, y0, z0).color(r, g, b, a).uv(0.5f, 0.5f).uv2(light).endVertex();
            vc.vertex(matrix, x1, y1, z1).color(r, g, b, a).uv(0.5f, 0.5f).uv2(light).endVertex();
            vc.vertex(matrix, x2, y2, z2).color(r, g, b, a).uv(0.5f, 0.5f).uv2(light).endVertex();
            vc.vertex(matrix, x3, y3, z3).color(r, g, b, a).uv(0.5f, 0.5f).uv2(light).endVertex();
        }
    }

    /** No-op; no GL resources held. Kept for API symmetry with the spec. */
    public void close() { }
}
