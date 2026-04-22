package net.deceasedcraft.deceasedcc.turrets;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.blocks.entity.TurretMountBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.TurretNetworkControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 5 — Turret Network Controller peripheral. Aggregates state across all
 * linked mounts and distributes orders. Coordination logic ({@link
 * #coordinateTargeting}) tracks which entities are already claimed so no two
 * turrets waste rounds on the same mob.
 */
public class TurretNetworkPeripheral implements IPeripheral {
    private final TurretNetworkControllerBlockEntity host;
    private boolean coordinate;
    private final Set<UUID> claimedThisTick = new HashSet<>();

    public TurretNetworkPeripheral(TurretNetworkControllerBlockEntity host) {
        this.host = host;
    }

    @Override public String getType() { return "turret_network"; }
    @Override public boolean equals(@Nullable IPeripheral other) { return other instanceof TurretNetworkPeripheral p && p.host == host; }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getAllStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (BlockPos pos : host.getLinkedTurrets()) {
            TurretMountBlockEntity t = resolve(pos);
            if (t == null) continue;
            out.put(posKey(pos), t.mountPeripheral().getStatus());
        }
        return out;
    }

    /** List of "x,y,z" pos keys for every currently-resolvable linked turret.
     *  Use one of these as the first arg to {@link #callTurret}. */
    @LuaFunction(mainThread = true)
    public final Map<Integer, String> listTurrets() {
        Map<Integer, String> out = new LinkedHashMap<>();
        int i = 1;
        for (BlockPos pos : host.getLinkedTurrets()) {
            if (resolve(pos) != null) out.put(i++, posKey(pos));
        }
        return out;
    }

    /** Proxy any @LuaFunction on a linked turret_mount peripheral. Lets a
     *  computer drive every linked turret without needing a wired modem on
     *  each one — just one modem on the controller.
     *
     *  Lua: controller.callTurret("12,64,-30", "fire")
     *       controller.callTurret("12,64,-30", "setAim", 90, 0)
     *       local s = controller.callTurret("12,64,-30", "getStatus")
     *
     *  Per-turret ccControl gating still applies — calls into setters will
     *  throw the same LuaException they would when called directly.
     *
     *  Uses IArguments because @LuaFunction doesn't accept Object... varargs. */
    @LuaFunction(mainThread = true)
    public final Object callTurret(IArguments arguments) throws LuaException {
        if (arguments.count() < 2) {
            throw new LuaException("callTurret requires (posKey, method, [args...])");
        }
        String posKey = arguments.getString(0);
        String methodName = arguments.getString(1);
        int extraCount = arguments.count() - 2;

        BlockPos pos = parsePosKey(posKey);
        if (pos == null) throw new LuaException("invalid posKey: " + posKey + " (expect 'x,y,z')");
        TurretMountBlockEntity t = resolve(pos);
        if (t == null) throw new LuaException("no linked turret at " + posKey);
        TurretMountPeripheral mp = t.mountPeripheral();
        if (mp == null) throw new LuaException("turret_mount peripheral not initialized at " + posKey);

        Method target = findLuaMethod(TurretMountPeripheral.class, methodName, extraCount);
        if (target == null) {
            throw new LuaException("turret_mount has no @LuaFunction '" + methodName
                    + "' taking " + extraCount + " arg" + (extraCount == 1 ? "" : "s"));
        }

        Class<?>[] paramTypes = target.getParameterTypes();
        Object[] javaArgs = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            javaArgs[i] = coerce(arguments.get(i + 2), paramTypes[i], methodName, i);
        }

        try {
            target.setAccessible(true);
            return target.invoke(mp, javaArgs);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof LuaException le) throw le;
            throw new LuaException("call to '" + methodName + "' failed: "
                    + (cause == null ? ite.getMessage() : cause.getMessage()));
        } catch (Exception ex) {
            throw new LuaException("call to '" + methodName + "' failed: " + ex.getMessage());
        }
    }

    private static Method findLuaMethod(Class<?> cls, String name, int argCount) {
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(LuaFunction.class)) continue;
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() != argCount) continue;
            return m;
        }
        return null;
    }

    private static Object coerce(Object value, Class<?> target, String methodName, int idx) throws LuaException {
        if (value == null) {
            if (target.isPrimitive()) {
                throw new LuaException(methodName + " arg " + (idx + 1) + " cannot be nil");
            }
            return null;
        }
        if (target.isInstance(value)) return value;
        try {
            if (target == int.class    || target == Integer.class) return ((Number) value).intValue();
            if (target == long.class   || target == Long.class)    return ((Number) value).longValue();
            if (target == double.class || target == Double.class)  return ((Number) value).doubleValue();
            if (target == float.class  || target == Float.class)   return ((Number) value).floatValue();
            if (target == boolean.class || target == Boolean.class) return value;
            if (target == String.class) return value.toString();
        } catch (ClassCastException cce) {
            throw new LuaException(methodName + " arg " + (idx + 1)
                    + " expected " + target.getSimpleName() + ", got " + value.getClass().getSimpleName());
        }
        return value;
    }

    @LuaFunction(mainThread = true)
    public final void setNetworkEnabled(boolean on) {
        forEach(t -> t.state.enabled = on);
    }

    @LuaFunction(mainThread = true)
    public final void setNetworkPriority(String priority) throws LuaException {
        switch (priority) {
            case "nearest", "mostDangerous", "lowestHealth", "firstInRange" -> forEach(t -> t.state.priority = priority);
            default -> throw new LuaException("unknown priority: " + priority);
        }
    }

    @LuaFunction(mainThread = true)
    public final void assignSectors(Map<?, ?> table) throws LuaException {
        for (var e : table.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> val)) throw new LuaException("sector entries must be tables");
            BlockPos pos = parsePosKey(key);
            if (pos == null) continue;
            TurretMountBlockEntity t = resolve(pos);
            if (t == null) continue;
            Number min = (Number) val.get("min");
            Number max = (Number) val.get("max");
            if (min == null || max == null) throw new LuaException("each sector needs min and max");
            t.state.minSectorDeg = min.floatValue();
            t.state.maxSectorDeg = max.floatValue();
            t.setChanged();
        }
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getNetworkKillLog() {
        Map<String, Object> out = new LinkedHashMap<>();
        int i = 1;
        for (BlockPos pos : host.getLinkedTurrets()) {
            TurretMountBlockEntity t = resolve(pos);
            if (t == null) continue;
            for (TurretState.KillEntry k : t.state.killLog) {
                Map<String, Object> row = new HashMap<>();
                row.put("turret", posKey(pos));
                row.put("entity", k.entityType());
                row.put("timestamp", k.timestamp());
                out.put(String.valueOf(i++), row);
            }
        }
        return out;
    }

    @LuaFunction(mainThread = true)
    public final void coordinateTargeting(boolean on) {
        coordinate = on;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getNetworkAmmo() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (BlockPos pos : host.getLinkedTurrets()) {
            TurretMountBlockEntity t = resolve(pos);
            if (t == null) continue;
            int count = 0;
            for (var s : t.state.ammoSlots) count += s.getCount();
            out.put(posKey(pos), count);
        }
        return out;
    }

    /** Called from Turret Network Controller server tick. */
    public void serverTick() {
        if (!coordinate) return;
        claimedThisTick.clear();
        for (BlockPos pos : host.getLinkedTurrets()) {
            TurretMountBlockEntity t = resolve(pos);
            if (t == null) continue;
            UUID cur = t.state.currentTargetUuid;
            if (cur != null && !claimedThisTick.add(cur)) {
                t.state.currentTargetUuid = null;
                t.state.forcedTargetUuid = null;
            }
        }
    }

    private void forEach(java.util.function.Consumer<TurretMountBlockEntity> fn) {
        for (BlockPos pos : host.getLinkedTurrets()) {
            TurretMountBlockEntity t = resolve(pos);
            if (t != null) { fn.accept(t); t.setChanged(); }
        }
    }

    private @Nullable TurretMountBlockEntity resolve(BlockPos pos) {
        Level level = host.getLevel();
        if (level == null) return null;
        return level.getBlockEntity(pos) instanceof TurretMountBlockEntity m ? m : null;
    }

    private static String posKey(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static @Nullable BlockPos parsePosKey(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
