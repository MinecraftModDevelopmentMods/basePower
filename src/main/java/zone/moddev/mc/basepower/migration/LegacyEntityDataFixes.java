package zone.moddev.mc.basepower.migration;

import com.mojang.datafixers.DataFixUtils;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.util.datafix.fixes.EntityUUIDFix;

/** Isolates vanilla entity choices from mod choices during the chunk datafix. */
final class LegacyEntityDataFixes {
    private static final String STAGED = "AdvantageLegacyEntityDataFix";
    // Forge moved these saved identities into its attribute registry in 1.16.
    private static final String[][] FORGE_ATTRIBUTES = {
        {"forge.swimSpeed", "forge:swim_speed"},
        {"forge.nameTagDistance", "forge:nametag_distance"},
        {"forge.entity_gravity", "minecraft:generic.gravity"},
        {"forge:entity_gravity", "minecraft:generic.gravity"},
        {"generic.reachDistance", "minecraft:player.block_interaction_range"},
        {"forge:reach_distance", "minecraft:player.block_interaction_range"},
        {"forge:block_reach", "minecraft:player.block_interaction_range"},
        {"forge:entity_reach", "minecraft:player.entity_interaction_range"}
    };

    private LegacyEntityDataFixes() {}

    static void prepare(CompoundTag root) {
        if (root.contains(STAGED)) return;
        int version = root.contains("DataVersion", 99) ? root.getInt("DataVersion") : -1;
        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        if (version >= current) return;
        CompoundTag level = entityOwner(root);
        if (level == null) return;
        String key = entityKey(root, level);
        if (!level.contains(key)) return;
        requireCompoundList(level, key);
        ListTag entities = level.getList(key, 10);
        if (entities.isEmpty()) return;
        DataFixer fixer = DataFixers.getDataFixer();
        Set<?> vanillaIds = fixer.getSchema(DataFixUtils.makeKey(version))
                .findChoiceType(References.ENTITY).types().keySet();
        ListTag fixed = new ListTag();
        for (int index = 0; index < entities.size(); index++) {
            fixed.add(upgrade(entities.getCompound(index), version, current, fixer, vanillaIds));
        }
        // Keep these out of vanilla's mixed-choice traversal and avoid fixing them twice.
        root.put(STAGED, fixed);
        level.put(key, new ListTag());
    }

    private static CompoundTag upgrade(CompoundTag source, int version, int current,
            DataFixer fixer, Set<?> vanillaIds) {
        CompoundTag entity = source.copy();
        renameForgeAttributes(entity);
        boolean hasPassengers = source.contains("Passengers");
        ListTag passengers = source.getList("Passengers", 10);
        entity.remove("Passengers");
        Dynamic<?> dynamic = new Dynamic<>(NbtOps.INSTANCE, entity);
        if (vanillaIds.contains(entity.getString("id"))) {
            entity = (CompoundTag) fixer.update(References.ENTITY, dynamic, version, current).getValue();
        } else {
            // All entities inherit this root UUID format, including unknown mod types.
            entity = (CompoundTag) EntityUUIDFix.updateEntityUUID(dynamic).getValue();
        }
        // Vanilla's UUID fixer rejects zero halves, although they are valid UUID bits.
        if (source.hasUUID("UUID")) {
            entity.putUUID("UUID", source.getUUID("UUID"));
        } else if (source.contains("UUIDMost", 99) && source.contains("UUIDLeast", 99)) {
            entity.putUUID("UUID", new UUID(source.getLong("UUIDMost"), source.getLong("UUIDLeast")));
        }
        if (entity.hasUUID("UUID")) {
            entity.remove("UUIDMost");
            entity.remove("UUIDLeast");
        }
        if (hasPassengers) {
            requireCompoundList(source, "Passengers");
            ListTag fixed = new ListTag();
            for (int index = 0; index < passengers.size(); index++) {
                fixed.add(upgrade(passengers.getCompound(index), version, current, fixer, vanillaIds));
            }
            entity.put("Passengers", fixed);
        }
        return entity;
    }

    static void preparePlayerAttributes(CompoundTag player) {
        renameForgeAttributes(player);
        if (!player.contains("RootVehicle")) return;
        if (!player.contains("RootVehicle", 10)) throw new IllegalStateException("Malformed player RootVehicle");
        CompoundTag vehicle = player.getCompound("RootVehicle");
        if (!vehicle.contains("Entity")) return;
        if (!vehicle.contains("Entity", 10)) throw new IllegalStateException("Malformed player RootVehicle.Entity");
        prepareAttributeTree(vehicle.getCompound("Entity"));
    }

    private static void prepareAttributeTree(CompoundTag entity) {
        renameForgeAttributes(entity);
        if (!entity.contains("Passengers")) return;
        requireCompoundList(entity, "Passengers");
        ListTag passengers = entity.getList("Passengers", 10);
        for (int index = 0; index < passengers.size(); index++) {
            prepareAttributeTree(passengers.getCompound(index));
        }
    }

    private static void renameForgeAttributes(CompoundTag entity) {
        if (!entity.contains("Attributes")) return;
        requireCompoundList(entity, "Attributes");
        ListTag attributes = entity.getList("Attributes", 10);
        for (String[] alias : FORGE_ATTRIBUTES) {
            CompoundTag legacy = null;
            int identities = 0;
            for (int index = 0; index < attributes.size(); index++) {
                CompoundTag attribute = attributes.getCompound(index);
                if (attribute.getString("Name").equals(alias[0])) {
                    legacy = attribute;
                    identities++;
                } else if (attribute.getString("Name").equals(alias[1])) {
                    identities++;
                }
            }
            if (identities > 1) throw new IllegalStateException("Ambiguous Forge attribute: " + alias[1]);
            if (legacy != null) legacy.putString("Name", alias[1]);
        }
    }

    static void finish(CompoundTag root) {
        if (root == null || !root.contains(STAGED)) return;
        if (!root.contains(STAGED, 9) || entityOwner(root) == null) {
            throw new IllegalStateException("Malformed staged Advantage entity migration");
        }
        requireCompoundList(root, STAGED);
        CompoundTag level = entityOwner(root);
        String key = entityKey(root, level);
        if (level.contains(key)) requireCompoundList(level, key);
        if (!level.getList(key, 10).isEmpty()) {
            throw new IllegalStateException("Entity data appeared during isolated Advantage migration");
        }
        level.put(key, root.getList(STAGED, 10));
        root.remove(STAGED);
    }

    private static CompoundTag entityOwner(CompoundTag root) {
        if (root.contains("Level", 10)) return root.getCompound("Level");
        if (!root.contains("Position")) {
            return root.contains("xPos", 99) && root.contains("zPos", 99) ? root : null;
        }
        if (root.getIntArray("Position").length != 2) {
            throw new IllegalStateException("Saved entity chunk has no containing chunk coordinates");
        }
        return root;
    }

    private static String entityKey(CompoundTag root, CompoundTag owner) {
        // Terrain NBT was flattened in 1.18; separate entity region files were not.
        return owner == root && !root.contains("Position") ? "entities" : "Entities";
    }

    private static void requireCompoundList(CompoundTag owner, String key) {
        if (!owner.contains(key, 9)) throw new IllegalStateException("Malformed entity list: " + key);
        ListTag list = (ListTag) owner.get(key);
        if (!list.isEmpty() && list.getElementType() != 10) {
            throw new IllegalStateException("Non-compound entity list: " + key);
        }
    }
}
