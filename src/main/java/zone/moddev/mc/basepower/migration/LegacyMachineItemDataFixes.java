package zone.moddev.mc.basepower.migration;

import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import java.util.ArrayList;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import zone.moddev.mc.basepower.util.ItemData;

/** Vanilla cannot traverse inventories behind unknown mod block-entity choices. */
final class LegacyMachineItemDataFixes {
    private static final String PREPARED = "AdvantageLegacyMachineItemDataFix";

    private LegacyMachineItemDataFixes() {}

    static void prepare(CompoundTag root) {
        int version = root.contains("DataVersion", 99) ? root.getInt("DataVersion") : -1;
        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        if (version >= current || root.getBoolean(PREPARED) || !root.contains("Level", 10)) return;
        ListTag tiles = root.getCompound("Level").getList("TileEntities", 10);
        for (int index = 0; index < tiles.size(); index++) {
            CompoundTag tile = tiles.getCompound(index);
            ResourceLocation id = ResourceLocation.tryParse(tile.getString("id"));
            if (id != null && LegacyMigrationApi.handlesNamespace(id.getNamespace())) {
                rewrite(tile, version, current);
            }
        }
        root.putBoolean(PREPARED, true);
    }

    private static Tag rewrite(Tag value, int version, int current) {
        if (value instanceof CompoundTag) {
            CompoundTag compound = (CompoundTag) value;
            ResourceLocation id = ResourceLocation.tryParse(compound.getString("id"));
            if (compound.contains("Count", 99) && id != null) {
                if (supported(id)) {
                    DataFixer fixer = DataFixers.getDataFixer();
                    CompoundTag input = version < 704 ? upgradeTo704(compound, version, fixer) : compound.copy();
                    int from = Math.max(version, 704);
                    if (from < ItemData.LAST_PORT_DATA_VERSION) {
                        CompoundTag fixed = (CompoundTag) fixer.update(References.ITEM_STACK,
                                new Dynamic<>(NbtOps.INSTANCE, input), from, ItemData.LAST_PORT_DATA_VERSION).getValue();
                        input = (CompoundTag) preserve(compound, fixed);
                        from = ItemData.LAST_PORT_DATA_VERSION;
                    }
                    // The native item schema also fixes its nested vanilla inventories.
                    // Do not visit them again with the old version and reset tool damage.
                    return fixer.update(References.ITEM_STACK,
                            new Dynamic<>(NbtOps.INSTANCE, input), from, current).getValue();
                }
                return compound;
            }
            for (String key : new ArrayList<>(compound.getAllKeys())) {
                compound.put(key, rewrite(compound.get(key), version, current));
            }
        } else if (value instanceof ListTag) {
            ListTag list = (ListTag) value;
            for (int index = 0; index < list.size(); index++) list.set(index, rewrite(list.get(index), version, current));
        }
        return value;
    }

    static CompoundTag upgradeTo704(CompoundTag source, int version, DataFixer fixer) {
        // A root ITEM_STACK hits the unchecked 704 hook conversion too early.
        // Native PLAYER.Inventory lets the nested block-entity choice rename run.
        CompoundTag player = new CompoundTag();
        ListTag inventory = new ListTag();
        inventory.add(source.copy());
        player.put("Inventory", inventory);
        Object converted = fixer.update(References.PLAYER, new Dynamic<>(NbtOps.INSTANCE, player),
                version, 704).getValue();
        if (!(converted instanceof CompoundTag)) {
            throw new IllegalStateException("Native item envelope is not a player compound");
        }
        Tag items = ((CompoundTag) converted).get("Inventory");
        if (!(items instanceof ListTag) || ((ListTag) items).size() != 1 || ((ListTag) items).getElementType() != 10) {
            throw new IllegalStateException("Native item envelope changed singleton inventory cardinality or type");
        }
        CompoundTag result = ((ListTag) items).getCompound(0);
        ResourceLocation id = ResourceLocation.tryParse(result.getString("id"));
        if (!result.contains("id", 8) || id == null || id.getPath().isEmpty() || !result.contains("Count", 99)
                || !result.get("Count").equals(source.get("Count"))
                || !Objects.equals(result.get("Slot"), source.get("Slot"))) {
            throw new IllegalStateException("Native item envelope changed item shape, count or slot");
        }
        return result;
    }

    private static boolean supported(ResourceLocation id) {
        return id.getNamespace().equals("minecraft") || id.getNamespace().equals("basemetals")
                || id.getNamespace().equals("mmdlib") || LegacyMigrationApi.handlesNamespace(id.getNamespace());
    }

    private static Tag preserve(Tag original, Tag result) {
        if (original instanceof CompoundTag && result instanceof CompoundTag) {
            CompoundTag before = (CompoundTag) original, after = (CompoundTag) result;
            ResourceLocation id = ResourceLocation.tryParse(before.getString("id"));
            if (before.contains("Count", 99) && id != null) {
                if (!after.contains("Count", 99) || before.getInt("Count") != after.getInt("Count")
                        || (before.contains("Slot", 99) && (!after.contains("Slot", 99)
                        || before.getInt("Slot") != after.getInt("Slot")))) {
                    throw new IllegalStateException("Native item migration changed inventory count or slot");
                }
                if (!supported(id)) return ItemData.withLegacyWear(before.copy(), BuiltInRegistries.ITEM.asLookup());
                restoreNamedEnchantments(before, after, "ench", "Enchantments");
                restoreNamedEnchantments(before, after, "StoredEnchantments", "StoredEnchantments");
            }
            for (String key : new ArrayList<>(before.getAllKeys())) {
                if (after.contains(key)) after.put(key, preserve(before.get(key), after.get(key)));
            }
        } else if (original instanceof ListTag && result instanceof ListTag) {
            ListTag before = (ListTag) original, after = (ListTag) result;
            boolean inventory = before.stream().anyMatch(value -> value instanceof CompoundTag
                    && ((CompoundTag) value).contains("Count", 99) && ((CompoundTag) value).contains("id", 8));
            if (inventory && before.size() != after.size()) {
                throw new IllegalStateException("Native item migration changed inventory cardinality");
            }
            for (int index = 0; index < Math.min(before.size(), after.size()); index++) {
                after.set(index, preserve(before.get(index), after.get(index)));
            }
        }
        return result;
    }

    private static void restoreNamedEnchantments(CompoundTag source, CompoundTag target, String oldKey, String newKey) {
        ListTag before = source.getCompound("tag").getList(oldKey, 10);
        ListTag after = target.getCompound("tag").getList(newKey, 10);
        for (int index = 0; index < before.size(); index++) {
            if (!before.getCompound(index).contains("id", 8)) continue;
            if (after.size() != before.size()) {
                throw new IllegalStateException("Native item migration changed a named enchantment list's size");
            }
            // The saved Forge registry has already translated these names. Vanilla's
            // numeric enchantment fixer must not reinterpret them as protection.
            after.set(index, before.getCompound(index).copy());
        }
    }

    static void finish(CompoundTag root) {
        if (root != null) root.remove(PREPARED);
    }
}
