package zone.moddev.mc.basepower.migration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Moves Forge's engine-owned payload key without rewriting arbitrary item custom data. */
final class NativePersistentData {
    private NativePersistentData() { }

    static int prepare(CompoundTag root, boolean player) {
        int changed = player ? owner(root) : 0;
        CompoundTag chunk = root.contains("Level", Tag.TAG_COMPOUND) ? root.getCompound("Level") : root;
        for (String key : new String[] {"Entities", "entities", "TileEntities", "block_entities"}) {
            if (chunk.get(key) instanceof ListTag list) for (Tag entry : list) {
                if (entry instanceof CompoundTag compound) changed += owner(compound);
            }
        }
        if (root.contains("Data", Tag.TAG_COMPOUND)) {
            CompoundTag data = root.getCompound("Data");
            if (data.contains("Player", Tag.TAG_COMPOUND)) changed += owner(data.getCompound("Player"));
        }
        return changed;
    }

    private static int owner(CompoundTag owner) {
        int changed = rename(owner);
        for (String key : new String[] {"Passengers", "Inventory", "EnderItems", "Items", "ArmorItems", "HandItems"}) {
            if (owner.get(key) instanceof ListTag list) for (Tag entry : list) {
                if (entry instanceof CompoundTag compound) {
                    changed += key.equals("Passengers") ? owner(compound) : stack(compound);
                }
            }
        }
        if (owner.contains("RootVehicle", Tag.TAG_COMPOUND)) {
            CompoundTag vehicle = owner.getCompound("RootVehicle");
            if (vehicle.contains("Entity", Tag.TAG_COMPOUND)) changed += owner(vehicle.getCompound("Entity"));
        }
        if (owner.contains("Item", Tag.TAG_COMPOUND)) changed += stack(owner.getCompound("Item"));
        return changed;
    }

    private static int stack(CompoundTag stack) {
        int changed = 0;
        if (stack.contains("tag", Tag.TAG_COMPOUND)) {
            CompoundTag tag = stack.getCompound("tag");
            for (String key : new String[] {"BlockEntityTag", "EntityTag"}) {
                if (tag.contains(key, Tag.TAG_COMPOUND)) changed += owner(tag.getCompound(key));
            }
            if (tag.get("Items") instanceof ListTag items) for (Tag entry : items) {
                if (entry instanceof CompoundTag compound) changed += stack(compound);
            }
        }
        if (stack.contains("components", Tag.TAG_COMPOUND)) {
            CompoundTag components = stack.getCompound("components");
            for (String key : new String[] {"minecraft:block_entity_data", "minecraft:entity_data"}) {
                if (components.contains(key, Tag.TAG_COMPOUND)) changed += owner(components.getCompound(key));
            }
            if (components.get("minecraft:container") instanceof ListTag contents) for (Tag entry : contents) {
                if (entry instanceof CompoundTag slot && slot.contains("item", Tag.TAG_COMPOUND))
                    changed += stack(slot.getCompound("item"));
            }
        }
        return changed;
    }

    private static int rename(CompoundTag owner) {
        if (!owner.contains("ForgeData")) return 0;
        if (!owner.contains("ForgeData", Tag.TAG_COMPOUND))
            throw new IllegalStateException("Saved ForgeData is not a compound");
        Tag previous = owner.get("ForgeData");
        if (owner.contains("NeoForgeData") && !previous.equals(owner.get("NeoForgeData")))
            throw new IllegalStateException("Conflicting Forge and NeoForge persistent data; original save retained");
        owner.put("NeoForgeData", previous.copy());
        owner.remove("ForgeData");
        return 1;
    }
}
