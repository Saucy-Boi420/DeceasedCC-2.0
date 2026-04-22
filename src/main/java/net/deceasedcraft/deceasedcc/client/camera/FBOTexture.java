package net.deceasedcraft.deceasedcc.client.camera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Phase 8.2 — adapter that makes a {@link RenderTarget}'s color attachment
 * usable anywhere MC wants an {@link AbstractTexture}. Returns the FBO's
 * color texture ID from {@link #getId()} so {@code AbstractTexture.bind}
 * binds the FBO directly — no per-frame GPU→CPU→GPU copy.
 *
 * <p>{@link #releaseId()} is overridden to a no-op so TextureManager
 * unregistering doesn't destroy the FBO's texture (the FBO owns it).
 */
public class FBOTexture extends AbstractTexture {
    private volatile RenderTarget target;

    public FBOTexture(RenderTarget target) {
        this.target = target;
    }

    /** Swap the backing RenderTarget (e.g., when CameraFeedManager
     *  resizes the FBO). ResourceLocation registration is preserved. */
    public void setTarget(RenderTarget target) {
        this.target = target;
    }

    @Override
    public void load(ResourceManager rm) {
        // No-op. The underlying GL texture is owned by the RenderTarget.
    }

    @Override
    public int getId() {
        return target.getColorTextureId();
    }

    @Override
    public void releaseId() {
        // No-op. The FBO owns the texture; releasing from the TextureManager
        // must NOT free it (the FBO will reuse it for subsequent captures).
    }
}
