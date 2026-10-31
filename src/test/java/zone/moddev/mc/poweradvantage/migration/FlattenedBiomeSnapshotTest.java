package zone.moddev.mc.poweradvantage.migration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;
import static org.junit.Assert.*;

public class FlattenedBiomeSnapshotTest {
    @Test public void preservesEveryNumericIdAndUnknownModEntry() {
        CompoundTag root = root(1631);
        CompoundTag original = registries(root).getCompound("minecraft:biomes").copy();
        assertTrue(LegacyWorldDataHook.renameFlattenedBiomeRegistry(root));
        assertEquals(original, registries(root).getCompound("minecraft:worldgen/biome"));
        assertFalse(registries(root).contains("minecraft:biomes"));
    }

    @Test public void isIdempotent() {
        CompoundTag root = root(2230);
        assertTrue(LegacyWorldDataHook.renameFlattenedBiomeRegistry(root));
        CompoundTag once = root.copy();
        assertFalse(LegacyWorldDataHook.renameFlattenedBiomeRegistry(root));
        assertEquals(once, root);
    }

    @Test public void neverReinterpretsAPreFlatteningSnapshot() {
        CompoundTag root = root(1343), before = root.copy();
        assertFalse(LegacyWorldDataHook.renameFlattenedBiomeRegistry(root));
        assertEquals(before, root);
    }

    @Test public void leavesTheUppercaseLegacyArchiveUntouched() {
        CompoundTag root = root(1631);
        CompoundTag archive = new CompoundTag();
        archive.put("Registries", registries(root).copy());
        root.put("FML", archive);
        CompoundTag before = archive.copy();
        assertTrue(LegacyWorldDataHook.renameFlattenedBiomeRegistry(root));
        assertEquals(before, root.getCompound("FML"));
    }

    @Test public void refusesConflictingSnapshotsWithoutChangingEither() {
        CompoundTag root = root(2230);
        registries(root).put("minecraft:worldgen/biome", new CompoundTag());
        CompoundTag before = root.copy();
        try {
            LegacyWorldDataHook.renameFlattenedBiomeRegistry(root);
            fail("A conflicting registry must not be merged or discarded");
        } catch (IllegalStateException expected) {
            assertEquals(before, root);
        }
    }

    @Test public void coalescesOnlyAnIdenticalDuplicate() {
        CompoundTag root = root(2230);
        registries(root).put("minecraft:worldgen/biome", registries(root).getCompound("minecraft:biomes").copy());
        assertTrue(LegacyWorldDataHook.renameFlattenedBiomeRegistry(root));
        assertFalse(registries(root).contains("minecraft:biomes"));
    }

    private static CompoundTag root(int version) {
        CompoundTag root = new CompoundTag(), data = new CompoundTag(), fml = new CompoundTag();
        CompoundTag registries = new CompoundTag(), snapshot = new CompoundTag();
        data.putInt("DataVersion", version);
        root.put("Data", data);
        root.put("fml", fml);
        fml.put("Registries", registries);
        registries.put("minecraft:biomes", snapshot);
        ListTag ids = new ListTag();
        for (String name : new String[] {"minecraft:nether", "othermod:marsh"}) {
            CompoundTag entry = new CompoundTag();
            entry.putString("K", name);
            entry.putInt("V", name.startsWith("minecraft:") ? 8 : 200);
            ids.add(entry);
        }
        snapshot.put("ids", ids);
        snapshot.putIntArray("blocked", new int[] {77});
        ListTag aliases = new ListTag();
        CompoundTag alias = new CompoundTag();
        alias.putString("K", "othermod:old_marsh");
        alias.putString("V", "othermod:marsh");
        aliases.add(alias);
        snapshot.put("aliases", aliases);
        return root;
    }

    private static CompoundTag registries(CompoundTag root) {
        return root.getCompound("fml").getCompound("Registries");
    }
}
