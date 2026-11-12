package zone.moddev.mc.basepower.util;

import java.lang.reflect.Field;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;

/** Keeps automation from bypassing vanilla container locks. */
public final class InventoryLocks {
    // CompoundContainer exposes no child getters; single containers use the native API.
    private static final class Fields {
        static final Field FIRST = child("container1");
        static final Field SECOND = child("container2");

        private static Field child(String name) {
            try {
                Field field = CompoundContainer.class.getDeclaredField(name);
                if (field.getType() != Container.class || !field.trySetAccessible()) {
                    throw new IllegalStateException("Cannot inspect vanilla compound inventory: " + name);
                }
                return field;
            } catch (NoSuchFieldException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }

    private InventoryLocks() {}

    public static boolean isLocked(Object inventory) {
        if (inventory instanceof InventoryWrapper) return ((InventoryWrapper) inventory).isLocked();
        try {
            if (inventory instanceof CompoundContainer)
                return isLocked(Fields.FIRST.get(inventory)) || isLocked(Fields.SECOND.get(inventory));
            return inventory instanceof BaseContainerBlockEntity container && container.isLocked();
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot inspect vanilla inventory lock", failure);
        }
    }
}
