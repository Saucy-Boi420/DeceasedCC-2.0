package net.deceasedcraft.deceasedcc.turrets;

/**
 * Target-selection filter for the Basic Turret. Cycled by the in-world GUI's
 * filter button; serialised as an ordinal on the block entity.
 */
public enum BasicTurretFilter {
    HOSTILES_ONLY("Hostiles"),
    PLAYERS_TOO("Hostiles + Players"),
    ALL_LIVING("All Mobs");

    private final String label;
    BasicTurretFilter(String label) { this.label = label; }
    public String label() { return label; }

    public BasicTurretFilter next() {
        BasicTurretFilter[] vals = values();
        return vals[(this.ordinal() + 1) % vals.length];
    }

    public static BasicTurretFilter fromOrdinal(int i) {
        BasicTurretFilter[] vals = values();
        if (i < 0 || i >= vals.length) return HOSTILES_ONLY;
        return vals[i];
    }
}
