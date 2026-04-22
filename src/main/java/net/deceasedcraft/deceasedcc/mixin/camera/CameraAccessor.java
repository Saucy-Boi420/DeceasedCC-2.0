package net.deceasedcraft.deceasedcc.mixin.camera;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Phase 8.2 — accessor for {@link Camera}'s private lerp fields. Marker
 * entity has 0 eye height, but Camera.setup interpolates from the prior
 * frame's eyeHeight — if that was non-zero (player was the camera
 * previously) the first capture frame draws at the wrong Y until the
 * lerp converges. We force both to 0 so the capture origin is exactly
 * the marker position.
 */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("eyeHeight")
    float getEyeHeight();

    @Accessor("eyeHeight")
    void setEyeHeight(float value);

    @Accessor("eyeHeightOld")
    float getEyeHeightOld();

    @Accessor("eyeHeightOld")
    void setEyeHeightOld(float value);
}
