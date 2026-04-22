package net.deceasedcraft.deceasedcc.integration.tacz;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Swaps the {@link DamageSource}'s type (and causingEntity for player-controlled
 * shots) when a {@link TurretShooterEntity} hits a living entity, picking a
 * random variant from one of three categories so the resulting death message
 * is randomized.
 *
 * <p>Implementation note: the previous version cancelled the LivingHurtEvent
 * and re-invoked {@code victim.hurt(newSrc, ev.getAmount())}. That double-
 * applied armor reduction (LivingHurtEvent fires post-armor; re-entering
 * hurt() re-applies armor) and ate damage to vanilla invulnerability frames
 * on rapid fire. The current approach mutates the existing DamageSource in
 * place via reflection — vanilla continues its single damage pass with the
 * correctly-reduced amount, and the death-message lookup later uses the new
 * type/causingEntity.</p>
 *
 * <p>Reflection target: {@link DamageSource#type} (private final {@code Holder<DamageType>})
 * and {@link DamageSource#causingEntity} (private final {@code Entity}). Field
 * names are stable across the 1.20.1 Forge mapping channel ({@code official}).</p>
 */
public final class TurretDamageRewriter {

    @SuppressWarnings("unchecked")
    private static final ResourceKey<DamageType>[] TURRET_VARIANTS            = buildKeys("turret_var");
    @SuppressWarnings("unchecked")
    private static final ResourceKey<DamageType>[] TURRET_CONTROLLED_VARIANTS = buildKeys("turret_controlled_var");
    @SuppressWarnings("unchecked")
    private static final ResourceKey<DamageType>[] TURRET_SELF_VARIANTS       = buildKeys("turret_self_var");

    // We can't use getDeclaredField("type") / "causingEntity" — those are the
    // mojmap/dev names. At runtime the production jar has SRG-mapped field
    // names (e.g. f_268712_) and string literals don't get remapped by reobf.
    // Instead we walk the field list by type: DamageSource has exactly one
    // Holder<?> field (type) and two Entity fields (causingEntity first, then
    // directEntity per source order). HotSpot preserves declaration order in
    // getDeclaredFields(), so the first Entity is causingEntity.
    //
    // If reflection fails (e.g. Forge re-shapes DamageSource in a future
    // release) we log + continue — the rewriter becomes a no-op and vanilla
    // death messages render instead of ours. Better than crashing mod load.
    private static final Field FIELD_TYPE;
    private static final Field FIELD_CAUSING_ENTITY;

    static {
        Field typeField = null;
        Field firstEntityField = null;
        try {
            for (Field f : DamageSource.class.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (typeField == null && ft == net.minecraft.core.Holder.class) {
                    typeField = f;
                } else if (firstEntityField == null && ft == Entity.class) {
                    firstEntityField = f;
                }
            }
            if (typeField != null) typeField.setAccessible(true);
            if (firstEntityField != null) firstEntityField.setAccessible(true);
        } catch (Throwable t) {
            DeceasedCC.LOGGER.error(
                    "TurretDamageRewriter: failed to locate DamageSource fields via reflection. "
                            + "Death messages will fall back to vanilla.", t);
        }
        FIELD_TYPE = typeField;
        FIELD_CAUSING_ENTITY = firstEntityField;
        if (FIELD_TYPE == null || FIELD_CAUSING_ENTITY == null) {
            DeceasedCC.LOGGER.warn(
                    "TurretDamageRewriter disabled: could not find expected DamageSource fields "
                            + "(Holder<DamageType> type / Entity causingEntity).");
        }
    }

    @SuppressWarnings("unchecked")
    private static ResourceKey<DamageType>[] buildKeys(String prefix) {
        ResourceKey<DamageType>[] arr = new ResourceKey[10];
        for (int i = 0; i < 10; i++) {
            arr[i] = ResourceKey.create(Registries.DAMAGE_TYPE,
                    new ResourceLocation(DeceasedCC.MODID, prefix + i));
        }
        return arr;
    }

    private TurretDamageRewriter() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.register(TurretDamageRewriter.class);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onHurt(LivingHurtEvent ev) {
        if (FIELD_TYPE == null || FIELD_CAUSING_ENTITY == null) return;
        DamageSource src = ev.getSource();
        TurretShooterEntity shooter = extractShooter(src);
        if (shooter == null) return;

        LivingEntity victim = ev.getEntity();
        if (victim.level().isClientSide) return;
        ServerLevel sl = (ServerLevel) victim.level();
        MinecraftServer server = sl.getServer();
        if (server == null) return;

        UUID controlling = lookupControllingPlayer(sl, shooter);

        ResourceKey<DamageType>[] pool;
        Entity newCausingEntity;
        if (controlling != null && controlling.equals(victim.getUUID())) {
            pool = TURRET_SELF_VARIANTS;
            newCausingEntity = victim;
        } else if (controlling != null) {
            pool = TURRET_CONTROLLED_VARIANTS;
            newCausingEntity = server.getPlayerList().getPlayer(controlling);
            if (newCausingEntity == null) newCausingEntity = shooter;
        } else {
            pool = TURRET_VARIANTS;
            newCausingEntity = src.getEntity(); // keep whatever vanilla had
        }

        ResourceKey<DamageType> chosen = pool[ThreadLocalRandom.current().nextInt(pool.length)];
        try {
            Holder<DamageType> newType = sl.registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(chosen);
            FIELD_TYPE.set(src, newType);
            FIELD_CAUSING_ENTITY.set(src, newCausingEntity);
            // No cancel, no re-invoke: vanilla finishes the single damage
            // pass with the now-mutated source. Death message uses the new
            // type's message_id; damage amount is untouched.
        } catch (Throwable t) {
            DeceasedCC.LOGGER.warn("Turret damage rewrite skipped (mutation failed)", t);
        }
    }

    private static TurretShooterEntity extractShooter(DamageSource src) {
        Entity attacker = src.getEntity();
        if (attacker instanceof TurretShooterEntity ts) return ts;
        Entity direct = src.getDirectEntity();
        if (direct instanceof Projectile p && p.getOwner() instanceof TurretShooterEntity ts) return ts;
        return null;
    }

    private static UUID lookupControllingPlayer(ServerLevel sl, TurretShooterEntity shooter) {
        BlockPos pos = shooter.ownerTurretPos;
        if (pos == null) return null;
        BlockEntity be = sl.getBlockEntity(pos);
        if (be instanceof TurretMountBlockEntity tm) return tm.state.controllingPlayer;
        return null;
    }
}
