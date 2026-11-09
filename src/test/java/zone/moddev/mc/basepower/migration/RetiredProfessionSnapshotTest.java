package zone.moddev.mc.basepower.migration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;
import static org.junit.Assert.*;

public class RetiredProfessionSnapshotTest {
    @Test public void removesOnlyRetiredVanillaIndexAndIsIdempotent() {
        for (String namespace : new String[] {"FML", "fml"}) {
            CompoundTag root = world(namespace, "minecraft:farmer");
            CompoundTag player = new CompoundTag();
            player.putInt("XpLevel", 34);
            root.put("Player", player);
            assertTrue(LegacyWorldDataHook.removeRetiredVanillaProfessionRegistry(root));
            assertFalse(root.getCompound(namespace).getCompound("Registries").contains("minecraft:villagerprofessions"));
            assertEquals(player, root.getCompound("Player"));
            assertFalse(LegacyWorldDataHook.removeRetiredVanillaProfessionRegistry(root));
        }
    }

    @Test public void preservesCustomProfessionIndexInsteadOfHidingMissingMappings() {
        CompoundTag root = world("fml", "othermod:machinist");
        CompoundTag before = root.copy();
        assertFalse(LegacyWorldDataHook.removeRetiredVanillaProfessionRegistry(root));
        assertEquals(before, root);
    }

    private static CompoundTag world(String namespace, String profession) {
        CompoundTag entry = new CompoundTag();
        entry.putString("K", profession);
        entry.putInt("V", 1);
        ListTag ids = new ListTag();
        ids.add(entry);
        CompoundTag snapshot = new CompoundTag();
        snapshot.put("ids", ids);
        CompoundTag registries = new CompoundTag();
        registries.put("minecraft:villagerprofessions", snapshot);
        CompoundTag fml = new CompoundTag();
        fml.put("Registries", registries);
        CompoundTag root = new CompoundTag();
        root.put(namespace, fml);
        return root;
    }
}
