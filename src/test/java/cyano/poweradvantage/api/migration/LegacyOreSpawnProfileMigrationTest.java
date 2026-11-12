package cyano.poweradvantage.api.migration;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LegacyOreSpawnProfileMigrationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final String OLD = "poweradvantage:legacy/custom_oil";
    private static final String CURRENT = "basepower:fluid_deposit/desert_crude_oil";
    private static final Predicate<String> TARGETS = Set.of("basepower:crude_oil", "basepower:refined_oil",
            "baseelectric:li_ore", "baseelectric:sulfur_ore", "minecraft:nether_quartz_ore")::contains;

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }

    private static JsonObject profile() {
        return json("""
                {"schema_version":7,"place_fluid_deposits":false,"manage_vanilla_ores":false,
                 "ores":{"poweradvantage:legacy/custom_oil":{"block":"poweradvantage:crude_oil",
                  "enabled":true,"retrogen":false,"source_provider":"poweradvantage",
                  "dimension_selectors":{"orespawn:all_except_nether_end":{"enabled":true,
                    "min_y":16,"max_y":63,"frequency":0.375,"min_quantity":14,"max_quantity":64,
                    "node_size":32,"vertical_spread":50,"host_blocks":["minecraft:stone"],
                    "biome_ids":["other:custom_biome"],"private":{"x":-987654321}}}}},
                 "fluid_deposits":{},"history":"poweradvantage:crude_oil",
                 "providers":{"poweradvantage":{"known_ores":["poweradvantage:legacy/custom_oil"]}},
                 "ore_source_policies":{"retained-group":{"outputs":{"poweradvantage:legacy/custom_oil":1},
                   "placement_sources":{"orespawn:standard":"poweradvantage:legacy/custom_oil"},
                   "candidates":[{"source_id":"poweradvantage:legacy/custom_oil","owner":"poweradvantage",
                     "registry_id":"poweradvantage:crude_oil"}]}}}
                """);
    }

    private static JsonObject powerDefaults() throws Exception {
        try (var stream = LegacyOreSpawnProfileMigrationTest.class.getResourceAsStream("/data/basepower/orespawn/provider.json")) {
            assertNotNull(stream);
            return json(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static JsonObject electricDefaults() {
        return json("""
                {"schema_version":5,"provider_modid":"baseelectric","provider_revision":1,
                 "ores":{"baseelectric:ore/lithium":{"block":"baseelectric:li_ore","enabled":true,
                   "retrogen":false,"dimensions":{"minecraft:overworld":{"enabled":true,
                     "frequency":0.125,"min_y":1,"max_y":31,"min_quantity":6,"max_quantity":9,
                     "node_size":4,"vertical_spread":4,"host_blocks":["minecraft:stone"]}}},
                  "baseelectric:ore/sulfur":{"block":"baseelectric:sulfur_ore","enabled":true,
                   "retrogen":false,"dimensions":{"minecraft:overworld":{"enabled":true,
                     "frequency":1,"min_y":1,"max_y":31,"min_quantity":8,"max_quantity":23,
                     "node_size":4,"vertical_spread":4,"host_blocks":["minecraft:stone"]}}}}}
                """);
    }

    private static JsonObject legacyRule(JsonObject root) { return root.getAsJsonObject("ores").getAsJsonObject(OLD); }
    private static JsonObject placement(JsonObject root) {
        return legacyRule(root).getAsJsonObject("dimension_selectors").getAsJsonObject("orespawn:all_except_nether_end");
    }

    @Test public void preservesEveryNonOutputValueAndRulePolicyReference() throws Exception {
        JsonObject source = profile(), original = source.deepCopy(), defaults = powerDefaults(), originalDefaults = defaults.deepCopy();
        var plan = LegacyOreSpawnProfileMigration.plan(source, defaults, TARGETS);
        assertFalse(plan.getProblems().toString(), plan.isRefused());
        assertTrue(plan.isChanged());
        JsonObject result = plan.getJson(), expected = original.deepCopy();
        legacyRule(expected).addProperty("block", "basepower:crude_oil");
        expected.getAsJsonObject("ore_source_policies").getAsJsonObject("retained-group").getAsJsonArray("candidates")
                .get(0).getAsJsonObject().addProperty("registry_id", "basepower:crude_oil");
        JsonObject guard = defaults.getAsJsonObject("fluid_deposits").getAsJsonObject(CURRENT).deepCopy();
        guard.addProperty("enabled", false); guard.addProperty("source_provider", "basepower");
        expected.getAsJsonObject("fluid_deposits").add(CURRENT, guard);
        expected.getAsJsonObject("providers").add("basepower", json("{\"profile_defaults_applied\":true}"));
        assertEquals(expected, result);
        assertEquals(original, source); assertEquals(originalDefaults, defaults);
        result.addProperty("history", "caller mutation");
        assertEquals(expected, plan.getJson());
        assertEquals("poweradvantage:legacy/custom_oil", plan.getJson().getAsJsonObject("ore_source_policies")
                .getAsJsonObject("retained-group").getAsJsonObject("placement_sources").get("orespawn:standard").getAsString());
    }

    @Test public void sequentialOwnersDoNotConsumeEachOthersLegacyEvidenceOrWhitelist() throws Exception {
        JsonObject source = profile();
        source.getAsJsonObject("ores").add("pack:arbitrary_rule", json("""
                {"block":"electricadvantage:li_ore","enabled":true,
                 "dimension_selectors":{"orespawn:all_except_nether_end":{"frequency":0.125,"node_size":8}}}
                """));
        var power = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(),
                Set.of("basepower:crude_oil", "minecraft:nether_quartz_ore")::contains);
        assertFalse(power.getProblems().toString(), power.isRefused());
        assertEquals("electricadvantage:li_ore", power.getJson().getAsJsonObject("ores").getAsJsonObject("pack:arbitrary_rule").get("block").getAsString());
        var electric = LegacyOreSpawnProfileMigration.plan(power.getJson(), electricDefaults(), TARGETS);
        assertFalse(electric.getProblems().toString(), electric.isRefused());
        assertEquals("baseelectric:li_ore", electric.getJson().getAsJsonObject("ores").getAsJsonObject("pack:arbitrary_rule").get("block").getAsString());
        assertFalse(electric.getJson().getAsJsonObject("ores").getAsJsonObject("baseelectric:ore/lithium").get("enabled").getAsBoolean());
        assertFalse(LegacyOreSpawnProfileMigration.plan(electric.getJson(), powerDefaults(), TARGETS).isChanged());
        assertFalse(LegacyOreSpawnProfileMigration.plan(electric.getJson(), electricDefaults(), TARGETS).isChanged());
    }

    @Test public void disabledZeroRateAndDisabledDimensionStillSuppressMissingModernDefaults() throws Exception {
        for (String mode : List.of("disabled", "zero", "dimension-disabled")) {
            JsonObject source = profile();
            if (mode.equals("disabled")) legacyRule(source).addProperty("enabled", false);
            if (mode.equals("zero")) placement(source).addProperty("frequency", 0);
            if (mode.equals("dimension-disabled")) placement(source).addProperty("enabled", false);
            var plan = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS);
            assertFalse(mode + plan.getProblems(), plan.isRefused());
            assertFalse(plan.getJson().getAsJsonObject("fluid_deposits").getAsJsonObject(CURRENT).get("enabled").getAsBoolean());
            assertEquals(placement(source), placement(plan.getJson()));
            assertEquals(legacyRule(source).get("enabled"), legacyRule(plan.getJson()).get("enabled"));
        }
    }

    @Test public void activeOldAndCurrentOverlappingRulesRefuseEvenWhenEquivalent() throws Exception {
        for (boolean equivalent : List.of(true, false)) {
            JsonObject source = profile();
            JsonObject current = legacyRule(source).deepCopy();
            current.addProperty("block", "basepower:crude_oil");
            if (!equivalent) current.getAsJsonObject("dimension_selectors").getAsJsonObject("orespawn:all_except_nether_end").addProperty("frequency", 0.7);
            source.getAsJsonObject("ores").add("pack:current_oil", current);
            // Provenance stays literal; use non-legacy provenance for the independent current rule.
            current.remove("source_provider"); legacyRule(source).remove("source_provider");
            var plan = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS);
            assertTrue(plan.isRefused());
            assertEquals(source, plan.getJson());
            assertTrue(plan.getProblems().toString().contains(equivalent ? "equivalent" : "conflicting"));
        }
    }

    @Test public void activeOreVersusActiveFluidDepositCollisionRefuses() throws Exception {
        JsonObject source = profile(); source.addProperty("place_fluid_deposits", true);
        source.getAsJsonObject("fluid_deposits").add(CURRENT, powerDefaults().getAsJsonObject("fluid_deposits").get(CURRENT).deepCopy());
        assertTrue(LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS).isRefused());
    }

    @Test public void currentDefaultIdWithStaleProviderCannotEvadeCollision() throws Exception {
        JsonObject source = profile(); source.addProperty("place_fluid_deposits", true);
        JsonObject current = powerDefaults().getAsJsonObject("fluid_deposits").getAsJsonObject(CURRENT).deepCopy();
        current.addProperty("source_provider", "poweradvantage");
        source.getAsJsonObject("fluid_deposits").add(CURRENT, current);
        var plan = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS);
        assertTrue(plan.isRefused()); assertEquals(source, plan.getJson());
        assertTrue(plan.getProblems().toString().contains("active output collision"));
    }

    @Test public void legacyOilSingletonRetainsDisabledMasterAndCoordinates() throws Exception {
        JsonObject source = json("""
                {"schema_version":3,"place_crude_oil":false,"oil":{"block":"poweradvantage:crude_oil",
                 "min_y":-48,"max_y":36,"frequency":0.625,"min_radius":3,"max_radius":9}}
                """);
        var plan = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS);
        assertFalse(plan.getProblems().toString(), plan.isRefused());
        JsonObject result = plan.getJson(), expected = source.getAsJsonObject("oil").deepCopy();
        expected.addProperty("block", "basepower:crude_oil");
        assertEquals(expected, result.get("oil")); assertFalse(result.get("place_crude_oil").getAsBoolean());
        assertFalse(result.getAsJsonObject("fluid_deposits").getAsJsonObject(CURRENT).get("enabled").getAsBoolean());
    }

    @Test public void existingDisabledCurrentEntryAndItsEntirePayloadRemainUntouched() throws Exception {
        JsonObject source = profile(), current = powerDefaults().getAsJsonObject("fluid_deposits").getAsJsonObject(CURRENT).deepCopy();
        current.addProperty("enabled", false); current.addProperty("user-note", "poweradvantage:crude_oil");
        source.getAsJsonObject("fluid_deposits").add(CURRENT, current);
        var plan = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS);
        assertFalse(plan.isRefused()); assertEquals(current, plan.getJson().getAsJsonObject("fluid_deposits").get(CURRENT));
    }

    @Test public void disjointDimensionsAreNotTreatedAsDuplicatePlacements() throws Exception {
        JsonObject source = profile();
        source.getAsJsonObject("ores").add("pack:nether_oil", json("""
                {"block":"basepower:crude_oil","enabled":true,"dimensions":{"minecraft:the_nether":{"frequency":1}}}
                """));
        assertFalse(LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS).isRefused());
    }

    @Test public void knownWeightedDeepAndCachedOutputFieldsMigrateButOtherStringsDoNot() throws Exception {
        JsonObject source = profile(), rule = legacyRule(source);
        rule.addProperty("deep_output", "poweradvantage:refined_oil");
        rule.add("outputs", JsonParser.parseString("[{\"block\":\"poweradvantage:crude_oil\",\"weight\":0.3125,\"min_y\":-64,\"private\":\"poweradvantage:crude_oil\"}]"));
        source.add("private", json("{\"block\":\"poweradvantage:crude_oil\",\"outputs\":[\"electricadvantage:li_ore\"]}"));
        var plan = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS);
        assertFalse(plan.isRefused()); JsonObject after = legacyRule(plan.getJson());
        assertEquals("basepower:refined_oil", after.get("deep_output").getAsString());
        assertEquals("basepower:crude_oil", after.getAsJsonArray("outputs").get(0).getAsJsonObject().get("block").getAsString());
        assertEquals(rule.getAsJsonArray("outputs").get(0).getAsJsonObject().get("weight"), after.getAsJsonArray("outputs").get(0).getAsJsonObject().get("weight"));
        assertEquals(source.get("private"), plan.getJson().get("private"));
        assertEquals("poweradvantage:crude_oil", after.getAsJsonArray("outputs").get(0).getAsJsonObject().get("private").getAsString());
    }

    @Test public void schemasSevenEightAndExplicitWrappersPreserveMarkers() throws Exception {
        for (int schema : List.of(7, 8)) for (String wrapper : List.of("flat", "profile", "worldgen", "geology", "geology_profile", "worldgen_profile")) {
            JsonObject profile = profile(); profile.addProperty("schema_version", schema);
            JsonObject source = wrapper.equals("flat") ? profile : new JsonObject();
            if (!wrapper.equals("flat")) source.add(wrapper, profile);
            var plan = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS);
            assertFalse(plan.getProblems().toString(), plan.isRefused());
            JsonObject result = wrapper.equals("flat") ? plan.getJson() : plan.getJson().getAsJsonObject(wrapper);
            assertEquals(schema, result.get("schema_version").getAsInt());
            assertEquals("basepower:crude_oil", legacyRule(result).get("block").getAsString());
        }
    }

    @Test public void legacyProviderOverrideKeepsProviderAndRuleIdsWithoutForeignSeeds() throws Exception {
        JsonObject source = profile(); source.addProperty("schema_version", 4); source.addProperty("provider_modid", "poweradvantage");
        var plan = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS);
        assertFalse(plan.isRefused()); assertEquals("poweradvantage", plan.getJson().get("provider_modid").getAsString());
        assertEquals(Set.of(OLD), plan.getJson().getAsJsonObject("ores").keySet());
        assertFalse(plan.getJson().getAsJsonObject("fluid_deposits").has(CURRENT));
        assertFalse(plan.getWarnings().isEmpty());
    }

    @Test public void quartzImplicitRuleAndWeightedOutputUseVerifiedNativeNameOnly() throws Exception {
        JsonObject source = json("""
                {"schema_version":8,"manage_vanilla_ores":false,"ores":{
                 "minecraft:quartz_ore":{"enabled":true,"dimensions":{"minecraft:the_nether":{"frequency":7}},
                   "outputs":[{"block":"minecraft:quartz_ore","weight":100}]},
                 "minecraft:nether_quartz_ore":{"native_generation":true,"enabled":true,"dimensions":{"minecraft:the_nether":{"frequency":11.2}}}}}
                """);
        var plan = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS);
        assertFalse(plan.isRefused()); assertEquals(source.getAsJsonObject("ores").keySet(), plan.getJson().getAsJsonObject("ores").keySet());
        assertEquals("minecraft:nether_quartz_ore", plan.getJson().getAsJsonObject("ores").getAsJsonObject("minecraft:quartz_ore").get("block").getAsString());
        source.addProperty("manage_vanilla_ores", true);
        assertTrue(LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS).isRefused());
    }

    @Test public void rootFlagsSurviveAndAbsentCurrentManifestOnlyGetsApplicationGuard() throws Exception {
        JsonObject source = profile();
        var plan = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS);
        assertFalse(plan.getJson().get("place_fluid_deposits").getAsBoolean());
        assertFalse(plan.getJson().get("manage_vanilla_ores").getAsBoolean());
        assertEquals(source.getAsJsonObject("providers").get("poweradvantage"), plan.getJson().getAsJsonObject("providers").get("poweradvantage"));
        source.getAsJsonObject("providers").add("basepower", json("{\"profile_defaults_applied\":false,\"user\":1}"));
        var refused = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS);
        assertTrue(refused.isRefused()); assertEquals(source, refused.getJson());
    }

    @Test public void unavailableTargetsAndMalformedKnownFieldsRefuseWithoutPartialJson() throws Exception {
        JsonObject source = profile();
        for (Predicate<String> exists : List.<Predicate<String>>of(id -> false, id -> { throw new IllegalStateException("unbound"); })) {
            var plan = LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), exists);
            assertTrue(plan.isRefused()); assertEquals(source, plan.getJson());
        }
        legacyRule(source).add("block", json("{}"));
        assertTrue(LegacyOreSpawnProfileMigration.plan(source, powerDefaults(), TARGETS).isRefused());
    }

    @Test public void absentAndAlreadyCurrentDocumentsCreateNoEvidenceOrDirectories() throws Exception {
        Path absent = temporary.getRoot().toPath().resolve("missing/serverconfig/orespawn-worldgen.json");
        assertEquals(LegacyOreSpawnProfileMigration.Status.MISSING, LegacyOreSpawnProfileMigration.migrate(absent, powerDefaults(), TARGETS).status());
        assertFalse(Files.exists(absent.getParent()));
        Path file = temporary.newFile("current.json").toPath(); Files.writeString(file, "{ \"history\": \"poweradvantage:crude_oil\" }\n");
        byte[] original = Files.readAllBytes(file);
        assertEquals(LegacyOreSpawnProfileMigration.Status.UNCHANGED, LegacyOreSpawnProfileMigration.migrate(file, powerDefaults(), TARGETS).status());
        assertArrayEquals(original, Files.readAllBytes(file)); assertEquals(1, temporary.getRoot().list().length);
    }

    @Test public void diskMigrationBacksUpExactBytesReportsHashesAndRepeatsWithoutWrites() throws Exception {
        Path file = temporary.newFile("world.json").toPath(); Files.writeString(file, "\n" + profile() + "\n");
        byte[] before = Files.readAllBytes(file);
        var result = LegacyOreSpawnProfileMigration.migrate(file, powerDefaults(), TARGETS);
        assertTrue(result.wasMigrated()); assertArrayEquals(before, Files.readAllBytes(result.backupFile()));
        JsonObject report = json(Files.readString(result.reportFile()));
        assertEquals("MIGRATED", report.get("status").getAsString());
        assertEquals(hash(before), report.get("source_sha256").getAsString());
        assertEquals(hash(Files.readAllBytes(file)), report.get("result_sha256").getAsString());
        byte[] migrated = Files.readAllBytes(file), receipt = Files.readAllBytes(result.reportFile());
        assertEquals(LegacyOreSpawnProfileMigration.Status.UNCHANGED, LegacyOreSpawnProfileMigration.migrate(file, powerDefaults(), TARGETS).status());
        assertArrayEquals(migrated, Files.readAllBytes(file)); assertArrayEquals(receipt, Files.readAllBytes(result.reportFile()));
        assertEquals(3, temporary.getRoot().list().length);
    }

    @Test public void refusedAttemptIsRetainedAndDoesNotBlockLaterValidAttempt() throws Exception {
        Path file = temporary.newFile("profile.json").toPath(); Files.writeString(file, profile().toString());
        byte[] before = Files.readAllBytes(file);
        var refused = LegacyOreSpawnProfileMigration.migrate(file, powerDefaults(), id -> false);
        assertEquals(LegacyOreSpawnProfileMigration.Status.REFUSED, refused.status());
        byte[] refusal = Files.readAllBytes(refused.reportFile()); assertNull(refused.backupFile());
        assertArrayEquals(before, Files.readAllBytes(file));
        var green = LegacyOreSpawnProfileMigration.migrate(file, powerDefaults(), TARGETS);
        assertTrue(green.wasMigrated()); assertNotEquals(refused.reportFile(), green.reportFile());
        assertArrayEquals(refusal, Files.readAllBytes(refused.reportFile()));
    }

    @Test public void malformedDuplicateAndInvalidUtf8InputsNeverChangeSource() throws Exception {
        for (String raw : List.of("{\"ores\":{},\"ores\":{}}", "{\"ores\":{}} trailing", "{\"x\":NaN}", "[]")) {
            Path file = temporary.newFile().toPath(); Files.writeString(file, raw);
            try { LegacyOreSpawnProfileMigration.migrate(file, powerDefaults(), TARGETS); fail(raw); }
            catch (java.io.IOException expected) { assertEquals(raw, Files.readString(file)); }
        }
        Path bad = temporary.newFile().toPath(); byte[] invalid = {(byte) 0xC3, (byte) 0x28}; Files.write(bad, invalid);
        try { LegacyOreSpawnProfileMigration.migrate(bad, powerDefaults(), TARGETS); fail(); }
        catch (java.io.IOException expected) { assertArrayEquals(invalid, Files.readAllBytes(bad)); }
    }

    @Test public void conflictingBackupNeverGetsOverwritten() throws Exception {
        Path file = temporary.newFile("profile.json").toPath(); Files.writeString(file, profile().toString());
        byte[] source = Files.readAllBytes(file);
        Path backup = file.resolveSibling(file.getFileName() + ".advantage-output-" + hash(source) + ".bak");
        Files.writeString(backup, "protected evidence");
        try { LegacyOreSpawnProfileMigration.migrate(file, powerDefaults(), TARGETS); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("Conflicting migration backup")); }
        assertEquals("protected evidence", Files.readString(backup)); assertArrayEquals(source, Files.readAllBytes(file));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
