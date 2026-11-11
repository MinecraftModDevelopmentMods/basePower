package zone.moddev.mc.basepower.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraftforge.registries.ForgeRegistries;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class FlattenedRegistryNameNormalizationTest {
    private static final String ARCHIVE = "basepower:legacy_registry_indexes";
    private static final Map<String, String> ALIASES = Map.of(
            "minecraft:potions", "minecraft:mob_effect",
            "minecraft:potiontypes", "minecraft:potion",
            "minecraft:soundevents", "minecraft:sound_event",
            "minecraft:enchantments", "minecraft:enchantment");

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test public void historicalEffectAndPotionCensusesResolveToDistinctNativeRegistryTypes() {
        assertEquals(id("mob_effect"), ForgeRegistries.MOB_EFFECTS.getRegistryName());
        assertEquals(id("potion"), ForgeRegistries.POTIONS.getRegistryName());
        assertEquals(id("sound_event"), ForgeRegistries.SOUND_EVENTS.getRegistryName());
        assertEquals(id("enchantment"), Registries.ENCHANTMENT.identifier());
        assertTrue(ForgeRegistries.MOB_EFFECTS.containsKey(id("blindness")));
        assertFalse("Historical potions are effects, not potion types", ForgeRegistries.POTIONS.containsKey(id("blindness")));
        assertTrue(ForgeRegistries.POTIONS.containsKey(id("awkward")));
        assertFalse(ForgeRegistries.MOB_EFFECTS.containsKey(id("awkward")));
        assertTrue(ForgeRegistries.SOUND_EVENTS.containsKey(id("ambient.cave")));
    }

    @Test public void normalizesFourLowercaseKeysBeforeArchivingWithoutChangingAnyPayload() throws Exception {
        CompoundTag before = source(), expected = normalized(before);
        Path file = write(before);
        LegacyWorldDataHook.prepareForgeRegistryMetadata(file.toFile());
        CompoundTag actual = read(file);
        assertEquals(expected, actual);
        assertEquals(before.get("Data"), actual.get("Data"));
        assertEquals("Numeric pre-flattening archive is separate and untouched", before.get("FML"), actual.get("FML"));
        for (String old : ALIASES.keySet()) assertFalse(old, registries(actual).contains(old));
        assertFalse("Data-driven enchantment index must not reach static Forge GameData", registries(actual).contains("minecraft:enchantment"));
        assertEquals(registries(before).get("minecraft:enchantments"), actual.getCompoundOrEmpty(ARCHIVE).get("minecraft:enchantment"));
        assertEquals(actual.getCompoundOrEmpty(ARCHIVE), read(file.resolveSibling("basepower-retired-registry-indexes.dat")));
    }

    @Test public void keepsEverySteamAndUnknownModEnchantmentIncludingSnapshotMetadata() throws Exception {
        CompoundTag before = source();
        Path file = write(before);
        LegacyWorldDataHook.prepareForgeRegistryMetadata(file.toFile());
        CompoundTag actual = read(file).getCompoundOrEmpty(ARCHIVE).getCompoundOrEmpty("minecraft:enchantment");
        assertEquals(registries(before).get("minecraft:enchantments"), actual);
        assertEquals(5, actual.getListOrEmpty("ids").size());
        for (String name : List.of("steamadvantage:high_explosive", "steamadvantage:powderless",
                "steamadvantage:rapid_reload", "steamadvantage:recoil", "othermod:private_enchantment")) {
            assertTrue(name, actual.getListOrEmpty("ids").stream().anyMatch(tag ->
                    ((CompoundTag)tag).getString("K").orElseThrow().equals(name)));
        }
        assertArrayEquals(new int[]{77, 901}, actual.getIntArray("blocked").orElseThrow());
        assertEquals(1, actual.getListOrEmpty("overrides").size());
        assertEquals(1, actual.getListOrEmpty("aliases").size());
        assertEquals(1, actual.getListOrEmpty("dummied").size());
    }

    @Test public void identicalCanonicalDuplicatesCoalesceWithoutMergingOrRenumberingEntries() throws Exception {
        CompoundTag before = source();
        for (var alias : ALIASES.entrySet()) {
            registries(before).put(alias.getValue(), registries(before).get(alias.getKey()).copy());
        }
        Path file = write(before);
        LegacyWorldDataHook.prepareForgeRegistryMetadata(file.toFile());
        assertEquals(normalized(before), read(file));
    }

    @Test public void anyConflictingCanonicalDuplicateFailsBeforeWritingOrCreatingAnArchive() throws Exception {
        for (var alias : ALIASES.entrySet()) {
            CompoundTag input = source();
            CompoundTag conflicting = ((CompoundTag)registries(input).get(alias.getKey())).copy();
            conflicting.putIntArray("blocked", new int[]{123456});
            registries(input).put(alias.getValue(), conflicting);
            assertRejectedWithoutWrites(input, false);
        }
    }

    @Test public void malformedRecognizedSourceOrCanonicalSnapshotsFailClosed() throws Exception {
        for (var alias : ALIASES.entrySet()) {
            for (String key : List.of(alias.getKey(), alias.getValue())) {
                CompoundTag input = source();
                registries(input).putString(key, "NotARegistrySnapshot");
                assertRejectedWithoutWrites(input, false);
            }
        }
    }

    @Test public void aConflictingEmbeddedEnchantmentArchiveCannotDiscardThePluralIndex() throws Exception {
        CompoundTag input = source();
        CompoundTag archive = new CompoundTag();
        archive.put("minecraft:enchantment", snapshot("othermod:different"));
        input.put(ARCHIVE, archive);
        assertRejectedWithoutWrites(input, false);
    }

    @Test public void aConflictingArchiveFileIsRetainedAndLevelDataIsNeverPartiallyNormalized() throws Exception {
        assertRejectedWithoutWrites(source(), true);
    }

    @Test public void originalBackupAndSidecarRemainByteExactAcrossRepeatPreparationAndSavedRestart() throws Exception {
        CompoundTag input = source();
        Path file = write(input);
        byte[] original = Files.readAllBytes(file);
        LegacyWorldDataHook.prepareForgeRegistryMetadata(file.toFile());
        Path backup = file.resolveSibling("level.dat.basepower-legacy-backup");
        Path sidecar = file.resolveSibling("basepower-retired-registry-indexes.dat");
        assertTrue("Normalization must create the original level backup", Files.isRegularFile(backup));
        assertTrue("The complete enchantment index must be archived", Files.isRegularFile(sidecar));
        assertArrayEquals(original, Files.readAllBytes(backup));
        byte[] once = Files.readAllBytes(file), archive = Files.readAllBytes(sidecar);
        LegacyWorldDataHook.prepareForgeRegistryMetadata(file.toFile());
        assertArrayEquals("Second preparation must not rewrite level.dat", once, Files.readAllBytes(file));
        assertArrayEquals(original, Files.readAllBytes(backup));
        assertArrayEquals(archive, Files.readAllBytes(sidecar));
        CompoundTag saved = read(file);
        saved.getCompoundOrEmpty("Data").putInt("DataVersion", 4671);
        saved.getCompoundOrEmpty("Data").putLong("Time", 123456);
        NbtIo.writeCompressed(saved, file);
        byte[] restarted = Files.readAllBytes(file);
        LegacyWorldDataHook.prepareForgeRegistryMetadata(file.toFile());
        assertArrayEquals(restarted, Files.readAllBytes(file));
        assertArrayEquals(original, Files.readAllBytes(backup));
        assertArrayEquals(archive, Files.readAllBytes(sidecar));
        assertEquals(input.get("FML"), read(file).get("FML"));
    }

    @Test public void unknownRegistryNamesAndModNamespacesAreNeverNormalizedFilteredOrArchived() throws Exception {
        CompoundTag input = source();
        for (String key : ALIASES.keySet()) registries(input).remove(key);
        registries(input).put("minecraft:private_registry", snapshot("othermod:unknown_entry"));
        registries(input).put("othermod:potions", snapshot("othermod:unknown_potion"));
        Path file = write(input);
        byte[] before = Files.readAllBytes(file);
        LegacyWorldDataHook.prepareForgeRegistryMetadata(file.toFile());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(input, read(file));
        assertFalse(Files.exists(file.resolveSibling("level.dat.basepower-legacy-backup")));
        assertFalse(Files.exists(file.resolveSibling("basepower-retired-registry-indexes.dat")));
    }

    @Test public void preFlatteningAndUppercaseOnlyArchivesAreNotReinterpreted() throws Exception {
        CompoundTag preFlattening = source();
        preFlattening.getCompoundOrEmpty("Data").putInt("DataVersion", 1343);
        CompoundTag uppercaseOnly = source();
        uppercaseOnly.remove("fml");
        for (CompoundTag input : List.of(preFlattening, uppercaseOnly)) {
            Path file = write(input);
            byte[] before = Files.readAllBytes(file);
            LegacyWorldDataHook.prepareForgeRegistryMetadata(file.toFile());
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(input, read(file));
        }
    }

    private void assertRejectedWithoutWrites(CompoundTag input, boolean existingSidecar) throws Exception {
        Path file = write(input), sidecar = file.resolveSibling("basepower-retired-registry-indexes.dat");
        if (existingSidecar) NbtIo.writeCompressed(snapshot("othermod:conflicting_archive"), sidecar);
        byte[] original = Files.readAllBytes(file), oldSidecar = existingSidecar ? Files.readAllBytes(sidecar) : null;
        try {
            LegacyWorldDataHook.prepareForgeRegistryMetadata(file.toFile());
            fail("Conflicting or malformed registry snapshots must not be silently accepted");
        } catch (IllegalStateException expected) {
            assertArrayEquals(original, Files.readAllBytes(file));
            assertFalse("No write was committed", Files.exists(file.resolveSibling("level.dat.basepower-legacy-backup")));
            if (existingSidecar) assertArrayEquals(oldSidecar, Files.readAllBytes(sidecar));
            else assertFalse(Files.exists(sidecar));
        }
    }

    private Path write(CompoundTag input) throws Exception {
        Path file = temporary.newFolder().toPath().resolve("level.dat");
        NbtIo.writeCompressed(input, file);
        return file;
    }

    private static CompoundTag read(Path file) throws Exception {
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }

    private static CompoundTag normalized(CompoundTag input) {
        CompoundTag expected = input.copy(), registries = registries(expected);
        for (var alias : ALIASES.entrySet()) {
            registries.put(alias.getValue(), registries.get(alias.getKey()).copy());
            registries.remove(alias.getKey());
        }
        CompoundTag archive = expected.getCompoundOrEmpty(ARCHIVE);
        archive.put("minecraft:enchantment", registries.get("minecraft:enchantment").copy());
        expected.put(ARCHIVE, archive);
        registries.remove("minecraft:enchantment");
        return expected;
    }

    private static CompoundTag source() throws Exception {
        CompoundTag root = TagParser.parseCompoundFully("{Data:{DataVersion:1631,Time:400L,Player:{Inventory:"
                + "[{id:\"steamadvantage:musket\",Count:1b,tag:{ench:[{id:\"steamadvantage:recoil\",lvl:3s}]}}]}},"
                + "fml:{Registries:{}},FML:{Registries:{\"minecraft:enchantments\":{ids:[{K:\"steamadvantage:recoil\",V:77}]},"
                + "\"minecraft:blocks\":{ids:[{K:\"poweradvantage:still\",V:255}]}},OpaqueLegacyField:[I;1,2,3]}}").copy();
        CompoundTag registries = registries(root);
        registries.put("minecraft:potions", snapshot("minecraft:blindness", "othermod:private_effect"));
        registries.put("minecraft:potiontypes", snapshot("minecraft:awkward", "othermod:private_potion"));
        registries.put("minecraft:soundevents", snapshot("minecraft:ambient.cave", "othermod:private_sound"));
        registries.put("minecraft:enchantments", snapshot("steamadvantage:high_explosive", "steamadvantage:powderless",
                "steamadvantage:rapid_reload", "steamadvantage:recoil", "othermod:private_enchantment"));
        registries.put("othermod:private_registry", snapshot("othermod:opaque_entry"));
        return root;
    }

    private static CompoundTag snapshot(String... names) throws Exception {
        CompoundTag snapshot = TagParser.parseCompoundFully("{ids:[],blocked:[I;77,901],"
                + "aliases:[{K:\"othermod:old_alias\",V:\"othermod:new_alias\"}],"
                + "overrides:[{K:\"minecraft:opaque_entry\",V:\"othermod\"}],dummied:[\"othermod:missing\"],"
                + "Opaque:{Bytes:[B;1b,2b],Counter:123L}}");
        ListTag ids = new ListTag();
        int index = 0;
        for (String name : names) {
            CompoundTag entry = new CompoundTag();
            entry.putString("K", name);
            entry.putInt("V", 200 + index++);
            ids.add(entry);
        }
        snapshot.put("ids", ids);
        return snapshot;
    }

    private static CompoundTag registries(CompoundTag root) {
        return root.getCompoundOrEmpty("fml").getCompoundOrEmpty("Registries");
    }

    private static Identifier id(String name) { return Identifier.fromNamespaceAndPath("minecraft", name); }
}
