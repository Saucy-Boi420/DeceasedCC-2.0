package net.deceasedcraft.deceasedcc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** No-op renderer for the invisible turret-shooter mob. Required because
 *  any client-side Mob needs a registered EntityRenderer, even an empty
 *  one. We intentionally draw nothing — the turret block's own BER
 *  handles the visible gun. */
@OnlyIn(Dist.CLIENT)
public class TurretShooterRenderer extends EntityRenderer<TurretShooterEntity> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft", "textures/misc/white.png");

    public TurretShooterRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0f;
    }

    @Override
    public void render(TurretShooterEntity entity, float yaw, float partialTick,
                       PoseStack pose, MultiBufferSource buf, int light) {
        // intentionally empty
    }

    @Override
    public ResourceLocation getTextureLocation(TurretShooterEntity entity) {
        return TEXTURE;
    }

    @Override public boolean shouldRender(TurretShooterEntity e, net.minecraft.client.renderer.culling.Frustum f, double x, double y, double z) {
        return false;
    }
}
