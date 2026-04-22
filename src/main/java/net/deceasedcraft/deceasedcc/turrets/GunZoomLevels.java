package net.deceasedcraft.deceasedcc.turrets;

import net.deceasedcraft.deceasedcc.integration.tacz.GunClassifier;
import net.minecraft.world.item.ItemStack;

/**
 * Per-gun-class zoom scale tables for the Turret Remote's right-click scope
 * cycling. Pure deceasedcc implementation — we deliberately DO NOT read TACZ's
 * scope attachments here. Zoom levels are picked purely from the gun
 * classification so behaviour is predictable across modpacks.
 * <p>
 * Right-click cycles: 1× → levels[0] → levels[1] → ... → 1× → ...
 */
public final class GunZoomLevels {
    private GunZoomLevels() {}

    /** Base unzoomed state. Index 0 in the cycle. */
    public static final float NONE = 1.0f;

    /** Look up the zoom ladder for a gun stack. Never null; empty array
     *  means "no zoom available" (only the 1× base state). */
    public static float[] forGun(ItemStack gun) {
        return forClass(GunClassifier.classOf(gun));
    }

    public static float[] forClass(GunClassifier.GunClass cat) {
        if (cat == null) return new float[] { 1.5f };
        switch (cat) {
            case PISTOL:   return new float[] { 1.25f };
            case SMG:      return new float[] { 1.5f };
            case RIFLE:    return new float[] { 1.5f, 2.0f };
            case DMR:      return new float[] { 2.5f, 5.0f, 7.5f, 10.0f };
            case SNIPER:   return new float[] { 2.5f, 5.0f, 7.5f, 10.0f };
            case LMG:      return new float[] { 1.5f, 2.0f };
            case SHOTGUN:  return new float[] { 1.25f };
            case LAUNCHER: return new float[] { 2.5f, 5.0f, 7.5f, 10.0f };
            default:       return new float[] { 1.5f };
        }
    }

    /** Human-readable label for a zoom factor — for the HUD overlay. */
    public static String labelFor(float zoom) {
        if (zoom <= NONE + 0.01f) return "—";
        // Print 1 decimal only when non-integer (1.5×, 2× style)
        if (Math.abs(zoom - Math.round(zoom)) < 0.01f) {
            return Math.round(zoom) + "\u00d7";
        }
        return String.format("%.2f\u00d7", zoom).replace(".00", "");
    }
}
