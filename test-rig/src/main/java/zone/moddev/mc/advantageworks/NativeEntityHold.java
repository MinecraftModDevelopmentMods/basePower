package zone.moddev.mc.advantageworks;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;

/** Holds native entity ticks without removing actors from the world's tracking indexes. */
final class NativeEntityHold {
    private final Map<Entity, State> held = new IdentityHashMap<>();

    void hold(Entity entity) {
        if (!(entity.level() instanceof ServerLevel)) throw new AssertionError("Entity hold requires a native server world");
        if (!held.containsKey(entity)) {
            State state = new State(entity);
            held.put(entity, state);
            state.gate.hold(entity, this);
        }
        entity.canUpdate(false);
    }

    void restore() {
        for (Map.Entry<Entity, State> entry : held.entrySet()) {
            Entity entity = entry.getKey();
            State state = entry.getValue();
            if (entity.tickCount != state.ticks) throw new AssertionError("Entity ticked while held: " + entity.getUUID());
            state.gate.release(entity, this);
        }
        held.clear();
    }

    private static final class State {
        final int ticks;
        final NativeEntityTickGate gate;

        State(Entity entity) {
            ticks = entity.tickCount;
            gate = NativeEntityTickGate.of((ServerLevel) entity.level());
        }
    }
}
