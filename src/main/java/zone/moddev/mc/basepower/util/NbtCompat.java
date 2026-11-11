package zone.moddev.mc.basepower.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/** Preserves legacy numeric-type checks when reading older saved machine data. */
public final class NbtCompat {
    private NbtCompat() {}

    public static boolean containsTag(CompoundTag compound, String key, int type) {
        Tag value = compound.get(key);
        return value != null && (type == 99 ? value instanceof NumericTag : value.getId() == type);
    }
}
