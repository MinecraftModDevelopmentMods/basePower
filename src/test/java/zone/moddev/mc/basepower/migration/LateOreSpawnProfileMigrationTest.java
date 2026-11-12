package zone.moddev.mc.basepower.migration;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.JumpInsnNode;

public class LateOreSpawnProfileMigrationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private Path registerBeforeImport(String name) throws Exception {
        AdvantageLegacyMigrations.registerPowerAdvantage();
        Path config = temporary.newFolder(name).toPath();
        OreSpawnProfileMigration.registerPowerProvider(config);
        try (var files = Files.list(config)) { assertEquals(0, files.count()); }
        return config;
    }

    private static JsonObject quartzProfile(int schema) {
        return JsonParser.parseString("""
                {"schema_version":%d,"manage_vanilla_ores":false,"place_fluid_deposits":false,
                 "ores":{"minecraft:quartz_ore":{"enabled":true,"suppress_vanilla":false,
                   "dimensions":{"minecraft:the_nether":{"enabled":true,"frequency":0.375,
                     "min_y":12,"max_y":117,"node_size":9,"private":{"keep":42}}}}},
                 "providers":{"retained":{"known_ores":["minecraft:quartz_ore"]}},
                 "history":"minecraft:quartz_ore is a literal note"}
                """.formatted(schema)).getAsJsonObject();
    }

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    @Test public void lateCreatedGlobalAndOwnedOverrideAreBothMigrated() throws Exception {
        Path config = registerBeforeImport("late-import");
        Path global = config.resolve("orespawn-worldgen.json");
        JsonObject before = quartzProfile(8), expected = before.deepCopy();
        expected.getAsJsonObject("ores").getAsJsonObject("minecraft:quartz_ore")
                .addProperty("block", "minecraft:nether_quartz_ore");
        Files.writeString(global, before.toString());
        Path override = config.resolve("basepower-orespawn.json");
        JsonObject provider = JsonParser.parseString("""
                {"schema_version":4,"provider_modid":"basepower","provider_revision":3,
                 "ores":{"basepower:legacy/saved_oil":{"block":"poweradvantage:crude_oil",
                   "enabled":false,"dimensions":{"minecraft:overworld":{"enabled":false,
                     "frequency":0.625,"min_y":19,"max_y":47,"private":{"keep":7}}}}},
                 "fluid_deposits":{},"user":{"setting":"poweradvantage:crude_oil"}}
                """).getAsJsonObject();
        Files.writeString(override, provider.toString());
        assertTrue(OreSpawnProfileMigration.migrateLateProfiles(config));
        assertEquals(expected, read(global));
        JsonObject migrated = read(override), retained = provider.getAsJsonObject("ores")
                .getAsJsonObject("basepower:legacy/saved_oil").deepCopy();
        retained.addProperty("block", "basepower:crude_oil");
        assertEquals(retained, migrated.getAsJsonObject("ores").get("basepower:legacy/saved_oil"));
        assertEquals(4, migrated.get("schema_version").getAsInt());
        assertEquals(provider.get("provider_revision"), migrated.get("provider_revision"));
        assertEquals(provider.get("user"), migrated.get("user"));
        assertFalse(migrated.getAsJsonObject("fluid_deposits")
                .getAsJsonObject("basepower:fluid_deposit/desert_crude_oil").get("enabled").getAsBoolean());
    }

    @Test public void lateRepairPreservesRootSevenEightAndEveryNonOutputValue() throws Exception {
        for (int schema : new int[] {7, 8}) {
            Path config = registerBeforeImport("schema-" + schema);
            Path file = config.resolve("orespawn-worldgen.json");
            JsonObject source = quartzProfile(schema), expected = source.deepCopy();
            expected.getAsJsonObject("ores").getAsJsonObject("minecraft:quartz_ore")
                    .addProperty("block", "minecraft:nether_quartz_ore");
            Files.writeString(file, source.toString());
            assertTrue(OreSpawnProfileMigration.migrateLateProfiles(config));
            assertEquals(expected, read(file));
        }
    }

    @Test public void absentAndAlreadyRepairedLateFilesAreIdempotentIncludingEvidence() throws Exception {
        Path config = registerBeforeImport("idempotent");
        assertFalse(OreSpawnProfileMigration.migrateLateProfiles(config));
        try (var files = Files.list(config)) { assertEquals(0, files.count()); }
        Path file = config.resolve("orespawn-worldgen.json");
        byte[] original = ("\n" + quartzProfile(8) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, original);
        assertTrue(OreSpawnProfileMigration.migrateLateProfiles(config));
        Map<Path, byte[]> first = new LinkedHashMap<>();
        try (var files = Files.list(config)) {
            for (Path path : files.toList()) first.put(path, Files.readAllBytes(path));
        }
        assertEquals(3, first.size());
        Path backup = first.keySet().stream().filter(path -> path.toString().endsWith(".bak")).findFirst().orElseThrow();
        assertArrayEquals(original, first.get(backup));
        assertFalse(OreSpawnProfileMigration.migrateLateProfiles(config));
        try (var files = Files.list(config)) { assertEquals(first.keySet(), new java.util.HashSet<>(files.toList())); }
        for (var entry : first.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
    }

    @Test public void lateActiveCollisionRefusesWithoutChangingTheImportedFile() throws Exception {
        Path config = registerBeforeImport("refused");
        JsonObject source = quartzProfile(8);
        source.addProperty("manage_vanilla_ores", true);
        JsonObject current = source.getAsJsonObject("ores").getAsJsonObject("minecraft:quartz_ore").deepCopy();
        current.addProperty("block", "minecraft:nether_quartz_ore");
        source.getAsJsonObject("ores").add("minecraft:nether_quartz_ore", current);
        Path file = config.resolve("orespawn-worldgen.json");
        Files.writeString(file, source.toString());
        byte[] before = Files.readAllBytes(file);
        try {
            OreSpawnProfileMigration.migrateLateProfiles(config);
            fail("Ambiguous active imported rules must not be silently accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("needs review"));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test public void loadCompleteEnqueuesRepairThenConditionallyRebakesAndRefreshes() throws Exception {
        var power = NativeMigrationMixinContract.read("zone/moddev/mc/basepower/PowerAdvantage");
        var listener = NativeMigrationMixinContract.method(power, "finishOreSpawnMigration");
        assertTrue(NativeMigrationMixinContract.calls(listener).stream().anyMatch(call ->
                call.owner.equals("net/minecraftforge/fml/event/lifecycle/FMLLoadCompleteEvent")
                        && call.name.equals("enqueueWork")));
        var callbacks = power.methods.stream().filter(method -> method.name.startsWith("lambda$finishOreSpawnMigration$")).toList();
        assertEquals(1, callbacks.size());
        var callback = callbacks.getFirst();
        var calls = NativeMigrationMixinContract.calls(callback);
        var repair = calls.stream().filter(call -> call.owner.equals("zone/moddev/mc/basepower/migration/OreSpawnProfileMigration")
                && call.name.equals("migrateLateProfiles")).findFirst().orElseThrow();
        assertEquals("(Ljava/nio/file/Path;)Z", repair.desc);
        String[] owners = {"GeomeConfig", "OreSpawnOreGeneration", "FluidDepositFeature"};
        int previous = callback.instructions.indexOf(repair);
        JumpInsnNode changedGate = null;
        for (var next = repair.getNext(); next != null; next = next.getNext()) {
            if (next instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) { changedGate = jump; break; }
        }
        assertNotNull("Rebake only when files changed", changedGate);
        for (String owner : owners) {
            String methodName = owner.equals("GeomeConfig") ? "bake" : "refreshWorldConfig";
            var call = calls.stream().filter(value -> value.owner.equals("zone/moddev/mc/orespawn/worldgen/" + owner)
                    && value.name.equals(methodName)).findFirst().orElseThrow();
            int index = callback.instructions.indexOf(call);
            assertTrue(owner + " follows repair in the required order", index > previous);
            assertTrue(owner + " is skipped on unchanged files", callback.instructions.indexOf(changedGate.label) > index);
            previous = index;
        }
    }
}
