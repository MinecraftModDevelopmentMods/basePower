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
import net.minecraft.resources.Identifier;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import zone.moddev.mc.basepower.util.ItemData;

/** Vanilla cannot traverse inventories behind unknown mod block-entity choices. */
final class LegacyMachineItemDataFixes {
    private static final String PREPARED = "AdvantageLegacyMachineItemDataFix";

    private LegacyMachineItemDataFixes() {}

    static void prepare(CompoundTag root) {
        int version = zone.moddev.mc.basepower.util.NbtCompat.containsTag(root, "DataVersion", 99) ? root.getIntOr("DataVersion", 0) : -1;
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        if (version >= current || root.getBooleanOr(PREPARED, false) || !zone.moddev.mc.basepower.util.NbtCompat.containsTag(root, "Level", 10)) return;
        ListTag tiles = compoundList(root.getCompoundOrEmpty("Level"), "TileEntities");
        for (int index = 0; index < tiles.size(); index++) {
            CompoundTag tile = tiles.getCompoundOrEmpty(index);
            Identifier id = Identifier.tryParse(tile.getStringOr("id", ""));
            if (id != null && LegacyMigrationApi.handlesNamespace(id.getNamespace())) {
                rewrite(tile, version, current);
            }
        }
        root.putBoolean(PREPARED, true);
    }

    private static Tag rewrite(Tag value, int version, int current) {
        if (value instanceof CompoundTag) {
            CompoundTag compound = (CompoundTag) value;
            Identifier id = Identifier.tryParse(compound.getStringOr("id", ""));
            if (zone.moddev.mc.basepower.util.NbtCompat.containsTag(compound, "Count", 99) && id != null) {
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
            for (String key : new ArrayList<>(compound.keySet())) {
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
        if (!(items instanceof ListTag) || ((ListTag) items).size() != 1 || !(((ListTag) items).get(0) instanceof CompoundTag)) {
            throw new IllegalStateException("Native item envelope changed singleton inventory cardinality or type");
        }
        CompoundTag result = ((ListTag) items).getCompoundOrEmpty(0);
        Identifier id = Identifier.tryParse(result.getStringOr("id", ""));
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(result, "id", 8) || id == null || id.getPath().isEmpty() || !zone.moddev.mc.basepower.util.NbtCompat.containsTag(result, "Count", 99)
                || !result.get("Count").equals(source.get("Count"))
                || !Objects.equals(result.get("Slot"), source.get("Slot"))) {
            throw new IllegalStateException("Native item envelope changed item shape, count or slot");
        }
        return result;
    }

    private static boolean supported(Identifier id) {
        return id.getNamespace().equals("minecraft") || id.getNamespace().equals("basemetals")
                || id.getNamespace().equals("mmdlib") || LegacyMigrationApi.handlesNamespace(id.getNamespace());
    }

    private static Tag preserve(Tag original, Tag result) {
        if (original instanceof CompoundTag && result instanceof CompoundTag) {
            CompoundTag before = (CompoundTag) original, after = (CompoundTag) result;
            Identifier id = Identifier.tryParse(before.getStringOr("id", ""));
            if (zone.moddev.mc.basepower.util.NbtCompat.containsTag(before, "Count", 99) && id != null) {
                if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(after, "Count", 99) || before.getIntOr("Count", 0) != after.getIntOr("Count", 0)
                        || (zone.moddev.mc.basepower.util.NbtCompat.containsTag(before, "Slot", 99) && (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(after, "Slot", 99)
                        || before.getIntOr("Slot", 0) != after.getIntOr("Slot", 0)))) {
                    throw new IllegalStateException("Native item migration changed inventory count or slot");
                }
                if (!supported(id)) return ItemData.withLegacyWear(before.copy(), BuiltInRegistries.ITEM);
                restoreNamedEnchantments(before, after, "ench", "Enchantments");
                restoreNamedEnchantments(before, after, "StoredEnchantments", "StoredEnchantments");
            }
            for (String key : new ArrayList<>(before.keySet())) {
                if (after.contains(key)) after.put(key, preserve(before.get(key), after.get(key)));
            }
        } else if (original instanceof ListTag && result instanceof ListTag) {
            ListTag before = (ListTag) original, after = (ListTag) result;
            boolean inventory = before.stream().anyMatch(value -> value instanceof CompoundTag
                    && zone.moddev.mc.basepower.util.NbtCompat.containsTag((CompoundTag) value, "Count", 99)
                    && zone.moddev.mc.basepower.util.NbtCompat.containsTag((CompoundTag) value, "id", 8));
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
        ListTag before = compoundList(source.getCompoundOrEmpty("tag"), oldKey);
        ListTag after = compoundList(target.getCompoundOrEmpty("tag"), newKey);
        for (int index = 0; index < before.size(); index++) {
            if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(before.getCompoundOrEmpty(index), "id", 8)) continue;
            if (after.size() != before.size()) {
                throw new IllegalStateException("Native item migration changed a named enchantment list's size");
            }
            // The saved Forge registry has already translated these names. Vanilla's
            // numeric enchantment fixer must not reinterpret them as protection.
            after.set(index, before.getCompoundOrEmpty(index).copy());
        }
    }

    static void finish(CompoundTag root) {
        if (root != null) root.remove(PREPARED);
    }

    private static ListTag compoundList(CompoundTag owner, String key) {
        return owner.getList(key).filter(values -> values.stream().allMatch(CompoundTag.class::isInstance))
                .orElseGet(ListTag::new);
    }
}
