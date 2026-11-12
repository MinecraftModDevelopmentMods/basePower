package zone.moddev.mc.basepower.migration;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OreSpawnProfileMigrationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private static JsonObject legacyProfile() {
        return JsonParser.parseString("""
                {"schema_version":7,"ores":{
                  "poweradvantage:legacy/custom_oil":{"block":"poweradvantage:crude_oil",
                    "enabled":true,"dimension_selectors":{"orespawn:all_except_nether_end":{
                      "enabled":true,"frequency":0.375,"min_y":16,"max_y":63}}}},
                 "fluid_deposits":{},"history":"poweradvantage:crude_oil is a literal note"}
                """).getAsJsonObject();
    }

    @Test public void migratesGlobalOverrideAndLockedWorldBeforeOreSpawnReadsThem() throws Exception {
        AdvantageLegacyMigrations.registerPowerAdvantage();
        Path config = temporary.newFolder("config").toPath();
        Path global = config.resolve("orespawn-worldgen.json");
        Path override = config.resolve("basepower-orespawn.json");
        String original = legacyProfile().toString();
        Files.writeString(global, original);
        Files.writeString(override, original);
        OreSpawnProfileMigration.registerPowerProvider(config);
        assertMigrated(global);
        assertMigrated(override);

        Path world = temporary.newFolder("world").toPath();
        Path profile = Files.createDirectories(world.resolve("serverconfig")).resolve("orespawn-worldgen.json");
        Files.writeString(profile, original);
        OreSpawnProfileMigration.migrateWorld(world);
        assertMigrated(profile);
        byte[] first = Files.readAllBytes(profile);
        OreSpawnProfileMigration.migrateWorld(world);
        assertArrayEquals(first, Files.readAllBytes(profile));
        try (var files = Files.list(profile.getParent())) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".bak")).count());
        }
    }

    @Test public void absentProfileDoesNotCreateOrPopulateAWorld() throws Exception {
        Path config = temporary.newFolder("empty-config").toPath();
        OreSpawnProfileMigration.registerPowerProvider(config);
        Path absent = temporary.getRoot().toPath().resolve("absent-world");
        OreSpawnProfileMigration.migrateWorld(absent);
        assertFalse(Files.exists(absent));
        try (var files = Files.list(config)) { assertEquals(0, files.count()); }
    }

    @Test public void newWorldWithPreplacedProfileDoesNotNeedLevelDat() throws Exception {
        AdvantageLegacyMigrations.registerPowerAdvantage();
        OreSpawnProfileMigration.registerPowerProvider(temporary.newFolder("new-world-config").toPath());
        Path world = temporary.newFolder("new-world").toPath();
        Path file = Files.createDirectories(world.resolve("serverconfig")).resolve("orespawn-worldgen.json");
        JsonObject source = JsonParser.parseString("""
                {"schema_version":7,"manage_vanilla_ores":false,"ores":{
                  "minecraft:quartz_ore":{"enabled":true,"dimensions":{
                    "minecraft:the_nether":{"enabled":true,"frequency":0.375}}}}}
                """).getAsJsonObject();
        Files.writeString(file, source.toString());
        assertFalse(Files.exists(world.resolve("level.dat")));
        OreSpawnProfileMigration.migrateWorld(world);
        JsonObject expected = source.deepCopy();
        expected.getAsJsonObject("ores").getAsJsonObject("minecraft:quartz_ore")
                .addProperty("block", "minecraft:nether_quartz_ore");
        assertEquals(expected, JsonParser.parseString(Files.readString(file)));
        assertFalse("Profile preparation must not manufacture world data", Files.exists(world.resolve("level.dat")));
    }

    @Test public void serverHookRunsAtHighestPriorityBeforeOreSpawnNormalListener() throws Exception {
        var power = NativeMigrationMixinContract.read("zone/moddev/mc/basepower/PowerAdvantage");
        var constructor = NativeMigrationMixinContract.method(power, "<init>");
        assertTrue("New worlds bypass the existing-level.dat datafix hook", NativeMigrationMixinContract.calls(constructor)
                .stream().anyMatch(call -> call.owner.equals("zone/moddev/mc/basepower/migration/OreSpawnProfileMigration")
                        && call.name.equals("registerServerHook")));
        var coordinator = NativeMigrationMixinContract.read("zone/moddev/mc/basepower/migration/OreSpawnProfileMigration");
        var register = NativeMigrationMixinContract.method(coordinator, "registerServerHook");
        boolean highest = false, event = false, listener = false;
        for (var instruction : register.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.IntInsnNode constant) {
                highest |= constant.operand == net.minecraftforge.eventbus.api.listener.Priority.HIGHEST;
            }
            if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field) {
                event |= field.owner.equals("net/minecraftforge/event/server/ServerAboutToStartEvent") && field.name.equals("BUS");
            }
            if (instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode dynamic) {
                for (var argument : dynamic.bsmArgs) if (argument instanceof org.objectweb.asm.Handle handle) {
                    listener |= handle.getOwner().equals(coordinator.name) && handle.getName().equals("prepareServerProfile");
                }
            }
        }
        assertTrue(highest && event && listener);
        var prepare = NativeMigrationMixinContract.method(coordinator, "prepareServerProfile");
        var calls = NativeMigrationMixinContract.calls(prepare);
        assertTrue(calls.stream().anyMatch(call -> call.owner.equals("net/minecraft/server/MinecraftServer") && call.name.equals("getWorldPath")));
        assertTrue(calls.stream().anyMatch(call -> call.owner.equals(coordinator.name) && call.name.equals("migrateWorld")));
        assertTrue("No cache reload or server tick may precede profile repair", calls.stream().noneMatch(call ->
                call.name.equals("reloadActiveProfile") || call.name.equals("tickServer")));
    }

    @Test public void targetValidationUsesDeclaredCodecsWithoutConstructingModBlocks() {
        AdvantageLegacyMigrations.registerPowerAdvantage();
        assertTrue(OreSpawnProfileMigration.declaredTarget("basepower:crude_oil"));
        assertTrue(OreSpawnProfileMigration.declaredTarget("basepower:refined_oil"));
        assertTrue(OreSpawnProfileMigration.declaredTarget("minecraft:nether_quartz_ore"));
        assertFalse(OreSpawnProfileMigration.declaredTarget("basepower:no_such_block"));
        assertFalse(OreSpawnProfileMigration.declaredTarget("minecraft:air"));
        assertFalse(OreSpawnProfileMigration.declaredTarget("bad identifier"));
    }

    @Test public void selectedWorldProfileIsPreparedBeforeWorldRegistryMutation() throws Exception {
        var hook = NativeMigrationMixinContract.read("zone/moddev/mc/basepower/migration/LegacyWorldDataHook");
        var method = NativeMigrationMixinContract.method(hook, "prepareLegacyWorld");
        var calls = NativeMigrationMixinContract.calls(method);
        int profile = -1, registry = -1;
        for (int index = 0; index < calls.size(); index++) {
            var call = calls.get(index);
            if (call.owner.equals("zone/moddev/mc/basepower/migration/OreSpawnProfileMigration")
                    && call.name.equals("migrateWorld")) profile = index;
            if (call.owner.equals(hook.name) && call.name.equals("prepareForgeRegistryMetadata")) registry = index;
        }
        assertTrue("Saved generation choices must be checked before world data is changed",
                profile >= 0 && registry > profile);
    }

    @Test public void ambiguousActiveDefaultStopsBeforeReplacingAProfile() throws Exception {
        AdvantageLegacyMigrations.registerPowerAdvantage();
        Path config = temporary.newFolder("collision").toPath();
        JsonObject source = legacyProfile();
        JsonObject current = JsonParser.parseString("""
                {"block":"basepower:crude_oil","enabled":true,
                 "dimensions":{"minecraft:overworld":{"enabled":true,"frequency":0.08}}}
                """).getAsJsonObject();
        source.getAsJsonObject("fluid_deposits").add("basepower:fluid_deposit/desert_crude_oil", current);
        Path file = config.resolve("orespawn-worldgen.json");
        Files.writeString(file, source.toString());
        byte[] before = Files.readAllBytes(file);
        try {
            OreSpawnProfileMigration.registerPowerProvider(config);
            fail("A colliding saved choice requires review, not a silent terrain change");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("needs review"));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    private static void assertMigrated(Path path) throws Exception {
        JsonObject result = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        JsonObject legacy = result.getAsJsonObject("ores").getAsJsonObject("poweradvantage:legacy/custom_oil");
        assertEquals("basepower:crude_oil", legacy.get("block").getAsString());
        assertEquals(0.375, legacy.getAsJsonObject("dimension_selectors")
                .getAsJsonObject("orespawn:all_except_nether_end").get("frequency").getAsDouble(), 0.0);
        assertEquals("poweradvantage:crude_oil is a literal note", result.get("history").getAsString());
        assertFalse(result.getAsJsonObject("fluid_deposits")
                .getAsJsonObject("basepower:fluid_deposit/desert_crude_oil").get("enabled").getAsBoolean());
    }
}
