package net.deceasedcraft.deceasedcc.core;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.minecraftforge.fml.ModList;

public final class Integrations {
    private Integrations() {}

    public static final String CC_TWEAKED = "computercraft";
    public static final String TACZ = "tacz";
    public static final String GECKOLIB = "geckolib";

    public static boolean loaded(String modid) {
        return ModList.get() != null && ModList.get().isLoaded(modid);
    }

    public static boolean ccTweaked() { return loaded(CC_TWEAKED); }
    public static boolean tacz() { return loaded(TACZ); }
    public static boolean geckolib() { return loaded(GECKOLIB); }

    public static void logDetectedMods() {
        DeceasedCC.LOGGER.info("DeceasedCC integration scan: CC={}, TACZ={}, GeckoLib={}",
                ccTweaked(), tacz(), geckolib());
    }
}
