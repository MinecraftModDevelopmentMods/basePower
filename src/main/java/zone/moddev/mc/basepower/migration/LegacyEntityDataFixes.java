package zone.moddev.mc.basepower.migration;

import com.mojang.datafixers.DataFixUtils;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.util.datafix.fixes.EntityUUIDFix;

/** Isolates vanilla entity choices from mod choices during the chunk datafix. */
final class LegacyEntityDataFixes {
    private static final String STAGED = "AdvantageLegacyEntityDataFix";
    static final String RETIRED_MEMORIES = "basepower:retired_brain_memories";
    private static final String RETIRED_TEMPTATION = "minecraft:is_tempted";
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
        int version = zone.moddev.mc.basepower.util.NbtCompat.containsTag(root, "DataVersion", 99) ? root.getIntOr("DataVersion", 0) : -1;
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        if (version >= current) return;
        CompoundTag level = entityOwner(root);
        if (level == null) return;
        String key = entityKey(root, level);
        if (!level.contains(key)) return;
        requireCompoundList(level, key);
        ListTag entities = level.getListOrEmpty(key);
        if (entities.isEmpty()) return;
        DataFixer fixer = DataFixers.getDataFixer();
        Set<?> vanillaIds = fixer.getSchema(DataFixUtils.makeKey(version))
                .findChoiceType(References.ENTITY).types().keySet();
        ListTag fixed = new ListTag();
        for (int index = 0; index < entities.size(); index++) {
            fixed.add(upgrade(entities.getCompoundOrEmpty(index), version, current, fixer, vanillaIds));
        }
        // Keep these out of vanilla's mixed-choice traversal and avoid fixing them twice.
        root.put(STAGED, fixed);
        level.put(key, new ListTag());
    }

    private static CompoundTag upgrade(CompoundTag source, int version, int current,
            DataFixer fixer, Set<?> vanillaIds) {
        CompoundTag entity = source.copy();
        renameForgeAttributes(entity);
        archiveRetiredMemories(entity);
        boolean hasPassengers = source.contains("Passengers");
        ListTag passengers = source.getListOrEmpty("Passengers");
        entity.remove("Passengers");
        Dynamic<?> dynamic = new Dynamic<>(NbtOps.INSTANCE, entity);
        if (vanillaIds.contains(entity.getStringOr("id", ""))) {
            entity = (CompoundTag) fixer.update(References.ENTITY, dynamic, version, current).getValue();
        } else {
            // All entities inherit this root UUID format, including unknown mod types.
            entity = (CompoundTag) EntityUUIDFix.updateEntityUUID(dynamic).getValue();
            upgradeAttributePayload(entity, version, current, fixer);
        }
        // Vanilla's UUID fixer rejects zero halves, although they are valid UUID bits.
        var savedUuid = source.getIntArray("UUID").filter(value -> value.length == 4);
        if (savedUuid.isPresent()) {
            entity.store("UUID", UUIDUtil.CODEC, UUIDUtil.uuidFromIntArray(savedUuid.get()));
        } else if (zone.moddev.mc.basepower.util.NbtCompat.containsTag(source, "UUIDMost", 99) && zone.moddev.mc.basepower.util.NbtCompat.containsTag(source, "UUIDLeast", 99)) {
            entity.store("UUID", UUIDUtil.CODEC, new UUID(source.getLongOr("UUIDMost", 0L), source.getLongOr("UUIDLeast", 0L)));
        }
        if (entity.getIntArray("UUID").filter(value -> value.length == 4).isPresent()) {
            entity.remove("UUIDMost");
            entity.remove("UUIDLeast");
        }
        if (hasPassengers) {
            requireCompoundList(source, "Passengers");
            ListTag fixed = new ListTag();
            for (int index = 0; index < passengers.size(); index++) {
                fixed.add(upgrade(passengers.getCompoundOrEmpty(index), version, current, fixer, vanillaIds));
            }
            entity.put("Passengers", fixed);
        }
        return entity;
    }

    private static void upgradeAttributePayload(CompoundTag entity, int version, int current, DataFixer fixer) {
        if (!entity.contains("Attributes")) return;
        if (entity.contains("attributes")) throw new IllegalStateException("Ambiguous old and native entity attributes");
        CompoundTag input = new CompoundTag();
        input.put("Attributes", entity.get("Attributes").copy());
        // Attribute records have the same native schema on players and living entities.
        // Isolate this vanilla-owned field rather than submitting an unknown mod entity choice.
        CompoundTag fixed = DataFixTypes.PLAYER.update(fixer, input, version, current);
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(fixed, "attributes", Tag.TAG_LIST)) {
            throw new IllegalStateException("Native attribute data fix did not produce the current format");
        }
        entity.remove("Attributes");
        entity.put("attributes", fixed.get("attributes"));
    }

    static void preparePlayerAttributes(CompoundTag player) {
        renameForgeAttributes(player);
        if (!player.contains("RootVehicle")) return;
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(player, "RootVehicle", 10)) throw new IllegalStateException("Malformed player RootVehicle");
        CompoundTag vehicle = player.getCompoundOrEmpty("RootVehicle");
        if (!vehicle.contains("Entity")) return;
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(vehicle, "Entity", 10)) throw new IllegalStateException("Malformed player RootVehicle.Entity");
        prepareAttributeTree(vehicle.getCompoundOrEmpty("Entity"));
    }

    private static void prepareAttributeTree(CompoundTag entity) {
        renameForgeAttributes(entity);
        archiveRetiredMemories(entity);
        if (!entity.contains("Passengers")) return;
        requireCompoundList(entity, "Passengers");
        ListTag passengers = entity.getListOrEmpty("Passengers");
        for (int index = 0; index < passengers.size(); index++) {
            prepareAttributeTree(passengers.getCompoundOrEmpty(index));
        }
    }

    static void archiveRetiredMemories(CompoundTag entity) {
        CompoundTag brain = entity.getCompoundOrEmpty("Brain");
        CompoundTag memories = brain.getCompoundOrEmpty("memories");
        Tag retired = memories.get(RETIRED_TEMPTATION);
        if (retired == null) return;
        if (entity.contains("ForgeData") && !(entity.get("ForgeData") instanceof CompoundTag))
            throw new IllegalStateException("Malformed entity ForgeData while archiving retired memory");
        CompoundTag persistent = entity.getCompoundOrEmpty("ForgeData").copy();
        if (persistent.contains(RETIRED_MEMORIES) && !(persistent.get(RETIRED_MEMORIES) instanceof CompoundTag))
            throw new IllegalStateException("Malformed retired brain memory archive");
        CompoundTag archive = persistent.getCompoundOrEmpty(RETIRED_MEMORIES);
        if (archive.contains(RETIRED_TEMPTATION) && !retired.equals(archive.get(RETIRED_TEMPTATION)))
            throw new IllegalStateException("Conflicting retired temptation memory archive");
        archive.put(RETIRED_TEMPTATION, retired.copy());
        persistent.put(RETIRED_MEMORIES, archive);
        entity.put("ForgeData", persistent);
        memories.remove(RETIRED_TEMPTATION);
    }

    private static void renameForgeAttributes(CompoundTag entity) {
        if (!entity.contains("Attributes")) return;
        requireCompoundList(entity, "Attributes");
        ListTag attributes = entity.getListOrEmpty("Attributes");
        for (String[] alias : FORGE_ATTRIBUTES) {
            CompoundTag legacy = null;
            int identities = 0;
            for (int index = 0; index < attributes.size(); index++) {
                CompoundTag attribute = attributes.getCompoundOrEmpty(index);
                if (attribute.getStringOr("Name", "").equals(alias[0])) {
                    legacy = attribute;
                    identities++;
                } else if (attribute.getStringOr("Name", "").equals(alias[1])) {
                    identities++;
                }
            }
            if (identities > 1) throw new IllegalStateException("Ambiguous Forge attribute: " + alias[1]);
            if (legacy != null) legacy.putString("Name", alias[1]);
        }
    }

    static void finish(CompoundTag root) {
        if (root == null || !root.contains(STAGED)) return;
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(root, STAGED, 9) || entityOwner(root) == null) {
            throw new IllegalStateException("Malformed staged Advantage entity migration");
        }
        requireCompoundList(root, STAGED);
        CompoundTag level = entityOwner(root);
        String key = entityKey(root, level);
        if (level.contains(key)) requireCompoundList(level, key);
        if (!level.getListOrEmpty(key).isEmpty()) {
            throw new IllegalStateException("Entity data appeared during isolated Advantage migration");
        }
        level.put(key, root.getListOrEmpty(STAGED));
        root.remove(STAGED);
    }

    private static CompoundTag entityOwner(CompoundTag root) {
        if (zone.moddev.mc.basepower.util.NbtCompat.containsTag(root, "Level", 10)) return root.getCompoundOrEmpty("Level");
        if (!root.contains("Position")) {
            return zone.moddev.mc.basepower.util.NbtCompat.containsTag(root, "xPos", 99) && zone.moddev.mc.basepower.util.NbtCompat.containsTag(root, "zPos", 99) ? root : null;
        }
        if (root.getIntArray("Position").orElseGet(() -> new int[0]).length != 2) {
            throw new IllegalStateException("Saved entity chunk has no containing chunk coordinates");
        }
        return root;
    }

    private static String entityKey(CompoundTag root, CompoundTag owner) {
        // Terrain NBT was flattened in 1.18; separate entity region files were not.
        return owner == root && !root.contains("Position") ? "entities" : "Entities";
    }

    private static void requireCompoundList(CompoundTag owner, String key) {
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(owner, key, 9)) throw new IllegalStateException("Malformed entity list: " + key);
        ListTag list = (ListTag) owner.get(key);
        if (list.stream().anyMatch(value -> !(value instanceof CompoundTag))) {
            throw new IllegalStateException("Non-compound entity list: " + key);
        }
    }
}
