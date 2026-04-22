package net.deceasedcraft.deceasedcc.scan;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named scan registry. Persists to the overworld's saved-data folder so
 * scans survive server restarts — a one-shot {@code scanArea} on a wine
 * cellar stays queryable across sessions.
 *
 * <p>Flat namespace, case-sensitive names, last-write-wins. Scans from
 * every dimension land in the same registry — absolute world coords carry
 * enough context for the scripts that read them.</p>
 */
@Mod.EventBusSubscriber(modid = DeceasedCC.MODID)
public final class ScanRegistry {
    private static final String SAVED_DATA_NAME = DeceasedCC.MODID + "_scans";
    private static volatile Store STORE;

    private ScanRegistry() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer srv = event.getServer();
        ServerLevel overworld = srv.overworld();
        DimensionDataStorage data = overworld.getDataStorage();
        STORE = data.computeIfAbsent(Store::load, Store::new, SAVED_DATA_NAME);
        DeceasedCC.LOGGER.info("ScanRegistry: loaded {} saved scans", STORE.files.size());
    }

    public static void put(String name, ScanFile file) {
        Store s = STORE;
        if (s == null) return;
        s.files.put(name, file);
        s.setDirty();
    }

    public static ScanFile get(String name) {
        Store s = STORE;
        return s == null ? null : s.files.get(name);
    }

    public static boolean remove(String name) {
        Store s = STORE;
        if (s == null) return false;
        boolean removed = s.files.remove(name) != null;
        if (removed) s.setDirty();
        return removed;
    }

    public static List<String> names() {
        Store s = STORE;
        if (s == null) return List.of();
        List<String> out = new ArrayList<>(s.files.keySet());
        out.sort(null);
        return out;
    }

    public static int size() {
        Store s = STORE;
        return s == null ? 0 : s.files.size();
    }

    static final class Store extends SavedData {
        final ConcurrentHashMap<String, ScanFile> files = new ConcurrentHashMap<>();

        Store() {}

        static Store load(CompoundTag tag) {
            Store s = new Store();
            if (tag.contains("files", Tag.TAG_COMPOUND)) {
                CompoundTag fs = tag.getCompound("files");
                for (String key : fs.getAllKeys()) {
                    try {
                        s.files.put(key, ScanFile.fromNbt(fs.getCompound(key)));
                    } catch (Exception e) {
                        DeceasedCC.LOGGER.warn("ScanRegistry: dropping malformed scan '{}': {}", key, e.toString());
                    }
                }
            }
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CompoundTag fs = new CompoundTag();
            for (var e : files.entrySet()) {
                fs.put(e.getKey(), e.getValue().toNbt());
            }
            tag.put("files", fs);
            return tag;
        }
    }
}
