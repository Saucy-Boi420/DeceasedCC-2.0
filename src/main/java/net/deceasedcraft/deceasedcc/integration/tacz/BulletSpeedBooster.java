package net.deceasedcraft.deceasedcc.integration.tacz;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.core.Integrations;
import net.deceasedcraft.deceasedcc.core.ModConfig;
import net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;

/**
 * Boosts TACZ bullet velocity by a configurable multiplier when the bullet
 * was fired by one of our turret shooters. Hooks {@link EntityJoinLevelEvent}
 * at LOW priority so this runs after TACZ sets the initial velocity.
 *
 * <p>TACZ's bullet class name changes between versions, so we identify by
 * the class name prefix {@code com.tacz.guns.entity.} rather than binding
 * a compile-time type.</p>
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID)
public final class BulletSpeedBooster {
    private static final String TACZ_PKG = "com.tacz.guns.entity";

    private BulletSpeedBooster() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!Integrations.tacz()) return;
        if (event.getLevel().isClientSide) return;
        Entity e = event.getEntity();
        if (!e.getClass().getName().startsWith(TACZ_PKG)) return;

        Entity owner = resolveOwner(e);
        if (!(owner instanceof TurretShooterEntity)) return;

        double mult = ModConfig.BULLET_SPEED_MULTIPLIER.get();
        if (Math.abs(mult - 1.0) < 1e-6) return;

        Vec3 v = e.getDeltaMovement();
        e.setDeltaMovement(v.scale(mult));
        // Bullet entities cache a velocity on themselves for integration;
        // try to find a float field named "speed" or similar and scale it too.
        scaleSpeedField(e, mult);
    }

    /** TACZ bullets expose an owner via either a method named {@code getOwner}
     *  or a public field. We probe reflectively. */
    private static Entity resolveOwner(Entity e) {
        try {
            Method m = e.getClass().getMethod("getOwner");
            Object o = m.invoke(e);
            if (o instanceof Entity ent) return ent;
        } catch (Throwable ignored) {}
        // Fall back: some TACZ entity subclasses expose an "owner" field directly.
        return null;
    }

    private static void scaleSpeedField(Entity e, double mult) {
        Class<?> c = e.getClass();
        while (c != null && c.getName().startsWith(TACZ_PKG)) {
            for (var f : c.getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                if ((f.getType() == float.class || f.getType() == double.class)
                        && (n.contains("speed") || n.contains("velocity"))) {
                    try {
                        f.setAccessible(true);
                        if (f.getType() == float.class) {
                            float v = f.getFloat(e);
                            f.setFloat(e, (float) (v * mult));
                        } else {
                            double v = f.getDouble(e);
                            f.setDouble(e, v * mult);
                        }
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
    }
}
