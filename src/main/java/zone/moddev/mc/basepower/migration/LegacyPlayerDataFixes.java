package zone.moddev.mc.basepower.migration;

import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixTypes;

/** Keeps saved Forge enchantment names across the native numeric player fixers. */
public final class LegacyPlayerDataFixes {
    private LegacyPlayerDataFixes() {}

    public static CompoundTag updateNativePlayer(DataFixTypes type, DataFixer fixer, CompoundTag source, int version) {
        return update(fixer, type, source, version);
    }

    public static CompoundTag update(DataFixer fixer, DataFixTypes type, CompoundTag source, int version) {
        if (type != DataFixTypes.PLAYER) throw new IllegalArgumentException("Expected native PLAYER type");
        CompoundTag result = source.copy();
        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        if (version >= current) return result;
        LegacyEntityDataFixes.preparePlayerAttributes(result);
        if (version < 704) {
            // Re-read the native namespaced container schema before later choice fixes.
            result = type.update(fixer, result, version, 704);
            version = 704;
        }
        result = type.update(fixer, result, version, current);
        preserveNames(source, result);
        return result;
    }

    static void preserveNames(Tag original, Tag result) {
        if (original instanceof CompoundTag && result instanceof CompoundTag) {
            CompoundTag before = (CompoundTag) original, after = (CompoundTag) result;
            if (isStack(before)) {
                if (!before.get("Count").equals(after.get("Count"))
                        || (before.contains("Slot") != after.contains("Slot"))
                        || (before.contains("Slot") && !before.get("Slot").equals(after.get("Slot")))) {
                    throw new IllegalStateException("Native player migration changed inventory count or slot");
                }
                restoreNamed(before, after, "ench", "Enchantments");
                restoreNamed(before, after, "StoredEnchantments", "StoredEnchantments");
            }
            for (String key : before.getAllKeys()) {
                if (after.contains(key)) preserveNames(before.get(key), after.get(key));
                else if (containsStack(before.get(key))) {
                    throw new IllegalStateException("Native player migration removed an inventory branch");
                }
            }
        } else if (original instanceof ListTag && result instanceof ListTag) {
            ListTag before = (ListTag) original, after = (ListTag) result;
            boolean inventory = containsStack(before) || containsStack(after);
            if (inventory && before.size() != after.size()) {
                throw new IllegalStateException("Native player migration changed inventory cardinality");
            }
            for (int index = 0; index < Math.min(before.size(), after.size()); index++) {
                preserveNames(before.get(index), after.get(index));
            }
        } else if (containsStack(original)) {
            throw new IllegalStateException("Native player migration replaced an inventory branch");
        }
    }

    private static boolean isStack(Tag value) {
        return value instanceof CompoundTag && ((CompoundTag) value).contains("id")
                && ((CompoundTag) value).contains("Count", 99);
    }

    private static boolean containsStack(Tag value) {
        if (isStack(value)) return true;
        if (value instanceof CompoundTag) {
            CompoundTag compound = (CompoundTag) value;
            for (String key : compound.getAllKeys()) if (containsStack(compound.get(key))) return true;
        } else if (value instanceof ListTag) {
            for (Tag child : (ListTag) value) if (containsStack(child)) return true;
        }
        return false;
    }

    private static void restoreNamed(CompoundTag source, CompoundTag target, String oldKey, String newKey) {
        ListTag before = source.getCompound("tag").getList(oldKey, 10);
        ListTag after = target.getCompound("tag").getList(newKey, 10);
        for (int index = 0; index < before.size(); index++) {
            if (!before.getCompound(index).contains("id", 8)) continue;
            if (before.size() != after.size()) {
                throw new IllegalStateException("Native player migration changed named enchantment cardinality");
            }
            // Restore only the saved name, never the item's foreign payload or native changes.
            after.getCompound(index).putString("id", before.getCompound(index).getString("id"));
        }
    }
}
