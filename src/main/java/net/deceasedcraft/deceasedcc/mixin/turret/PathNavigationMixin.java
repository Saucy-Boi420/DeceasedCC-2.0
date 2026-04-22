package net.deceasedcraft.deceasedcc.mixin.turret;

import net.deceasedcraft.deceasedcc.core.TurretRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * Blocks any {@link PathNavigation#moveTo(double, double, double, double)}
 * call whose destination is within a couple of blocks of any registered
 * turret. This is the surgical fix for "pack's AI goal paths zombies to
 * gun-shot origins" behavior — the previous post-hoc janitor (run once
 * per tick, clearing nav after it's been set) left small windows where
 * the mob could make forward progress. Rejecting the nav call at source
 * closes those windows.
 *
 * <p>Only applies to {@link Mob} navigators on the server. Players are
 * unaffected (PlayerController doesn't use PathNavigation). Mobs with a
 * legitimate attack target are also exempt — a zombie fighting a player
 * who happens to stand near a turret still gets to path to the player.
 */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {

    // The `mob` field is protected on PathNavigation; shadow it so we can
    // read it from the mixin without reflecting.
    @Shadow protected Mob mob;

    @Inject(method = "moveTo(DDDD)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void deceasedcc$rejectPathsToTurrets(double x, double y, double z, double speed,
                                                  CallbackInfoReturnable<Boolean> cir) {
        Mob m = this.mob;
        if (m == null) return;
        if (m.level().isClientSide) return;
        if (!(m.level() instanceof ServerLevel sl)) return;
        // Mobs with a legitimate attack target are left alone — they're
        // in a real fight, don't interfere.
        if (m.getTarget() != null) return;

        Collection<BlockPos> turrets = TurretRegistry.positionsInDim(sl);
        if (turrets.isEmpty()) return;

        for (BlockPos t : turrets) {
            double dx = x - (t.getX() + 0.5);
            double dy = y - (t.getY() + 0.5);
            double dz = z - (t.getZ() + 0.5);
            // 4 blocks in each axis (16 squared). If the mob wants to path
            // to anywhere within that bubble of a turret, veto.
            if (dx * dx + dy * dy + dz * dz <= 16.0) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
