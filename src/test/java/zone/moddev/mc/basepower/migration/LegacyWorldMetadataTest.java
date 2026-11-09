package zone.moddev.mc.basepower.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Set;

import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.BeforeClass;
import org.junit.Test;

public class LegacyWorldMetadataTest {
    @Test public void movesTheOldPaintingRegistryWithoutLosingItsEntries() {
        CompoundTag root = new CompoundTag(), forge = new CompoundTag(), registries = new CompoundTag();
        CompoundTag snapshot = new CompoundTag(), entry = new CompoundTag();
        ListTag ids = new ListTag();
        entry.putString("K", "example:custom_painting");
        entry.putInt("V", 42);
        ids.add(entry);
        snapshot.put("ids", ids);
        registries.put("minecraft:motive", snapshot);
        forge.put("Registries", registries);
        root.put("fml", forge);
        LegacyWorldMetadata.remap(root, id -> false);
        assertFalse(registries.contains("minecraft:motive"));
        assertEquals(snapshot, registries.getCompound("minecraft:painting_variant"));
        CompoundTag once = root.copy();
        LegacyWorldMetadata.remap(root, id -> false);
        assertEquals(once, root);
    }

    @BeforeClass public static void registerAliases() {
        AdvantageLegacyMigrations.registerPowerAdvantage();
        LegacyMigrationApi.registerNamespaceAlias("test_old_expansion", "test_new_expansion");
    }

    @Test public void migratesSavedModAndPackNamesWithoutTouchingRegistries() {
        CompoundTag root = world(mods(mod("poweradvantage", "2.4.1"), mod("unrelated", "1")),
                packs("vanilla", "mod:poweradvantage", "file/poweradvantage.zip", "mod:unrelated"),
                packs("mod:test_old_expansion"));
        CompoundTag expected = world(mods(mod("basepower", "2.4.1"), mod("unrelated", "1")),
                packs("vanilla", "mod:basepower", "file/poweradvantage.zip", "mod:unrelated"),
                packs("mod:test_new_expansion"));
        LegacyWorldMetadata.remap(root, Set.of("basepower", "test_new_expansion")::contains);
        assertEquals(expected, root);
        LegacyWorldMetadata.remap(root, Set.of("basepower", "test_new_expansion")::contains);
        assertEquals(expected, root);
    }

    @Test public void keepsTheCurrentPackChoiceAndVersionIfAlreadyRecorded() {
        CompoundTag root = world(mods(mod("poweradvantage", "2.4.1"), mod("basepower", "2.4.9")),
                packs("vanilla", "mod:poweradvantage"), packs("mod:basepower"));
        LegacyWorldMetadata.remap(root, Set.of("basepower")::contains);
        assertEquals(world(mods(mod("basepower", "2.4.9")), packs("vanilla"), packs("mod:basepower")), root);
    }

    @Test public void leavesUninstalledReplacementsAndStillInstalledLegacyModsVisible() {
        CompoundTag root = world(mods(mod("poweradvantage", "2.4.1"), mod("test_old_expansion", "1")),
                packs("mod:test_old_expansion", "mod:invalid:id"), packs("mod:poweradvantage"));
        CompoundTag original = root.copy();
        LegacyWorldMetadata.remap(root, Set.of("poweradvantage", "basepower")::contains);
        assertEquals(original, root);
    }

    @Test public void doesNotAddMissingMetadataToPreFlatteningOrNewWorlds() {
        CompoundTag root = new CompoundTag();
        root.put("FML", mod("poweradvantage", "2.4.0"));
        CompoundTag original = root.copy();
        LegacyWorldMetadata.remap(root, Set.of("basepower")::contains);
        assertEquals(original, root);
    }

    private static CompoundTag world(ListTag mods, ListTag enabled, ListTag disabled) {
        CompoundTag root = new CompoundTag(), fml = new CompoundTag(), data = new CompoundTag();
        CompoundTag registries = new CompoundTag(), packs = new CompoundTag();
        registries.putString("witness", "poweradvantage:fluid_pipe");
        fml.put("LoadingModList", mods);
        fml.put("Registries", registries);
        packs.put("Enabled", enabled);
        packs.put("Disabled", disabled);
        data.put("DataPacks", packs);
        root.put("fml", fml);
        root.put("Data", data);
        return root;
    }

    private static ListTag packs(String... ids) {
        ListTag result = new ListTag();
        for (String id : ids) result.add(StringTag.valueOf(id));
        return result;
    }

    private static ListTag mods(CompoundTag... mods) {
        ListTag result = new ListTag();
        for (CompoundTag mod : mods) result.add(mod);
        return result;
    }

    private static CompoundTag mod(String id, String version) {
        CompoundTag mod = new CompoundTag();
        mod.putString("ModId", id);
        mod.putString("ModVersion", version);
        return mod;
    }
}
