package zone.moddev.mc.basepower.util;

import com.mojang.serialization.Dynamic;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Bridges saved machine inventories and the native item component format. */
public final class ItemData {
    public static final int LAST_PORT_DATA_VERSION = 3465;

    private ItemData() {}

    public static CompoundTag customData(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    public static ItemStack readStack(HolderLookup.Provider registries, CompoundTag saved) {
        if (zone.moddev.mc.basepower.util.NbtCompat.containsTag(saved, "Count", 99)) {
            // Vanilla does not descend into arbitrary mod block-entity inventories.
            saved = withLegacyWear(saved, registries.lookupOrThrow(Registries.ITEM));
            saved = (CompoundTag) DataFixers.getDataFixer().update(References.ITEM_STACK,
                    new Dynamic<>(NbtOps.INSTANCE, saved), LAST_PORT_DATA_VERSION,
                    net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version()).getValue();
        }
        return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), saved)
                .result().orElse(ItemStack.EMPTY);
    }

    public static CompoundTag writeStack(ItemStack stack, HolderLookup.Provider registries) {
        return (CompoundTag) ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
                .getOrThrow();
    }

    public static CompoundTag withLegacyWear(CompoundTag saved, HolderGetter<Item> items) {
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(saved, "Damage", 99) || saved.getIntOr("Damage", 0) <= 0
                || NbtCompat.containsTag(saved.getCompoundOrEmpty("tag"), "Damage", 99)) return saved;
        Identifier id = Identifier.tryParse(saved.getStringOr("id", ""));
        if (id == null || id.getNamespace().equals("minecraft") || items.get(ResourceKey.create(Registries.ITEM, id))
                .filter(holder -> holder.value().components().has(DataComponents.MAX_DAMAGE)).isEmpty()) return saved;
        // The native component fixer reads wear from tag.Damage; it cannot infer
        // pre-flattening metadata semantics for a modded item.
        CompoundTag copy = saved.copy();
        CompoundTag tag = copy.getCompoundOrEmpty("tag");
        tag.putInt("Damage", copy.getIntOr("Damage", 0));
        copy.put("tag", tag);
        return copy;
    }
}
