package net.deceasedcraft.deceasedcc.mixin.camera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Phase 8.2 — accessor for LevelRenderer's "Fabulous" graphics mode
 * framebuffer fields. When we invoke {@code GameRenderer.renderLevel} to
 * draw into our custom {@code TextureTarget} (for the camera feed),
 * Fabulous mode would otherwise route the translucent / item-entity /
 * weather passes into its own dedicated framebuffers — wrong targets
 * for our capture. We null these for the duration of the capture and
 * restore afterward; MC falls back to rendering directly into the bound
 * main target.
 *
 * <p>Pattern borrowed from SecurityCraft's FrameFeedHandler (MIT licensed,
 * see NOTICE). No code is copied verbatim — only the approach.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("translucentTarget")
    RenderTarget getTranslucentTarget();

    @Accessor("translucentTarget")
    @Mutable
    void setTranslucentTarget(RenderTarget target);

    @Accessor("itemEntityTarget")
    RenderTarget getItemEntityTarget();

    @Accessor("itemEntityTarget")
    @Mutable
    void setItemEntityTarget(RenderTarget target);

    @Accessor("weatherTarget")
    RenderTarget getWeatherTarget();

    @Accessor("weatherTarget")
    @Mutable
    void setWeatherTarget(RenderTarget target);

    @Accessor("cloudsTarget")
    RenderTarget getCloudsTarget();

    @Accessor("cloudsTarget")
    @Mutable
    void setCloudsTarget(RenderTarget target);

    @Accessor("transparencyChain")
    PostChain getTransparencyChain();

    @Accessor("transparencyChain")
    @Mutable
    void setTransparencyChain(PostChain chain);
}
