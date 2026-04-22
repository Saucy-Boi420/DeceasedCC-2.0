package net.deceasedcraft.deceasedcc.util;

import dan200.computercraft.api.lua.LuaException;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Phase 3 — resolves hologram-voxel palette entries to packed ARGB ints
 * on the server thread, before the grid is broadcast.
 *
 * <p>Lua-facing spec: a palette entry is either
 * <ul>
 *   <li>A Minecraft block ID (e.g. {@code "minecraft:stone"}) — resolved via
 *       {@link ForgeRegistries#BLOCKS} and collapsed to the block's default
 *       MapColor (same colour vanilla uses on actual maps). The resulting
 *       RGB is combined with a full-opaque alpha.</li>
 *   <li>A hex colour (e.g. {@code "#RRGGBB"} or {@code "#AARRGGBB"}).</li>
 * </ul>
 * Detection by leading character — {@code '#'} marks hex, everything else
 * is treated as a block ID.
 */
public final class VoxelPalette {
    private VoxelPalette() {}

    /** Fallback colour for blocks whose MapColor is {@code NONE} (invisible)
     *  or blocks that raise an exception during MapColor resolution. */
    private static final int FALLBACK_RGB = 0x808080;

    /**
     * Resolve a single Lua palette entry to a packed ARGB int.
     *
     * Accepts:
     * <ul>
     *   <li>A {@link Number} — treated as a raw packed ARGB int (e.g.
     *       {@code 0xFF00FFFF}). Used by the Phase 5 markers path where
     *       colours often come from Lua HSV→ARGB math.</li>
     *   <li>A hex {@link String} — {@code "#RRGGBB"} (auto-opaque) or
     *       {@code "#AARRGGBB"} (explicit alpha).</li>
     *   <li>A block ID {@link String} — {@code "minecraft:stone"} etc.
     *       Resolved via MapColor.</li>
     * </ul>
     *
     * @param entry a String or Number from the Lua table
     * @param level the server level — needed for blocks whose MapColor depends
     *              on biome tinting (grass, leaves); most blocks ignore it.
     * @throws LuaException when the entry is malformed or refers to an
     *         unknown block ID.
     */
    public static int resolve(Object entry, Level level) throws LuaException {
        if (entry instanceof Number n) {
            // longValue so the full 32-bit pattern survives (Lua numbers are
            // doubles; an int cast directly would clamp values ≥ 2^31).
            return (int) n.longValue();
        }
        if (!(entry instanceof String s) || s.isEmpty()) {
            throw new LuaException("palette entry must be a non-empty string or ARGB number");
        }
        if (s.charAt(0) == '#') return parseHex(s);
        return resolveBlock(s, level);
    }

    private static int parseHex(String s) throws LuaException {
        String hex = s.substring(1);
        if (hex.length() != 6 && hex.length() != 8) {
            throw new LuaException("hex palette entry must be '#RRGGBB' or '#AARRGGBB': " + s);
        }
        long v;
        try {
            v = Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            throw new LuaException("invalid hex palette entry: " + s);
        }
        if (hex.length() == 6) return (int) (0xFF000000L | v);
        return (int) v;
    }

    private static int resolveBlock(String id, Level level) throws LuaException {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) throw new LuaException("invalid block ID: " + id);
        if (!ForgeRegistries.BLOCKS.containsKey(rl)) {
            throw new LuaException("unknown block ID: " + id);
        }
        Block block = ForgeRegistries.BLOCKS.getValue(rl);
        if (block == null) throw new LuaException("unknown block ID: " + id);

        int rgb;
        try {
            // Most blocks' MapColor is constant and ignores position; the few
            // that tint by biome (grass/leaves) will sample at the projector
            // origin rather than the specific voxel. Acceptable tradeoff —
            // projector voxels are abstract volumes, not world positions.
            rgb = block.defaultBlockState().getMapColor(level, BlockPos.ZERO).col;
        } catch (Throwable t) {
            rgb = FALLBACK_RGB;
        }
        if (rgb == 0) rgb = FALLBACK_RGB; // MapColor.NONE encodes as 0 — would render invisible
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }
}
