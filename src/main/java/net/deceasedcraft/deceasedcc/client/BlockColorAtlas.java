package net.deceasedcraft.deceasedcc.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side generator + cache for a "native block color" atlas. For each
 * registered block, averages every non-transparent pixel of every face
 * texture into a single ARGB int. Scripts can opt-in to palettizing their
 * voxel holograms from this atlas (see {@code hologramSetFromScan}'s
 * {@code useBlockAtlas = true} option).
 *
 * <h3>Where the colors come from</h3>
 * Option A (texture-average, per user preference): walk the baked model's
 * quads, pull each quad's {@link TextureAtlasSprite}, read the sprite's
 * original {@link NativeImage}, compute a weighted average. Fast — runs in
 * a few seconds for a ~5k-block modpack.
 *
 * <h3>Transparency</h3>
 * Final alpha = (mean pixel alpha of opaque pixels) × (opaque pixel ratio).
 * So fully-opaque blocks get alpha≈255, glass gets alpha≈127, a torch
 * (~10% opaque pixels) gets alpha≈25.
 *
 * <h3>Caching</h3>
 * Generated atlas is written to {@code config/deceasedcc/block_atlas.json}
 * with a content hash (all loaded mod id:version strings, sha-1'd). On
 * subsequent startups the file is re-read; hash mismatch triggers a
 * regenerate. The {@code /deceasedcc atlas regen} command forces a rebuild.
 *
 * <h3>Server handoff</h3>
 * After generation (or load from cache), the client ships the map to the
 * server via {@code AtlasSyncPacket}. Server caches it in
 * {@link net.deceasedcraft.deceasedcc.core.ServerBlockAtlas}. The
 * hologram-proj peripheral reads that when a Lua script passes
 * {@code useBlockAtlas = true}.
 */
public final class BlockColorAtlas {
    private BlockColorAtlas() {}

    /** blockId → packed ARGB. Populated either by {@link #loadOrGenerate()}
     *  or by decoding a server-authoritative snapshot. */
    public static final Map<String, Integer> COLORS = new ConcurrentHashMap<>();
    /** Hash of the content (mod list) used last time we generated. Empty
     *  until loadOrGenerate runs. */
    public static String currentHash = "";

    private static final int CACHE_VERSION = 1;
    private static final String CACHE_FILE_NAME = "block_atlas.json";
    private static final String[] SKIP_BLOCK_PREFIXES = {
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:light", "minecraft:barrier", "minecraft:moving_piston",
            "minecraft:structure_void",
    };

    /** Entry point — load the cache, verify its hash, regenerate on mismatch. */
    public static void loadOrGenerate() {
        String hash = computeContentHash();
        Path cache = cachePath();
        if (tryLoadCache(cache, hash)) {
            DeceasedCC.LOGGER.info("BlockColorAtlas: loaded {} entries from cache ({})",
                    COLORS.size(), hash);
            return;
        }
        DeceasedCC.LOGGER.info("BlockColorAtlas: generating (cache missing or hash changed)...");
        long t0 = System.currentTimeMillis();
        generate();
        currentHash = hash;
        long dt = System.currentTimeMillis() - t0;
        DeceasedCC.LOGGER.info("BlockColorAtlas: generated {} entries in {} ms",
                COLORS.size(), dt);
        writeCache(cache, hash);
    }

    /** Force regeneration (used by /deceasedcc atlas regen). */
    public static void forceRegen() {
        COLORS.clear();
        loadOrGenerate();
    }

    /** Walk every registered block, average its textures. */
    private static void generate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        BlockRenderDispatcher disp = mc.getBlockRenderer();
        RandomSource rand = RandomSource.create(42L);

        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) continue;
            String key = id.toString();
            if (shouldSkip(key)) continue;
            BlockState state = block.defaultBlockState();
            BakedModel model;
            try {
                model = disp.getBlockModel(state);
            } catch (Throwable t) {
                continue;
            }
            Integer argb = averageColor(model, state, rand);
            if (argb != null) COLORS.put(key, argb);
        }
    }

    /** Weighted-average every opaque pixel of every face texture of the
     *  block's baked model. Returns null if no pixels could be sampled. */
    private static Integer averageColor(BakedModel model, BlockState state, RandomSource rand) {
        long rSum = 0, gSum = 0, bSum = 0, aSum = 0;
        long opaquePixels = 0, totalPixels = 0;

        // 6 cardinal faces + null (for directionless quads)
        Direction[] dirs = Direction.values();
        for (int i = 0; i <= dirs.length; i++) {
            Direction d = (i < dirs.length) ? dirs[i] : null;
            List<net.minecraft.client.renderer.block.model.BakedQuad> quads;
            try {
                quads = model.getQuads(state, d, rand);
            } catch (Throwable t) {
                continue;
            }
            for (var q : quads) {
                TextureAtlasSprite sprite = q.getSprite();
                if (sprite == null) continue;
                NativeImage img = spriteImage(sprite);
                if (img == null) continue;
                int w = img.getWidth();
                int h = img.getHeight();
                // Sample the FIRST frame of an animated texture (y = 0..w).
                // Texture atlases pack frames vertically for animated
                // sprites, so limiting y to frame-height keeps water from
                // averaging across all animation steps.
                int frameH = Math.min(h, w);
                for (int y = 0; y < frameH; y++) {
                    for (int x = 0; x < w; x++) {
                        int abgr = img.getPixelRGBA(x, y);
                        // NativeImage stores as ABGR in memory. Extract:
                        int a = (abgr >>> 24) & 0xFF;
                        int b = (abgr >>> 16) & 0xFF;
                        int g = (abgr >>>  8) & 0xFF;
                        int r =  abgr         & 0xFF;
                        totalPixels++;
                        if (a > 0) {
                            rSum += r; gSum += g; bSum += b; aSum += a;
                            opaquePixels++;
                        }
                    }
                }
            }
        }
        if (opaquePixels == 0) return null;
        int r = (int) (rSum / opaquePixels);
        int g = (int) (gSum / opaquePixels);
        int b = (int) (bSum / opaquePixels);
        int meanAlpha = (int) (aSum / opaquePixels);
        // Cutout penalty: scale by opaque-pixel ratio.
        int effectiveAlpha = (int) (meanAlpha * opaquePixels / Math.max(1, totalPixels));
        return (effectiveAlpha << 24) | (r << 16) | (g << 8) | b;
    }

    /** Reflect out {@code SpriteContents.originalImage} since it's not
     *  publicly accessible in 1.20.1 mappings. One-time cost per block —
     *  the method caches the Field handle. */
    private static Field originalImageField;
    private static NativeImage spriteImage(TextureAtlasSprite sprite) {
        try {
            SpriteContents contents = sprite.contents();
            if (originalImageField == null) {
                for (Field f : SpriteContents.class.getDeclaredFields()) {
                    if (f.getType() == NativeImage.class) {
                        f.setAccessible(true);
                        originalImageField = f;
                        break;
                    }
                }
                if (originalImageField == null) return null;
            }
            return (NativeImage) originalImageField.get(contents);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean shouldSkip(String blockId) {
        for (String prefix : SKIP_BLOCK_PREFIXES) {
            if (blockId.equals(prefix)) return true;
        }
        return false;
    }

    // ---------- cache I/O ----------

    private static Path cachePath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve(DeceasedCC.MODID)
                .resolve(CACHE_FILE_NAME);
    }

    private static boolean tryLoadCache(Path cache, String expectedHash) {
        if (!Files.exists(cache)) return false;
        try {
            String json = Files.readString(cache, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("version") || root.get("version").getAsInt() != CACHE_VERSION) return false;
            if (!root.has("hash") || !expectedHash.equals(root.get("hash").getAsString())) return false;
            COLORS.clear();
            JsonObject entries = root.getAsJsonObject("entries");
            for (var e : entries.entrySet()) {
                int argb = (int) Long.parseLong(e.getValue().getAsString(), 16);
                COLORS.put(e.getKey(), argb);
            }
            currentHash = expectedHash;
            return true;
        } catch (Throwable t) {
            DeceasedCC.LOGGER.warn("BlockColorAtlas: cache read failed: {}", t.toString());
            return false;
        }
    }

    private static void writeCache(Path cache, String hash) {
        try {
            Files.createDirectories(cache.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", CACHE_VERSION);
            root.addProperty("hash", hash);
            JsonObject entries = new JsonObject();
            List<String> sortedKeys = new ArrayList<>(COLORS.keySet());
            Collections.sort(sortedKeys);
            for (String k : sortedKeys) {
                entries.addProperty(k, String.format("%08X", COLORS.get(k)));
            }
            root.add("entries", entries);
            Files.writeString(cache, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DeceasedCC.LOGGER.warn("BlockColorAtlas: cache write failed: {}", e.toString());
        }
    }

    /** SHA-1 of the sorted list of "modid:version" for every loaded mod.
     *  Fast regen detection when the user adds/removes/updates mods or
     *  changes a resource pack that changes textures. */
    private static String computeContentHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            List<String> parts = new ArrayList<>();
            for (var info : net.minecraftforge.fml.ModList.get().getMods()) {
                parts.add(info.getModId() + ":" + info.getVersion().toString());
            }
            Collections.sort(parts);
            for (String p : parts) md.update(p.getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
