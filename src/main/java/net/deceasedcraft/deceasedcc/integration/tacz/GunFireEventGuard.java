package net.deceasedcraft.deceasedcc.integration.tacz;

import net.deceasedcraft.deceasedcc.DeceasedCC;
import net.deceasedcraft.deceasedcc.core.Integrations;
import net.deceasedcraft.deceasedcc.turrets.TurretShooterEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventListener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Reflectively wraps GunFireEvent subscribers from mods that blindly cast
 * the shooter to Player ({@code shotsfired}). Wrapped listeners skip the
 * call for our non-Player {@link TurretShooterEntity} and run normally
 * otherwise, so normal player gun fires keep ejecting casings while
 * turret fires stop crashing the server tick.
 * <p>
 * Reflection walks Forge's {@code ListenerList -> ListenerListInst(busID) ->
 * priorities: ArrayList<ArrayList<IEventListener>>} structure, then uses
 * the public {@code unregister(l)} + {@code register(priority, l)} methods
 * on {@code ListenerListInst} so Forge's cached listener array is rebuilt
 * automatically. The event itself is NOT cancelled, so TACZ's own
 * projectile-spawning path continues to run.
 */
public final class GunFireEventGuard {

    private static final String[] GUARD_TARGET_PACKAGES = {
            "net.marblednull.shotsfired"
    };

    private GunFireEventGuard() {}

    public static void register() {
        if (!Integrations.tacz()) return;
        try {
            Class<?> eventClass = Class.forName("com.tacz.guns.api.event.common.GunFireEvent");
            int wrapped = wrapListenersIn(eventClass, GUARD_TARGET_PACKAGES);
            DeceasedCC.LOGGER.info("GunFireEventGuard: wrapped {} GunFireEvent listener(s) "
                    + "from guarded packages.", wrapped);
        } catch (Throwable t) {
            DeceasedCC.LOGGER.warn("GunFireEventGuard install failed: " + t, t);
        }
    }

    @SuppressWarnings("unchecked")
    private static int wrapListenersIn(Class<?> eventClass, String[] packagePrefixes) throws Exception {
        // Grab MinecraftForge.EVENT_BUS's busID — we go through the PUBLIC
        // outer ListenerList API which takes (busId, priority, listener).
        // That avoids hitting the package-private inner ListenerListInst
        // class (whose methods throw IllegalAccessException from outside
        // the module even though they're declared public).
        Field busIdField = MinecraftForge.EVENT_BUS.getClass().getDeclaredField("busID");
        busIdField.setAccessible(true);
        int busId = busIdField.getInt(MinecraftForge.EVENT_BUS);

        Object dummy = createDummyEvent(eventClass);
        Method getListenerList = eventClass.getMethod("getListenerList");
        Object listenerList = getListenerList.invoke(dummy);
        Class<?> llClass = listenerList.getClass();

        // ListenerList public methods
        Method llRegister = llClass.getMethod("register",
                int.class, EventPriority.class, IEventListener.class);
        Method llUnregister = llClass.getMethod("unregister", int.class, IEventListener.class);
        Method llGetInstance = llClass.getDeclaredMethod("getInstance", int.class);
        llGetInstance.setAccessible(true);

        // Read-only peek at the inner ListenerListInst to discover priorities
        Object inst = llGetInstance.invoke(listenerList, busId);
        if (inst == null) {
            DeceasedCC.LOGGER.warn("GunFireEventGuard: no ListenerListInst for busID={}", busId);
            return 0;
        }
        Field prioritiesField = inst.getClass().getDeclaredField("priorities");
        prioritiesField.setAccessible(true);
        ArrayList<ArrayList<IEventListener>> priorities =
                (ArrayList<ArrayList<IEventListener>>) prioritiesField.get(inst);

        EventPriority[] priorityValues = EventPriority.values();
        record Match(EventPriority priority, IEventListener listener) {}
        java.util.List<Match> matches = new java.util.ArrayList<>();
        for (int p = 0; p < priorities.size() && p < priorityValues.length; p++) {
            ArrayList<IEventListener> bucket = priorities.get(p);
            if (bucket == null) continue;
            for (IEventListener l : bucket) {
                if (shouldWrap(l, packagePrefixes)) {
                    matches.add(new Match(priorityValues[p], l));
                }
            }
        }

        // Mutations via the PUBLIC outer ListenerList API (no module violation)
        for (Match m : matches) {
            llUnregister.invoke(listenerList, busId, m.listener());
            llRegister.invoke(listenerList, busId, m.priority(), (IEventListener) wrap(m.listener()));
            DeceasedCC.LOGGER.info("GunFireEventGuard: wrapped {} @ {}",
                    describe(m.listener()), m.priority());
        }
        return matches.size();
    }

    private static Object createDummyEvent(Class<?> eventClass) throws Exception {
        for (java.lang.reflect.Constructor<?> c : eventClass.getDeclaredConstructors()) {
            try {
                c.setAccessible(true);
                Class<?>[] params = c.getParameterTypes();
                Object[] args = new Object[params.length];
                for (int i = 0; i < params.length; i++) args[i] = defaultFor(params[i]);
                return c.newInstance(args);
            } catch (Throwable ignored) {}
        }
        throw new IllegalStateException("No usable constructor on " + eventClass);
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class)    return (byte) 0;
        if (type == short.class)   return (short) 0;
        if (type == int.class)     return 0;
        if (type == long.class)    return 0L;
        if (type == float.class)   return 0f;
        if (type == double.class)  return 0.0;
        if (type == char.class)    return '\0';
        return null;
    }

    private static boolean shouldWrap(IEventListener listener, String[] packagePrefixes) {
        String desc = describe(listener);
        for (String prefix : packagePrefixes) {
            if (desc.contains(prefix)) return true;
        }
        return false;
    }

    /** Reach into whatever wrapper IEventListener class Forge produced for
     *  this Consumer-based subscription and return something searchable. */
    private static String describe(IEventListener listener) {
        Class<?> cls = listener.getClass();
        StringBuilder acc = new StringBuilder(cls.getName());
        try {
            for (Field f : cls.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(listener);
                if (val == null) continue;
                if (val instanceof Consumer<?>) {
                    acc.append("|consumer:").append(val.getClass().getName());
                }
                if (val instanceof Method m) {
                    acc.append("|method:").append(m.getDeclaringClass().getName())
                       .append(".").append(m.getName());
                }
                if (val instanceof Class<?> c) {
                    acc.append("|class:").append(c.getName());
                }
            }
        } catch (Throwable ignored) {}
        return acc.toString();
    }

    private static IEventListener wrap(IEventListener original) {
        return event -> {
            try {
                Method getShooter = event.getClass().getMethod("getShooter");
                Object shooter = getShooter.invoke(event);
                if (shooter instanceof TurretShooterEntity) {
                    return; // no-op for turret shooters; prevents classcast crash
                }
            } catch (Throwable t) {
                DeceasedCC.LOGGER.warn("GunFireEventGuard wrap inspect failed", t);
            }
            original.invoke(event);
        };
    }
}
