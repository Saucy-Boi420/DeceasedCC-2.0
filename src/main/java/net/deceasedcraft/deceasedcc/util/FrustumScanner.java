package net.deceasedcraft.deceasedcc.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 6c — frustum-cone scan from a point in the world. Returns a
 * Lua-friendly {@code Map<String, Object>} frame summarising every entity
 * inside the cone (yaw+pitch direction, half-angle coneAngle/2, max
 * distance {@code range}).
 *
 * <p>Used by the camera scanning loop on the Advanced Network Controller
 * BE. Block-summary output is intentionally omitted in this phase — scan
 * cost grows O(R³) for blocks and we haven't hit a use case yet; Phase 10
 * can add if needed.
 */
public final class FrustumScanner {
    private FrustumScanner() {}

    /**
     * Run a scan and return a Lua-ready frame.
     *
     * @param level       the server level the camera lives in
     * @param cameraPos   camera block position (scan origin is the block centre
     *                    offset up slightly so it doesn't miss entities standing
     *                    on the same tile)
     * @param yawDeg      camera yaw in MC convention (0 = south / +Z)
     * @param pitchDeg    camera pitch in MC convention (0 = horizontal,
     *                    -90 = looking up, +90 = looking down)
     * @param coneAngle   full cone angle in degrees; entities inside an angular
     *                    distance less than (coneAngle/2) count as visible
     * @param range       max distance (blocks) from origin
     */
    public static Map<String, Object> scan(ServerLevel level, BlockPos cameraPos,
                                            float yawDeg, float pitchDeg,
                                            double coneAngle, double range) {
        Vec3 origin = Vec3.atCenterOf(cameraPos);
        Vec3 look = lookVec(yawDeg, pitchDeg);
        double halfAngleCos = Math.cos(Math.toRadians(coneAngle * 0.5));

        AABB box = new AABB(
                origin.x - range, origin.y - range, origin.z - range,
                origin.x + range, origin.y + range, origin.z + range);

        List<Map<String, Object>> entities = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box, FrustumScanner::scannable)) {
            Vec3 ePos = e.position();
            Vec3 toE = ePos.subtract(origin);
            double dist = toE.length();
            if (dist > range) continue;
            if (dist < 0.001) { // entity is basically on top of camera — always visible
                entities.add(entityEntry(e, ePos, dist));
                continue;
            }
            double dot = toE.normalize().dot(look);
            if (dot < halfAngleCos) continue; // outside cone
            entities.add(entityEntry(e, ePos, dist));
        }
        entities.sort(Comparator.comparingDouble(m -> (Double) m.get("distance")));

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("timestamp", level.getGameTime());
        frame.put("cameraPos", posMap(cameraPos));
        Map<String, Object> dir = new LinkedHashMap<>();
        dir.put("yaw", yawDeg); dir.put("pitch", pitchDeg);
        frame.put("direction", dir);
        frame.put("coneAngle", coneAngle);
        frame.put("range", range);
        frame.put("entities", entities);
        frame.put("entityCount", entities.size());
        return frame;
    }

    private static boolean scannable(Entity e) {
        return e.isAlive() && !e.isSpectator();
    }

    private static Map<String, Object> entityEntry(Entity e, Vec3 pos, double dist) {
        Map<String, Object> m = new LinkedHashMap<>();
        EntityType<?> type = e.getType();
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        m.put("uuid", e.getUUID().toString());
        m.put("type", id != null ? id.toString() : type.toString());
        m.put("category", e.getType().getCategory().getName());
        m.put("isPlayer", e instanceof Player);
        m.put("name", e.getName().getString());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", pos.x); p.put("y", pos.y); p.put("z", pos.z);
        m.put("pos", p);
        m.put("distance", dist);
        return m;
    }

    private static Map<String, Object> posMap(BlockPos p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", p.getX()); m.put("y", p.getY()); m.put("z", p.getZ());
        return m;
    }

    /**
     * MC convention: yaw 0 = +Z, yaw 90 = -X; pitch 0 = horizontal, positive
     * pitch = looking down.
     */
    public static Vec3 lookVec(float yawDeg, float pitchDeg) {
        double yr = Math.toRadians(yawDeg);
        double pr = Math.toRadians(pitchDeg);
        double cp = Math.cos(pr);
        return new Vec3(
                -Math.sin(yr) * cp,
                -Math.sin(pr),
                 Math.cos(yr) * cp);
    }
}
