package net.deceasedcraft.deceasedcc.turrets;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;

/**
 * Keeps non-player mobs from ever targeting the turret's invisible shooter
 * entity. Covers:
 *   - Direct retaliation goals (HurtByTargetGoal, etc.) that would latch
 *     onto a Mob instance.
 *   - Any mod that uses a sound-aware AI goal whose final step is
 *     {@code Mob.setTarget(<noise source>)} — if the "source" happens to
 *     be the shooter entity, we veto the target change.
 *
 * <p>Combined with {@link TurretShooterEntity#gameEvent} suppression, this
 * kills the "zombies run to the turret on fire" behavior. Players' own
 * targeting isn't affected — only Mob subclasses fire this event.</p>
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TurretAggroSuppressor {
    private TurretAggroSuppressor() {}

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent ev) {
        LivingEntity newTarget = ev.getNewTarget();
        if (newTarget instanceof TurretShooterEntity) {
            // Cancel the retarget so the mob keeps its old target (or none).
            // setCanceled is enough — Forge propagates that back as "don't
            // change target".
            ev.setCanceled(true);
        }
    }

    // Periodic "nav + target janitor" — walks every server dim's turret
    // list and aggressively cleans up any mob that's interested in a
    // turret. Three cases handled:
    //
    //   (a) mob's attack target IS the TurretShooterEntity → clear target
    //       + nav. Catches HurtByTarget retaliation and any AI goal that
    //       bypasses LivingChangeTargetEvent.
    //
    //   (b) mob's attack target is within 2 blocks of a turret AND mob's
    //       last-hurt-by is the shooter → clear target. Catches aggro
    //       chains where the shooter damaged the mob which now thinks the
    //       turret position is hostile.
    //
    //   (c) mob has NO attack target but IS navigating within 5 blocks
    //       of a turret → stop nav. Catches pack-specific AI goals that
    //       path to gun-noise origins via PathNavigation.moveTo.
    //
    // Runs every 4 server ticks (5 Hz). Per-turret scan radius is 48.
    // O(turrets × nearby_mobs) — with a handful of turrets and typical
    // mob densities, it's cheap enough.
    // Run every tick. The pack's AI goals re-pathfind every tick too, so
    // anything slower leaves gaps where zombies make progress between
    // janitor sweeps. One-tick cadence is cheap because the inner loops
    // are bounded by mobs-within-48-of-each-turret.
    private static final double JANITOR_MOB_SCAN_RADIUS = 48.0;
    private static final double NAV_TARGET_CLOSE_SQ = 8.0 * 8.0;
    private static final double TARGET_NEAR_TURRET_SQ = 3.0 * 3.0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        var server = ev.getServer();
        if (server == null) return;
        for (ServerLevel sl : server.getAllLevels()) {
            Collection<BlockPos> turrets =
                    net.deceasedcraft.deceasedcc.core.TurretRegistry.positionsInDim(sl);
            if (turrets.isEmpty()) continue;
            for (BlockPos t : turrets) {
                var box = new net.minecraft.world.phys.AABB(t).inflate(JANITOR_MOB_SCAN_RADIUS);
                var mobs = sl.getEntitiesOfClass(Mob.class, box, m -> true);
                for (Mob mob : mobs) {
                    LivingEntity target = mob.getTarget();

                    // (a) direct shooter target — purge it.
                    if (target instanceof TurretShooterEntity) {
                        mob.setTarget(null);
                        mob.getNavigation().stop();
                        target = null;
                    }

                    // (b) target is near a turret AND the mob's recent
                    //     damage source was the shooter — unpin.
                    if (target != null) {
                        double tdx = target.getX() - (t.getX() + 0.5);
                        double tdy = target.getY() - (t.getY() + 0.5);
                        double tdz = target.getZ() - (t.getZ() + 0.5);
                        if (tdx * tdx + tdy * tdy + tdz * tdz <= TARGET_NEAR_TURRET_SQ
                                && mob.getLastHurtByMob() instanceof TurretShooterEntity) {
                            mob.setTarget(null);
                            mob.setLastHurtByMob(null);
                            mob.getNavigation().stop();
                            target = null;
                        }
                    }

                    // (c) targetless wanderer heading toward a turret.
                    if (target == null) {
                        PathNavigation nav = mob.getNavigation();
                        BlockPos navTarget = nav.getTargetPos();
                        if (navTarget != null) {
                            double dx = navTarget.getX() - t.getX();
                            double dy = navTarget.getY() - t.getY();
                            double dz = navTarget.getZ() - t.getZ();
                            if (dx * dx + dy * dy + dz * dz <= NAV_TARGET_CLOSE_SQ) {
                                nav.stop();
                            }
                        }
                    }
                }
            }
        }
    }
}
