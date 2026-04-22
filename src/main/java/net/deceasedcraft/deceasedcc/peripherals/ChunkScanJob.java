package net.deceasedcraft.deceasedcc.peripherals;

import net.deceasedcraft.deceasedcc.scan.ScanFile;
import net.deceasedcraft.deceasedcc.scan.ScanRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tick-paced terrain scan. The radar advances one cursor step at a time
 * across an AABB; per-tick work is clamped to
 * {@code ModConfig.CHUNK_RADAR_MAX_BLOCKS_PER_TICK} so a huge area (e.g. a
 * 3-chunk radius scan with ~310k block candidates) doesn't stall the server
 * main thread. On completion the job writes a {@link ScanFile} into
 * {@link ScanRegistry} and the peripheral fires a CC event so Lua scripts can
 * wait on it.
 */
public final class ChunkScanJob {
    /** Fallback if the config hasn't loaded yet (shouldn't happen in practice). */
    public static final int DEFAULT_BLOCKS_PER_TICK = 4096;
    private static final TagKey<Block> FORGE_ORES =
            BlockTags.create(new ResourceLocation("forge", "ores"));

    public final String jobId;
    public final String name;
    public final int minX, minY, minZ, maxX, maxY, maxZ;
    public final String author;

    private int cx, cy, cz;
    private final List<ScanFile.Point> points = new ArrayList<>();
    private boolean done = false;

    public ChunkScanJob(String jobId, String name, String author,
                        int minX, int minY, int minZ,
                        int maxX, int maxY, int maxZ) {
        this.jobId = jobId;
        this.name = name;
        this.author = author;
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        this.cx = minX; this.cy = minY; this.cz = minZ;
    }

    public boolean isDone() { return done; }

    /** Process up to BLOCKS_PER_TICK positions. Returns true if the job
     *  finished this tick; the caller is responsible for publishing. */
    public boolean step(Level level) {
        if (done) return true;
        int budget;
        try {
            budget = net.deceasedcraft.deceasedcc.core.ModConfig.CHUNK_RADAR_MAX_BLOCKS_PER_TICK.get();
        } catch (Throwable t) {
            budget = DEFAULT_BLOCKS_PER_TICK;
        }
        while (budget-- > 0) {
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(cx, cy, cz);
            BlockState s = level.getBlockState(pos);
            if (!s.isAir()) {
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(s.getBlock());
                if (id != null) {
                    int rgb = classifyColor(s, id);
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("block", id.toString());
                    meta.put("mod", id.getNamespace());
                    // Capture BlockEntity NBT when present so a single
                    // scanArea call returns wine-rack / chest / barrel
                    // inventories inline. The NBT is kept raw so scripts
                    // see it as a nested Lua table under entry.meta.nbt.
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                    net.minecraft.nbt.CompoundTag beTag = null;
                    if (be != null) {
                        net.minecraft.nbt.CompoundTag saved = be.saveWithoutMetadata();
                        if (saved != null && !saved.isEmpty()) beTag = saved;
                    }
                    points.add(new ScanFile.Point(cx, cy, cz, rgb, meta, beTag));
                }
            }
            // advance x -> y -> z order so we finish one Y slab before
            // moving to the next
            cx++;
            if (cx > maxX) {
                cx = minX;
                cy++;
                if (cy > maxY) {
                    cy = minY;
                    cz++;
                    if (cz > maxZ) {
                        publish();
                        done = true;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void publish() {
        ScanRegistry.put(name, new ScanFile(
                author,
                System.currentTimeMillis(),
                "terrain",
                minX, minY, minZ, maxX, maxY, maxZ,
                points));
    }

    public int pointsSoFar() { return points.size(); }

    public double progressFraction() {
        long total = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (total <= 0) return 1.0;
        long done = (long)(cz - minZ) * (maxX - minX + 1) * (maxY - minY + 1)
                  + (long)(cy - minY) * (maxX - minX + 1)
                  + (cx - minX);
        return Math.min(1.0, (double) done / total);
    }

    private static int classifyColor(BlockState s, ResourceLocation id) {
        if (s.is(FORGE_ORES)) return 0xD94F4F;
        if (!s.getFluidState().isEmpty()) return 0x3A7AFF;
        String path = id.getPath();
        if (path.contains("leaves") || path.contains("grass")) return 0x4CAF50;
        if (path.contains("log") || path.contains("wood") || path.contains("planks")) return 0x8B5A2B;
        if (path.contains("sand") || path.contains("sandstone")) return 0xE0D28B;
        if (path.contains("stone") || path.contains("cobble") || path.contains("deepslate")) return 0x8A8A8A;
        return 0xB0B0B0;
    }
}
