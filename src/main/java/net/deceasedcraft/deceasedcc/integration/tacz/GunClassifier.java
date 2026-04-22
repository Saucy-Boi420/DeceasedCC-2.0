package net.deceasedcraft.deceasedcc.integration.tacz;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.core.Integrations;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heuristically classifies every TACZ-registered gun into a weapon class
 * (pistol, SMG, rifle, etc.) and computes the average RPM + bullet speed
 * for each class at server start. These averages are the "category default"
 * used by {@link TaczBridge} when a gun ships with no native RPM (some
 * third-party packs ship incomplete data).
 *
 * <p>TACZ doesn't expose a weapon-type field on {@code GunData}, so we
 * infer one from ammo caliber + bolt type + RPM + magazine size + the
 * gun's registry name. The classification is DeceasedCC-local — this
 * class doesn't mutate anything on TACZ's side.</p>
 *
 * <p>Fallback chain for a gun's fire rate:
 * <ol>
 *   <li>The gun's own {@code GunData.roundsPerMinute} if &gt; 0.</li>
 *   <li>The average RPM of its classified category.</li>
 *   <li>The hardcoded {@link #HARDCODED_FALLBACK_RPM}.</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID)
public final class GunClassifier {
    public static final int HARDCODED_FALLBACK_RPM = 600;

    public enum GunClass {
        PISTOL, SMG, RIFLE, DMR, LMG, SHOTGUN, SNIPER, LAUNCHER, UNKNOWN;
    }

    /** Populated at ServerStarted. Null means the classifier never ran. */
    private static volatile Map<GunClass, Integer> CATEGORY_RPM = null;
    /** Per-gun cached classification so we don't re-infer on every shot. */
    private static final ConcurrentHashMap<String, GunClass> CLASS_BY_GUN = new ConcurrentHashMap<>();
    /** Per-gun cached bullet speed (blocks/sec) read from BulletData.speed. */
    private static final ConcurrentHashMap<String, Float> BULLET_SPEED_BY_GUN = new ConcurrentHashMap<>();

    private GunClassifier() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Integrations.tacz()) return;
        try {
            computeCategoryAverages();
        } catch (Throwable t) {
            DeceasedCC.LOGGER.warn("GunClassifier: failed to build category averages: {}", t.toString());
            CATEGORY_RPM = new EnumMap<>(GunClass.class);
        }
    }

    /** Look up a class for the given gun stack, caching the result. */
    public static GunClass classOf(ItemStack gun) {
        if (gun.isEmpty()) return GunClass.UNKNOWN;
        try {
            TaczBridge.TaczRefl r = TaczBridge.reflOrNull();
            if (r == null) return GunClass.UNKNOWN;
            Object iGunImpl = r.getIGunOrNull.invoke(null, gun);
            if (iGunImpl == null) return GunClass.UNKNOWN;
            Object gunId = r.getGunId.invoke(iGunImpl, gun);
            if (gunId == null) return GunClass.UNKNOWN;
            String key = gunId.toString();
            GunClass cached = CLASS_BY_GUN.get(key);
            if (cached != null) return cached;
            Object opt = r.getCommonGunIndex.invoke(null, gunId);
            if (opt == null) { CLASS_BY_GUN.put(key, GunClass.UNKNOWN); return GunClass.UNKNOWN; }
            if (!(boolean) opt.getClass().getMethod("isPresent").invoke(opt)) {
                CLASS_BY_GUN.put(key, GunClass.UNKNOWN); return GunClass.UNKNOWN;
            }
            Object gunIndex = opt.getClass().getMethod("get").invoke(opt);
            Object gunData = gunIndex.getClass().getMethod("getGunData").invoke(gunIndex);
            GunClass c = classify((ResourceLocation) gunId, gunData);
            CLASS_BY_GUN.put(key, c);
            return c;
        } catch (Throwable t) {
            return GunClass.UNKNOWN;
        }
    }

    /** RPM to fall back to when a gun's own RPM is 0. */
    public static int categoryFallbackRpm(GunClass c) {
        Map<GunClass, Integer> avgs = CATEGORY_RPM;
        if (avgs == null) return HARDCODED_FALLBACK_RPM;
        Integer v = avgs.get(c);
        return v == null || v <= 0 ? HARDCODED_FALLBACK_RPM : v;
    }

    /** Bullet speed for the given gun, cached. Falls back to 150 blocks/sec
     *  (typical pistol-rifle TACZ value) if the lookup fails. */
    public static float bulletSpeed(ItemStack gun) {
        if (gun.isEmpty()) return 150f;
        try {
            TaczBridge.TaczRefl r = TaczBridge.reflOrNull();
            if (r == null) return 150f;
            Object iGunImpl = r.getIGunOrNull.invoke(null, gun);
            if (iGunImpl == null) return 150f;
            Object gunId = r.getGunId.invoke(iGunImpl, gun);
            if (gunId == null) return 150f;
            String key = gunId.toString();
            Float cached = BULLET_SPEED_BY_GUN.get(key);
            if (cached != null) return cached;
            Object opt = r.getCommonGunIndex.invoke(null, gunId);
            if (opt == null) { BULLET_SPEED_BY_GUN.put(key, 150f); return 150f; }
            if (!(boolean) opt.getClass().getMethod("isPresent").invoke(opt)) {
                BULLET_SPEED_BY_GUN.put(key, 150f); return 150f;
            }
            Object gunIndex = opt.getClass().getMethod("get").invoke(opt);
            Object gunData = gunIndex.getClass().getMethod("getGunData").invoke(gunIndex);
            Object bullet = gunData.getClass().getMethod("getBulletData").invoke(gunData);
            Object speed = bullet.getClass().getMethod("getSpeed").invoke(bullet);
            float s = speed instanceof Float f ? f : (speed instanceof Number n ? n.floatValue() : 150f);
            if (s <= 0) s = 150f;
            BULLET_SPEED_BY_GUN.put(key, s);
            return s;
        } catch (Throwable t) {
            return 150f;
        }
    }

    // ---- internals -------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void computeCategoryAverages() throws Exception {
        Class<?> timelessAPI = Class.forName("com.tacz.guns.api.TimelessAPI");
        Method getAll = timelessAPI.getMethod("getAllCommonGunIndex");
        Set<Map.Entry<ResourceLocation, Object>> all =
                (Set<Map.Entry<ResourceLocation, Object>>) (Object) getAll.invoke(null);

        Map<GunClass, int[]> bucket = new EnumMap<>(GunClass.class);
        for (GunClass c : GunClass.values()) bucket.put(c, new int[] { 0, 0 }); // sum, count

        for (Map.Entry<ResourceLocation, Object> entry : all) {
            ResourceLocation gunId = entry.getKey();
            Object gunIndex = entry.getValue();
            Object gunData = gunIndex.getClass().getMethod("getGunData").invoke(gunIndex);
            int rpm = (int) gunData.getClass().getMethod("getRoundsPerMinute").invoke(gunData);
            GunClass c = classify(gunId, gunData);
            if (rpm > 0) {
                int[] row = bucket.get(c);
                row[0] += rpm;
                row[1]++;
            }
            CLASS_BY_GUN.put(gunId.toString(), c);
        }

        Map<GunClass, Integer> avgs = new EnumMap<>(GunClass.class);
        for (var e : bucket.entrySet()) {
            int[] row = e.getValue();
            avgs.put(e.getKey(), row[1] == 0 ? 0 : row[0] / row[1]);
        }
        CATEGORY_RPM = avgs;

        DeceasedCC.LOGGER.info("GunClassifier: cataloged {} guns — per-class avg RPM {}",
                all.size(), avgs);
    }

    /** Infer a weapon class from TACZ's gun data fields. */
    private static GunClass classify(ResourceLocation gunId, Object gunData) {
        try {
            Object ammoIdObj = gunData.getClass().getMethod("getAmmoId").invoke(gunData);
            ResourceLocation ammoId = (ResourceLocation) ammoIdObj;
            String ammo = ammoId == null ? "" : ammoId.getPath();

            Object boltObj = gunData.getClass().getMethod("getBolt").invoke(gunData);
            String bolt = boltObj == null ? "" : boltObj.toString();

            int rpm = (int) gunData.getClass().getMethod("getRoundsPerMinute").invoke(gunData);
            int mag = (int) gunData.getClass().getMethod("getAmmoAmount").invoke(gunData);
            String name = gunId.getPath().toLowerCase();

            // Explicit ammo-type buckets first.
            if (ammo.equals("40mm") || ammo.equals("37mm") || ammo.contains("grenade")) return GunClass.LAUNCHER;
            if (ammo.equals("12g") || ammo.contains("slug") || ammo.contains("shotshell")) return GunClass.SHOTGUN;

            // Bolt-action anything-but-shotgun → sniper.
            if ("MANUAL_ACTION".equals(bolt)) return GunClass.SNIPER;

            // Large-caliber long guns.
            if (ammo.equals("50bmg") || ammo.equals("338") || ammo.equals("762x54")
                    || ammo.equals("308") || ammo.equals("30_06")) {
                if (rpm >= 450 || mag >= 40) return GunClass.LMG;   // full-auto large calibre
                if (rpm <= 120) return GunClass.SNIPER;             // bolt / semi precision
                return GunClass.DMR;
            }

            // Pistol cartridges — distinguish handgun vs. SMG by RPM.
            if (ammo.equals("9mm") || ammo.equals("45acp") || ammo.equals("357mag")
                    || ammo.equals("50ae") || ammo.equals("46x30") || ammo.equals("57x28")) {
                if (rpm >= 600 || mag >= 25) return GunClass.SMG;
                return GunClass.PISTOL;
            }

            // Intermediate rifle rounds.
            if (ammo.equals("556x45") || ammo.equals("762x39")) {
                if (mag >= 75 || rpm >= 800) return GunClass.LMG;
                return GunClass.RIFLE;
            }

            // Name-based fall-through for custom packs that don't fit.
            if (contains(name, "sniper", "awp", "barrett", "m107", "m200", "ksr", "cheytac")) return GunClass.SNIPER;
            if (contains(name, "pistol", "revolver", "deagle", "glock", "1911", "p226", "cz75")) return GunClass.PISTOL;
            if (contains(name, "smg", "ump", "mp5", "mp7", "vector", "p90", "uzi"))           return GunClass.SMG;
            if (contains(name, "shotgun", "m870", "remington", "saiga", "dbl"))              return GunClass.SHOTGUN;
            if (contains(name, "lmg", "m249", "m60", "mg42", "rpk", "pkm", "minigun"))       return GunClass.LMG;
            if (contains(name, "rifle", "ak", "m4", "m16", "scar", "aug", "g36"))            return GunClass.RIFLE;
            if (contains(name, "launcher", "rpg", "grenade"))                                 return GunClass.LAUNCHER;

            return GunClass.UNKNOWN;
        } catch (Throwable t) {
            return GunClass.UNKNOWN;
        }
    }

    private static boolean contains(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }
}
