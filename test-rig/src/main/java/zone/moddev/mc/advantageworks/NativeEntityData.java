package zone.moddev.mc.advantageworks;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/** Read-only access to the pinned Forge field without invoking its allocating getter. */
final class NativeEntityData {
    private static final Field PERSISTENT_DATA = persistentField();

    private NativeEntityData() { }

    static boolean containsString(Entity entity, String key) {
        CompoundTag data = peek(entity);
        return data != null && zone.moddev.mc.basepower.util.NbtCompat.containsTag(data, key, 8);
    }

    static String string(Entity entity, String key) {
        CompoundTag data = peek(entity);
        return data == null ? "" : data.getString(key).orElse("");
    }

    private static CompoundTag peek(Entity entity) {
        Objects.requireNonNull(entity, "Missing observed entity");
        try {
            return (CompoundTag) PERSISTENT_DATA.get(entity);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read pinned native117 Entity.persistentData", exception);
        }
    }

    private static Field persistentField() {
        try {
            return requireNativeField(Entity.class.getDeclaredField("persistentData"));
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("Missing pinned native117 Entity.persistentData", exception);
        }
    }

    static Field requireNativeField(Field field) {
        if (field == null || field.getDeclaringClass() != Entity.class
                || !field.getName().equals("persistentData") || field.getType() != CompoundTag.class
                || !Modifier.isPrivate(field.getModifiers()) || Modifier.isStatic(field.getModifiers())
                || Modifier.isFinal(field.getModifiers())) {
            throw new IllegalStateException("Unexpected pinned native117 persistent-data field: " + field);
        }
        try {
            field.setAccessible(true);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Cannot access pinned native117 Entity.persistentData", exception);
        }
        return field;
    }
}
