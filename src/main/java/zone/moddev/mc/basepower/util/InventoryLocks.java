package zone.moddev.mc.basepower.util;

import java.lang.reflect.Field;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.LockCode;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

/** Keeps automation from bypassing vanilla container locks. */
public final class InventoryLocks {
    // Ordinary inventories need no vanilla field access or Forge name remapper.
    private static final class Fields {
        static final Field CODE = ObfuscationReflectionHelper.findField(BaseContainerBlockEntity.class, "f_58621_");
        static final Field FIRST = ObfuscationReflectionHelper.findField(
                net.minecraft.world.CompoundContainer.class, "f_18910_");
        static final Field SECOND = ObfuscationReflectionHelper.findField(
                net.minecraft.world.CompoundContainer.class, "f_18911_");
    }

    private InventoryLocks() {}

    public static boolean isLocked(Object inventory) {
        if (inventory instanceof InventoryWrapper) return ((InventoryWrapper) inventory).isLocked();
        try {
            if (inventory instanceof net.minecraft.world.CompoundContainer)
                return isLocked(Fields.FIRST.get(inventory)) || isLocked(Fields.SECOND.get(inventory));
            return inventory instanceof BaseContainerBlockEntity
                    && !((LockCode) Fields.CODE.get(inventory)) .unlocksWith(ItemStack.EMPTY);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot inspect vanilla inventory lock", failure);
        }
    }
}
