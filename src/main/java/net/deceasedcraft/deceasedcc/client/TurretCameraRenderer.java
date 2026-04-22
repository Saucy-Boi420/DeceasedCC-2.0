package net.deceasedcraft.deceasedcc.client;

import net.deceasedcraft.deceasedcc.turrets.TurretCameraEntity;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** No-op renderer. The camera anchor is never added to the world, but
 *  Minecraft's EntityRenderDispatcher still needs a registered renderer
 *  for the TURRET_CAMERA EntityType. */
@OnlyIn(Dist.CLIENT)
public class TurretCameraRenderer extends EntityRenderer<TurretCameraEntity> {
    private static final ResourceLocation PLACEHOLDER =
            new ResourceLocation("minecraft", "textures/misc/white.png");

    public TurretCameraRenderer(EntityRendererProvider.Context ctx) { super(ctx); }

    @Override public ResourceLocation getTextureLocation(TurretCameraEntity e) { return PLACEHOLDER; }
    @Override public boolean shouldRender(TurretCameraEntity e, Frustum frustum,
                                          double x, double y, double z) { return false; }
}
