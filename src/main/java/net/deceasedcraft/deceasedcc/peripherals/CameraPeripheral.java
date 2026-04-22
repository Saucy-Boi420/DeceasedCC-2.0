package net.deceasedcraft.deceasedcc.peripherals;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.deceasedcraft.deceasedcc.blocks.entity.CameraBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 scaffold — {@code camera} peripheral. Stubs match the API surface in
 * hologram.txt so Lua scripts compile now; capture/buffer arrive in Phase 6.
 */
public class CameraPeripheral implements IPeripheral {
    private final CameraBlockEntity host;
    private final Set<IComputerAccess> attached = ConcurrentHashMap.newKeySet();

    // Direction state (phase 6b) — stored eagerly so GUI/save paths are ready.
    // Phase 1.2 seeds yaw/pitch from the placer via seedDirection() when the
    // block is first placed; the BE then persists these on NBT save.
    private double yawDeg = 0.0, pitchDeg = 0.0, rollDeg = 0.0;
    private boolean motionTriggerEnabled = false;

    /** Internal: called by {@link CameraBlockEntity} when loading from NBT
     *  or when the block is first placed. NOT exposed to Lua — the Lua-side
     *  setter is {@link #setDirection} below, which runs on the server's
     *  main thread and is rate-limited by CC. */
    public void seedDirection(float yaw, float pitch) {
        this.yawDeg = yaw;
        this.pitchDeg = pitch;
    }

    public CameraPeripheral(CameraBlockEntity host) {
        this.host = host;
    }

    @Override public String getType() { return "camera"; }
    @Override public void attach(IComputerAccess c) { attached.add(c); }
    @Override public void detach(IComputerAccess c) { attached.remove(c); }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof CameraPeripheral o && o.host == this.host;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getPosition() {
        var p = host.getBlockPos();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", p.getX()); out.put("y", p.getY()); out.put("z", p.getZ());
        return out;
    }

    // ---- capture / buffer (phase 6) — stubs ---------------------------------

    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getCurrentFrame() { return null; }

    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getFrameAt(int secondsAgo) { return null; }

    @LuaFunction(mainThread = true)
    public final int getBufferSize() { return 0; }

    @LuaFunction(mainThread = true)
    public final int getBufferDuration() { return 0; }

    @LuaFunction(mainThread = true)
    public final @Nullable String saveSnapshot(int secondsAgo) { return null; }

    @LuaFunction(mainThread = true)
    public final void clearBuffer() {}

    // ---- direction (phase 6b) — stubs ---------------------------------------

    @LuaFunction(mainThread = true)
    public final void setDirection(double yaw, double pitch, double roll) {
        this.yawDeg = yaw; this.pitchDeg = pitch; this.rollDeg = roll;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getDirection() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("yaw", yawDeg); out.put("pitch", pitchDeg); out.put("roll", rollDeg);
        return out;
    }

    @LuaFunction(mainThread = true)
    public final void lookAt(double x, double y, double z) {}

    @LuaFunction(mainThread = true)
    public final void lookAtBlock(int x, int y, int z) {}

    @LuaFunction(mainThread = true)
    public final void lockOnto(double x, double y, double z) {}

    @LuaFunction(mainThread = true)
    public final void lockOntoBlock(int x, int y, int z) {}

    @LuaFunction(mainThread = true)
    public final void clearLookTarget() {}

    @LuaFunction(mainThread = true)
    public final void resetDirection() { yawDeg = 0; pitchDeg = 0; rollDeg = 0; }

    @LuaFunction(mainThread = true)
    public final boolean isLocked() { return false; }

    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getLockTarget() { return null; }

    // ---- motion trigger (phase 7) — stubs -----------------------------------

    @LuaFunction(mainThread = true)
    public final void setMotionTriggerEnabled(boolean enabled) { motionTriggerEnabled = enabled; }

    @LuaFunction(mainThread = true)
    public final boolean isMotionTriggerEnabled() { return motionTriggerEnabled; }

    @LuaFunction(mainThread = true)
    public final void setMotionTriggerFilter(Map<?, ?> filterTable) throws LuaException {}

    // ---- linker integration (phase 8) — stubs -------------------------------

    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getLinkedProjector() { return null; }

    @LuaFunction(mainThread = true)
    public final void setLinkedProjector(int x, int y, int z) {}
}
