package net.deceasedcraft.deceasedcc.client.camera;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.network.CameraSnapshotRequestPacket;
import net.deceasedcraft.deceasedcc.network.CameraSnapshotResponsePacket;
import net.deceasedcraft.deceasedcc.network.DeceasedNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.Deflater;

/**
 * Phase 8.3 — client-side handler for snapshot requests from the server.
 * Queues incoming {@link CameraSnapshotRequestPacket}s, processes them
 * one-at-a-time at a safe render stage, sends back the ARGB bytes.
 *
 * <p>Rendering happens at {@code RenderLevelStageEvent.AFTER_LEVEL} — the
 * same pattern {@link CameraFeedRenderer} uses — so it doesn't interfere
 * with the outer-frame's main render.
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID, value = Dist.CLIENT)
public final class CameraSnapshotClient {
    private CameraSnapshotClient() {}

    private static final Queue<CameraSnapshotRequestPacket> QUEUE = new ConcurrentLinkedQueue<>();

    public static void onRequest(CameraSnapshotRequestPacket p) {
        QUEUE.offer(p);
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        CameraSnapshotRequestPacket req = QUEUE.poll();
        if (req == null) return;
        try {
            processRequest(req);
        } catch (Throwable t) {
            DeceasedCC.LOGGER.error("[Snapshot] capture failed", t);
            DeceasedNetwork.CHANNEL.sendToServer(new CameraSnapshotResponsePacket(
                    req.frameId(), req.width(), req.height(),
                    false, new byte[0], "exception: " + t.getClass().getSimpleName()));
        }
    }

    private static void processRequest(CameraSnapshotRequestPacket req) {
        TextureTarget target = new TextureTarget(req.width(), req.height(), true, Minecraft.ON_OSX);
        try {
            boolean ok = CameraFeedRenderer.captureTo(target,
                    new BlockPos(req.cameraPos()),
                    req.yaw(), req.pitch(), req.fov());
            if (!ok) {
                DeceasedNetwork.CHANNEL.sendToServer(new CameraSnapshotResponsePacket(
                        req.frameId(), req.width(), req.height(),
                        false, new byte[0], "renderer busy or shaders active"));
                return;
            }
            byte[] argb = readFboAsArgbTopLeft(target);
            byte[] compressed = deflate(argb);
            DeceasedNetwork.CHANNEL.sendToServer(new CameraSnapshotResponsePacket(
                    req.frameId(), req.width(), req.height(),
                    true, compressed, ""));
        } finally {
            try { target.destroyBuffers(); } catch (Exception ignored) {}
        }
    }

    /** Read the FBO's color attachment back to CPU as ARGB bytes (top-left
     *  origin). glReadPixels returns RGBA bytes with bottom-left origin;
     *  we reorder channels and flip Y so the output matches MC's
     *  DynamicTexture / setImage convention. */
    private static byte[] readFboAsArgbTopLeft(TextureTarget target) {
        int w = target.width, h = target.height;
        target.bindRead();
        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        try {
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            target.unbindRead();
            byte[] argb = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                int srcRow = (h - 1 - y) * w * 4;   // flip vertically
                int dstRow = y * w * 4;
                for (int x = 0; x < w; x++) {
                    int s = srcRow + x * 4;
                    int d = dstRow + x * 4;
                    byte r = buf.get(s);
                    byte g = buf.get(s + 1);
                    byte b = buf.get(s + 2);
                    byte a = buf.get(s + 3);
                    argb[d]     = a;
                    argb[d + 1] = r;
                    argb[d + 2] = g;
                    argb[d + 3] = b;
                }
            }
            return argb;
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static byte[] deflate(byte[] raw) {
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4);
        byte[] chunk = new byte[4096];
        while (!def.finished()) {
            int n = def.deflate(chunk);
            out.write(chunk, 0, n);
        }
        def.end();
        return out.toByteArray();
    }
}
