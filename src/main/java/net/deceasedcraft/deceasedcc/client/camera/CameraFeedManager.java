package net.deceasedcraft.deceasedcc.client.camera;

import com.mojang.blaze3d.pipeline.TextureTarget;
import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 8.2 / 8.3 — per-projector client-side camera feed state. Owns a
 * {@link TextureTarget} and a registered {@link FBOTexture} per active
 * live-camera projector. FBO dimensions are per-projector (derived from
 * the BE's sticky feedWidth/feedHeight opts, synced via transform packet).
 * Auto-resizes when the Entry's feed dims change.
 *
 * <p>Feeds not requested in {@link #STALE_TIMEOUT_MS} ms get auto-released.
 */
public final class CameraFeedManager {
    private CameraFeedManager() {}

    /** Minimum gap between successive captures for a given feed. */
    private static final long MIN_REFRESH_INTERVAL_MS = 500L;

    /** Feeds not requested in this long get released. */
    private static final long STALE_TIMEOUT_MS = 5_000L;

    private static final Map<BlockPos, Feed> FEEDS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(0);

    private static final class Feed {
        volatile TextureTarget fbo;
        final ResourceLocation texLoc;
        final FBOTexture wrapper;
        volatile int width;
        volatile int height;
        volatile long lastRenderMs = 0L;
        volatile long lastRequestedMs = System.currentTimeMillis();

        Feed(TextureTarget fbo, ResourceLocation texLoc, FBOTexture wrapper, int w, int h) {
            this.fbo = fbo;
            this.texLoc = texLoc;
            this.wrapper = wrapper;
            this.width = w;
            this.height = h;
        }
    }

    /** Ensure a feed exists at the requested dimensions. If the existing
     *  FBO's size differs, the old one is destroyed and a new one
     *  allocated + re-bound to the same ResourceLocation. */
    public static ResourceLocation getOrCreateTexture(BlockPos projectorPos, int w, int h) {
        BlockPos key = projectorPos.immutable();
        Feed f = FEEDS.get(key);
        long now = System.currentTimeMillis();
        if (f != null) {
            f.lastRequestedMs = now;
            if (f.width == w && f.height == h) return f.texLoc;
            // Resize — destroy old FBO, allocate new at new dims, point
            // wrapper at the new texture. ResourceLocation stays the same
            // so bound RenderTypes don't need re-creation.
            TextureTarget oldFbo = f.fbo;
            TextureTarget newFbo = new TextureTarget(w, h, true, Minecraft.ON_OSX);
            newFbo.setClearColor(0.08f, 0.10f, 0.14f, 1.0f);
            f.fbo = newFbo;
            f.width = w;
            f.height = h;
            f.wrapper.setTarget(newFbo);
            f.lastRenderMs = 0L;  // force a fresh render next refresh
            try { oldFbo.destroyBuffers(); } catch (Exception ignored) {}
            return f.texLoc;
        }
        TextureTarget fbo = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        fbo.setClearColor(0.08f, 0.10f, 0.14f, 1.0f);
        int id = NEXT_ID.incrementAndGet();
        ResourceLocation loc = new ResourceLocation(DeceasedCC.MODID, "camera_feed_" + id);
        FBOTexture tex = new FBOTexture(fbo);
        Minecraft.getInstance().getTextureManager().register(loc, tex);
        Feed newFeed = new Feed(fbo, loc, tex, w, h);
        Feed existing = FEEDS.putIfAbsent(key, newFeed);
        if (existing != null) {
            Minecraft.getInstance().getTextureManager().release(loc);
            fbo.destroyBuffers();
            existing.lastRequestedMs = now;
            return existing.texLoc;
        }
        return loc;
    }

    /** Trigger a capture if the min-interval elapsed. Caller passes the
     *  current camera pos/yaw/pitch/fov each call — opts can change
     *  between calls (user reconfigured the feed via loadFromCamera2D). */
    public static boolean refreshIfDue(BlockPos projectorPos, BlockPos cameraPos,
                                        float yaw, float pitch, float fov) {
        Feed f = FEEDS.get(projectorPos.immutable());
        if (f == null) return false;
        long now = System.currentTimeMillis();
        f.lastRequestedMs = now;
        if (now - f.lastRenderMs < MIN_REFRESH_INTERVAL_MS) return false;
        boolean ok = CameraFeedRenderer.captureTo(f.fbo, cameraPos, yaw, pitch, fov);
        if (ok) f.lastRenderMs = now;
        return ok;
    }

    @Nullable
    public static ResourceLocation getTextureIfActive(BlockPos projectorPos) {
        Feed f = FEEDS.get(projectorPos.immutable());
        return f == null ? null : f.texLoc;
    }

    /** Called periodically to sweep stale feeds. */
    public static void sweepStale() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Feed>> it = FEEDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Feed> e = it.next();
            if (now - e.getValue().lastRequestedMs > STALE_TIMEOUT_MS) {
                Feed dead = e.getValue();
                Minecraft.getInstance().getTextureManager().release(dead.texLoc);
                try { dead.fbo.destroyBuffers(); } catch (Exception ignored) {}
                it.remove();
            }
        }
    }

    public static void release(BlockPos projectorPos) {
        Feed f = FEEDS.remove(projectorPos.immutable());
        if (f != null) {
            Minecraft.getInstance().getTextureManager().release(f.texLoc);
            try { f.fbo.destroyBuffers(); } catch (Exception ignored) {}
        }
    }
}
