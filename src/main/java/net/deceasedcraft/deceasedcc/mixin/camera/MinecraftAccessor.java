package net.deceasedcraft.deceasedcc.mixin.camera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Phase 8.2 — field accessors for Minecraft state we need to swap during
 * camera capture without triggering the public setter's side effects.
 *
 * <p>{@code mainRenderTarget} is {@code private final}; setter needs
 * {@code @Mutable} to strip final.
 *
 * <p>{@code cameraEntity} has a public {@code setCameraEntity} method but
 * that method calls {@code gameRenderer.checkEntityPostEffect()}, which
 * can reload post-process shaders. Swapping twice per capture × 2 Hz
 * cadence = visible flicker. This direct-field accessor avoids the
 * side effect — we only want the position/rotation source, not the
 * post-effect reset.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("mainRenderTarget")
    @Mutable
    void setMainRenderTarget(RenderTarget target);

    @Accessor("cameraEntity")
    Entity getCameraEntity();

    @Accessor("cameraEntity")
    void setCameraEntityDirect(Entity entity);
}
