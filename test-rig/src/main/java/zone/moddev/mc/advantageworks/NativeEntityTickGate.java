package zone.moddev.mc.advantageworks;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.lang.reflect.Field;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;

/** Test-only selective ticking hold; the native entity lookup and sections stay intact. */
final class NativeEntityTickGate extends EntityTickList {
    private final EntityTickList nativeTicks;
    private final Map<Entity, Hold> holds = new IdentityHashMap<>();

    private NativeEntityTickGate(EntityTickList nativeTicks) { this.nativeTicks = nativeTicks; }

    static NativeEntityTickGate of(ServerLevel world) {
        try {
            Field field = tickerField();
            EntityTickList actual = (EntityTickList) field.get(world);
            if (actual == null) throw new AssertionError("Missing actual native entity tick list");
            if (actual instanceof NativeEntityTickGate) return (NativeEntityTickGate) actual;
            NativeEntityTickGate gate = new NativeEntityTickGate(actual);
            field.set(world, gate);
            return gate;
        } catch (ReflectiveOperationException error) { throw new AssertionError("Native 1.17 ticker field is inaccessible", error); }
    }

    private static Field tickerField() throws NoSuchFieldException {
        // Exact official/SRG names for the pinned engine; plain JUnit has no ModLauncher remapper.
        for (String name : new String[] {"entityTickList", "f_143243_"}) {
            try {
                Field field = ServerLevel.class.getDeclaredField(name);
                if (field.getType() != EntityTickList.class) throw new AssertionError("Unexpected native ticker field type");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException("Native 1.17 entityTickList/f_143243_");
    }

    void hold(Entity entity, Object owner) {
        holds.computeIfAbsent(entity, ignored -> new Hold(entity.canUpdate())).owners.add(owner);
        entity.canUpdate(false);
    }

    void release(Entity entity, Object owner) {
        Hold hold = holds.get(entity);
        if (hold == null || !hold.owners.remove(owner)) throw new AssertionError("Unobserved native entity hold");
        if (hold.owners.isEmpty()) {
            holds.remove(entity);
            entity.canUpdate(hold.updating);
        }
    }

    @Override public void add(Entity entity) { nativeTicks.add(entity); }
    @Override public void remove(Entity entity) { nativeTicks.remove(entity); }
    @Override public boolean contains(Entity entity) { return !holds.containsKey(entity) && nativeTicks.contains(entity); }
    @Override public void forEach(Consumer<Entity> action) {
        nativeTicks.forEach(entity -> { if (!holds.containsKey(entity)) action.accept(entity); });
    }

    private static final class Hold {
        final Set<Object> owners = Collections.newSetFromMap(new IdentityHashMap<>());
        final boolean updating;
        Hold(boolean updating) { this.updating = updating; }
    }
}
