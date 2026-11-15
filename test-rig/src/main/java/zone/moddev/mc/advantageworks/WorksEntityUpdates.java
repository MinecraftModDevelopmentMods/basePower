package zone.moddev.mc.advantageworks;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Native, test-only actor holds; never persisted into the machine/player fixture. */
final class WorksEntityUpdates {
    private static final Map<Entity, Boolean> HELD = Collections.synchronizedMap(new WeakHashMap<>());
    static {
        NeoForge.EVENT_BUS.addListener((EntityTickEvent.Pre event) -> {
            if (!get(event.getEntity())) event.setCanceled(true);
        });
    }
    private WorksEntityUpdates() { }
    static boolean get(Entity entity) { return !HELD.containsKey(entity); }
    static void set(Entity entity, boolean updating) {
        if (updating) HELD.remove(entity); else HELD.put(entity, Boolean.TRUE);
    }
}
