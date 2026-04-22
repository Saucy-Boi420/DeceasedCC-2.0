package net.deceasedcraft.deceasedcc.turrets;

import net.deceasedcraft.deceasedcc.core.ModItems;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;

/**
 * Identity + stats for a single turret upgrade item. Reworked in 1.4.5 to
 * use <b>percentage boosts</b> that stack additively.
 *
 * <ul>
 *   <li>Basic tier  → +25% to its stat per copy</li>
 *   <li>Advanced    → +50%</li>
 * </ul>
 *
 * Two basic fire-rate upgrades stack to +50% (1.5×). Four advanced stack to
 * +200% (3×). Much more sane than the old 2×/4× multiplicative scheme where
 * 4 advanced = 16× — which the gun could never actually deliver anyway
 * because TACZ's internal cooldown capped it at the gun's native RPM.
 * With percentage boosts we also bypass TACZ's cooldown on the firing path
 * so the computed rate is achievable.
 */
public enum TurretUpgrade {
    FIRE_RATE_BASIC   (Stat.FIRE_RATE,  Tier.BASIC,    25f),
    FIRE_RATE_ADV     (Stat.FIRE_RATE,  Tier.ADVANCED, 50f),
    TURN_SPEED_BASIC  (Stat.TURN_SPEED, Tier.BASIC,    25f),
    TURN_SPEED_ADV    (Stat.TURN_SPEED, Tier.ADVANCED, 50f),
    RANGE_BASIC       (Stat.RANGE,      Tier.BASIC,    25f),
    RANGE_ADV         (Stat.RANGE,      Tier.ADVANCED, 50f),
    // Power upgrades — only valid inside the Turret Remote. Efficiency
    // multiplier is (1 + sum(percentBoost) / 100). Basic = +100% eff
    // (→ 2×, halves drain), Advanced = +200% eff (→ 3×, third of drain).
    POWER_BASIC       (Stat.POWER,      Tier.BASIC,    100f),
    POWER_ADV         (Stat.POWER,      Tier.ADVANCED, 200f);

    public final Stat stat;
    public final Tier tier;
    /** Percentage boost per installed item. Stacks additively. */
    public final float percentBoost;

    TurretUpgrade(Stat stat, Tier tier, float percentBoost) {
        this.stat = stat;
        this.tier = tier;
        this.percentBoost = percentBoost;
    }

    public enum Stat { FIRE_RATE, TURN_SPEED, RANGE, POWER }
    public enum Tier { BASIC, ADVANCED }

    public static TurretUpgrade fromItem(Item item) {
        if (item == null) return null;
        if (item == ModItems.UPGRADE_FIRE_RATE_BASIC.get())    return FIRE_RATE_BASIC;
        if (item == ModItems.UPGRADE_FIRE_RATE_ADVANCED.get()) return FIRE_RATE_ADV;
        if (item == ModItems.UPGRADE_TURN_SPEED_BASIC.get())   return TURN_SPEED_BASIC;
        if (item == ModItems.UPGRADE_TURN_SPEED_ADVANCED.get())return TURN_SPEED_ADV;
        if (item == ModItems.UPGRADE_RANGE_BASIC.get())        return RANGE_BASIC;
        if (item == ModItems.UPGRADE_RANGE_ADVANCED.get())     return RANGE_ADV;
        if (item == ModItems.UPGRADE_POWER_BASIC.get())        return POWER_BASIC;
        if (item == ModItems.UPGRADE_POWER_ADVANCED.get())     return POWER_ADV;
        return null;
    }

    public static TurretUpgrade fromStack(ItemStack s) {
        return s == null || s.isEmpty() ? null : fromItem(s.getItem());
    }

    /** Sum percentage boosts per stat across all slots, convert to a
     *  multiplier. No upgrades → 1.0× baseline. */
    public static EnumMap<Stat, Float> effectiveMultipliers(ItemStack[] slots) {
        EnumMap<Stat, Float> boost = new EnumMap<>(Stat.class);
        for (Stat s : Stat.values()) boost.put(s, 0f);
        if (slots != null) {
            for (ItemStack s : slots) {
                TurretUpgrade u = fromStack(s);
                if (u == null) continue;
                boost.merge(u.stat, u.percentBoost, Float::sum);
            }
        }
        EnumMap<Stat, Float> result = new EnumMap<>(Stat.class);
        for (var e : boost.entrySet()) {
            result.put(e.getKey(), 1f + e.getValue() / 100f);
        }
        return result;
    }

    public static boolean allowedInBasic(ItemStack s) {
        TurretUpgrade u = fromStack(s);
        return u != null && u.tier == Tier.BASIC && u.stat != Stat.POWER;
    }

    public static boolean allowedInAdvanced(ItemStack s) {
        TurretUpgrade u = fromStack(s);
        return u != null && u.stat != Stat.POWER;
    }

    /** Remote accepts only Basic Range + any Power upgrade. */
    public static boolean allowedInRemote(ItemStack s) {
        TurretUpgrade u = fromStack(s);
        if (u == null) return false;
        if (u.stat == Stat.POWER) return true;
        return u.stat == Stat.RANGE && u.tier == Tier.BASIC;
    }
}
