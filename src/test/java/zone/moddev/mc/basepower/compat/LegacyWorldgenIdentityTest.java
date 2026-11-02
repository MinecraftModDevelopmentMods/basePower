package zone.moddev.mc.basepower.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LegacyWorldgenIdentityTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private Path root() throws IOException { return temporary.newFolder().toPath(); }
    private Path put(Path root, String relative, String json) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        return path;
    }
    private JsonObject read(Path path) throws IOException {
        return new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private String os1(String owner) {
        return "{\"dimensions\":[{\"dimension\":0,\"ores\":[{\"blockID\":\"" + owner
                + ":crude_oil\",\"frequency\":0.25,\"minHeight\":16,\"maxHeight\":64}]}]}";
    }
    private String provider(String owner, String block) {
        return "{\"schema_version\":3,\"provider_revision\":1,\"provider_modid\":\"" + owner
                + "\",\"fluid_deposits\":{\"" + owner + ":fluid_deposit/desert_crude_oil\":{\"block\":\""
                + block + "\",\"enabled\":false}}}";
    }
    private List<Path> backups(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".bak")).collect(Collectors.toList());
        }
    }
    private void fails(Path root) throws Exception {
        try {
            LegacyWorldgenIdentity.migrateConfig(root, "poweradvantage", "basepower");
            fail("Expected fail-closed migration");
        } catch (IOException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }

    @Test public void oldOs1FilenameIsRetiredAndParametersAreUnchanged() throws Exception {
        Path root = root();
        String original = os1("poweradvantage");
        Path old = put(root, "orespawn/poweradvantage.json", original);
        assertEquals(2, LegacyWorldgenIdentity.migrateConfig(root, "poweradvantage", "basepower"));
        assertFalse(Files.exists(old));
        JsonObject ore = read(root.resolve("orespawn/basepower.json")).getAsJsonArray("dimensions")
                .get(0).getAsJsonObject().getAsJsonArray("ores").get(0).getAsJsonObject();
        assertEquals("basepower:crude_oil", ore.get("blockID").getAsString());
        assertEquals(0.25, ore.get("frequency").getAsDouble(), 0);
        assertEquals(64, ore.get("maxHeight").getAsInt());
        assertEquals(original, new String(Files.readAllBytes(backups(root).get(0)), StandardCharsets.UTF_8));
        assertEquals(0, LegacyWorldgenIdentity.migrateConfig(root, "poweradvantage", "basepower"));
        assertEquals(1, backups(root).size());
    }

    @Test public void explicitProviderPreventsBridgeOverwritingItFromEitherLegacyFilename() throws Exception {
        Path root = root();
        Path explicit = put(root, "basepower-orespawn.json", provider("basepower", "othermod:oil"));
        byte[] authority = Files.readAllBytes(explicit);
        put(root, "orespawn/poweradvantage.json", os1("poweradvantage"));
        put(root, "orespawn/basepower.json", os1("basepower"));
        assertEquals(2, LegacyWorldgenIdentity.migrateConfig(root, "poweradvantage", "basepower"));
        assertArrayEquals(authority, Files.readAllBytes(explicit));
        assertFalse(Files.exists(root.resolve("orespawn/poweradvantage.json")));
        assertFalse(Files.exists(root.resolve("orespawn/basepower.json")));
        assertEquals(2, backups(root).size());
    }

    @Test public void oldProviderBecomesLoadedOwnerWithoutEnablingUserDisabledRule() throws Exception {
        Path root = root();
        put(root, "poweradvantage-orespawn.json", provider("poweradvantage", "poweradvantage:crude_oil"));
        assertEquals(2, LegacyWorldgenIdentity.migrateConfig(root, "poweradvantage", "basepower"));
        JsonObject migrated = read(root.resolve("basepower-orespawn.json"));
        assertEquals("basepower", migrated.get("provider_modid").getAsString());
        JsonObject rule = migrated.getAsJsonObject("fluid_deposits")
                .getAsJsonObject("basepower:fluid_deposit/desert_crude_oil");
        assertEquals("basepower:crude_oil", rule.get("block").getAsString());
        assertFalse(rule.get("enabled").getAsBoolean());
        assertEquals(0, LegacyWorldgenIdentity.migrateConfig(root, "poweradvantage", "basepower"));
    }

    @Test public void conflictingAuthoritiesFailBeforeAnyWriteOrBackup() throws Exception {
        Path root = root();
        Path old = put(root, "poweradvantage-orespawn.json", provider("poweradvantage", "poweradvantage:crude_oil"));
        put(root, "basepower-orespawn.json", provider("basepower", "othermod:oil"));
        byte[] before = Files.readAllBytes(old);
        fails(root);
        assertArrayEquals(before, Files.readAllBytes(old));
        assertTrue(backups(root).isEmpty());
    }

    @Test public void savedWorldRewritesIdsManifestsAndSelectorsButNotTagsOrText() throws Exception {
        Path world = root();
        Path profile = put(world, "serverconfig/orespawn-worldgen.json", "{\"schema_version\":5,"
                + "\"ores\":{\"electricadvantage:ore/lithium\":{\"block\":\"electricadvantage:li_ore\","
                + "\"enabled\":false,\"source_provider\":\"electricadvantage\",\"host_tags\":[\"electricadvantage:stone\"],"
                + "\"host_blocks\":[\"electricadvantage:steel_frame\",{\"block\":\"other:stone\"}],"
                + "\"notes\":\"electricadvantage:li_ore\"}},\"providers\":{\"electricadvantage\":{"
                + "\"profile_defaults_applied\":true,\"known_ores\":[\"electricadvantage:ore/lithium\"]}},"
                + "\"resource_sources\":{\"lithium\":\"electricadvantage\",\"sulfur\":\"none\"},"
                + "\"description\":\"electricadvantage:li_ore\"}");
        Path unrelated = put(world, "another/serverconfig/orespawn-worldgen.json", "not JSON");
        assertEquals(1, LegacyWorldgenIdentity.migrateWorld(world, "electricadvantage", "baseelectric"));
        JsonObject migrated = read(profile);
        JsonObject rule = migrated.getAsJsonObject("ores").getAsJsonObject("baseelectric:ore/lithium");
        assertEquals("baseelectric:li_ore", rule.get("block").getAsString());
        assertEquals("baseelectric", rule.get("source_provider").getAsString());
        assertEquals("electricadvantage:stone", rule.getAsJsonArray("host_tags").get(0).getAsString());
        assertEquals("baseelectric:steel_frame", rule.getAsJsonArray("host_blocks").get(0).getAsString());
        assertEquals("electricadvantage:li_ore", rule.get("notes").getAsString());
        assertEquals("baseelectric", migrated.getAsJsonObject("resource_sources").get("lithium").getAsString());
        assertEquals("none", migrated.getAsJsonObject("resource_sources").get("sulfur").getAsString());
        assertFalse(migrated.getAsJsonObject("providers").has("electricadvantage"));
        assertEquals("not JSON", new String(Files.readAllBytes(unrelated), StandardCharsets.UTF_8));
        assertEquals(0, LegacyWorldgenIdentity.migrateWorld(world, "electricadvantage", "baseelectric"));
    }

    @Test public void legacyWorldRulesRememberModernDefaultsSoTheyCannotBeAddedAlongside() throws Exception {
        Path world = root();
        Path profile = put(world, "serverconfig/orespawn-worldgen.json", "{\"schema_version\":5,\"ores\":{"
                + "\"poweradvantage:legacy/poweradvantage_crude_oil_0\":{\"block\":\"poweradvantage:crude_oil\","
                + "\"enabled\":false}}}");
        LegacyWorldgenIdentity.migrateWorld(world, "poweradvantage", "basepower");
        JsonObject migrated = read(profile);
        assertTrue(migrated.getAsJsonObject("ores").has("basepower:legacy/basepower_crude_oil_0"));
        JsonObject manifest = migrated.getAsJsonObject("providers").getAsJsonObject("basepower");
        assertTrue(manifest.get("profile_defaults_applied").getAsBoolean());
        assertEquals("basepower:fluid_deposit/desert_crude_oil", manifest.getAsJsonArray("known_fluid_deposits")
                .get(0).getAsString());
        assertEquals(0, LegacyWorldgenIdentity.migrateWorld(world, "poweradvantage", "basepower"));
    }

    @Test public void malformedOrDuplicateOwnedJsonFailsWithoutImportingDefaults() throws Exception {
        for (String invalid : new String[] { "[]", "{unquoted:true}", "{\"dimensions\":[],\"dimensions\":[]}",
                "{\"dimensions\":[]} {}", "{\"dimensions\":[{\"dimension\":null,\"ores\":[]}]}",
                "{\"dimensions\":[{\"dimension\":0,\"ores\":[{\"blockID\":null}]}]}" }) {
            Path root = root();
            Path old = put(root, "orespawn/poweradvantage.json", invalid);
            fails(root);
            assertEquals(invalid, new String(Files.readAllBytes(old), StandardCharsets.UTF_8));
            assertFalse(Files.exists(root.resolve("orespawn/basepower.json")));
            assertTrue(backups(root).isEmpty());
        }
    }

    @Test public void keyCollisionsInWorldAreNotSilentlyMerged() throws Exception {
        Path world = root();
        Path profile = put(world, "serverconfig/orespawn-worldgen.json", "{\"ores\":{"
                + "\"poweradvantage:ore/custom\":{\"block\":\"poweradvantage:crude_oil\",\"enabled\":false},"
                + "\"basepower:ore/custom\":{\"block\":\"basepower:crude_oil\",\"enabled\":true}}}");
        byte[] before = Files.readAllBytes(profile);
        try { LegacyWorldgenIdentity.migrateWorld(world, "poweradvantage", "basepower"); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Conflicting")); }
        assertArrayEquals(before, Files.readAllBytes(profile));
        assertTrue(backups(world).isEmpty());
    }

    @Test public void existingBridgeMarkerCannotSilentlySkipLegacyAuthority() throws Exception {
        Path root = root();
        put(root, "orespawn/poweradvantage.json", os1("poweradvantage"));
        put(root, ".orespawn-legacy-bridge-migrated", "provider_schema=4");
        fails(root);
        assertTrue(backups(root).isEmpty());
    }

    @Test public void standaloneOldImportsRequireReconciliationInsteadOfDuplicatingNewProvider() throws Exception {
        Path root = root();
        put(root, "orespawn/poweradvantage.json", os1("poweradvantage"));
        put(root, "orespawn-orespawn.json", "{\"ores\":{\"orespawn:legacy/poweradvantage/poweradvantage_crude_oil_0\":{"
                + "\"block\":\"poweradvantage:crude_oil\"}}}");
        fails(root);
        assertTrue(Files.exists(root.resolve("orespawn/poweradvantage.json")));
        assertTrue(backups(root).isEmpty());
    }

    @Test public void originalBackupSurvivesFurtherUserEditsAndSecondMigration() throws Exception {
        Path world = root();
        Path profile = put(world, "serverconfig/orespawn-worldgen.json", "{\"ores\":{\"poweradvantage:ore/custom\":{"
                + "\"block\":\"poweradvantage:crude_oil\",\"enabled\":false}}}");
        byte[] original = Files.readAllBytes(profile);
        LegacyWorldgenIdentity.migrateWorld(world, "poweradvantage", "basepower");
        Path backup = backups(world).get(0);
        put(world, "serverconfig/orespawn-worldgen.json", "{\"ores\":{\"poweradvantage:ore/custom\":{"
                + "\"block\":\"poweradvantage:crude_oil\",\"enabled\":true}}}");
        LegacyWorldgenIdentity.migrateWorld(world, "poweradvantage", "basepower");
        assertArrayEquals(original, Files.readAllBytes(backup));
        assertEquals(2, backups(world).size());
        assertTrue(read(profile).getAsJsonObject("ores").getAsJsonObject("basepower:ore/custom")
                .get("enabled").getAsBoolean());
    }

    @Test public void unsupportedPairsCannotBecomePathsAndMissingFilesAreNoOps() throws Exception {
        Path root = root();
        try { LegacyWorldgenIdentity.migrateConfig(root, "../escape", "basepower"); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(backups(root).isEmpty()); }
        assertEquals(0, LegacyWorldgenIdentity.migrateConfig(root, "poweradvantage", "basepower"));
        assertEquals(0, LegacyWorldgenIdentity.migrateWorld(root, "poweradvantage", "basepower"));
        assertEquals("baseelectric", LegacyWorldgenIdentity.migrateSelectorValue("electricadvantage",
                "electricadvantage", "baseelectric"));
        assertEquals("other", LegacyWorldgenIdentity.migrateSelectorValue("other", "electricadvantage", "baseelectric"));
    }

    @Test public void invalidLegacyNumbersAndUnsupportedDisableFailClosed() throws Exception {
        for (String extra : new String[] { "\"frequency\":\"oops\"", "\"frequency\":-1", "\"size\":1.5",
                "\"minHeight\":-1", "\"maxHeight\":257", "\"blockMeta\":16", "\"enabled\":false" }) {
            Path root = root();
            put(root, "orespawn/poweradvantage.json", "{\"dimensions\":[{\"dimension\":0,\"ores\":[{"
                    + "\"blockID\":\"poweradvantage:crude_oil\"," + extra + "}]}]}");
            fails(root);
            assertTrue(backups(root).isEmpty());
        }
    }

    @Test public void os3BlockNamesAreScopedAndCustomGeneratorNamesAreNotRewritten() throws Exception {
        Path root = root();
        put(root, "orespawn3/poweradvantage.json", "{\"version\":\"1.0\",\"spawns\":{\"custom\":{"
                + "\"feature\":\"poweradvantage:generator\",\"blocks\":[{\"name\":\"poweradvantage:crude_oil\","
                + "\"chance\":100,\"metadata\":0}]}}}");
        assertEquals(2, LegacyWorldgenIdentity.migrateConfig(root, "poweradvantage", "basepower"));
        JsonObject spawn = read(root.resolve("orespawn3/basepower.json")).getAsJsonObject("spawns").getAsJsonObject("custom");
        assertEquals("basepower:crude_oil", spawn.getAsJsonArray("blocks").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("poweradvantage:generator", spawn.get("feature").getAsString());
    }

    @Test public void preexistingCorruptBackupIsNeverOverwritten() throws Exception {
        Path world = root();
        String json = "{\"ores\":{\"poweradvantage:ore/custom\":{\"block\":\"poweradvantage:crude_oil\"}}}";
        Path profile = put(world, "serverconfig/orespawn-worldgen.json", json);
        LegacyWorldgenIdentity.migrateWorld(world, "poweradvantage", "basepower");
        Path backup = backups(world).get(0);
        Files.write(backup, "corrupt".getBytes(StandardCharsets.UTF_8));
        Files.write(profile, json.getBytes(StandardCharsets.UTF_8));
        try { LegacyWorldgenIdentity.migrateWorld(world, "poweradvantage", "basepower"); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("backup conflict")); }
        assertEquals("corrupt", new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
        assertEquals(json, new String(Files.readAllBytes(profile), StandardCharsets.UTF_8));
    }

    @Test public void wrongProviderFilenameAndPendingImportBesideSavedRulesAreRejected() throws Exception {
        Path root = root();
        put(root, "poweradvantage-orespawn.json", provider("basepower", "basepower:crude_oil"));
        fails(root);
        assertTrue(backups(root).isEmpty());
        Path other = root();
        put(other, "orespawn/poweradvantage.json", os1("poweradvantage"));
        put(other, "orespawn-worldgen.json", "{\"fluid_deposits\":{\"poweradvantage:fluid_deposit/desert_crude_oil\":{"
                + "\"block\":\"poweradvantage:crude_oil\"}}}");
        fails(other);
        assertTrue(backups(other).isEmpty());
    }

    @Test public void linkedAncestorCannotRedirectWorldMigration() throws Exception {
        Path world = root();
        Path outside = root();
        Path original = put(outside, "orespawn-worldgen.json", "{\"block\":\"poweradvantage:crude_oil\"}");
        try { Files.createSymbolicLink(world.resolve("serverconfig"), outside); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.Assume.assumeNoException(unavailable);
        }
        byte[] before = Files.readAllBytes(original);
        try { LegacyWorldgenIdentity.migrateWorld(world, "poweradvantage", "basepower"); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("linked/reparse")); }
        assertArrayEquals(before, Files.readAllBytes(original));
    }

    @Test public void bothOwnedPairsMigrateOneGlobalProfileWithoutLosingEitherAuthority() throws Exception {
        Path config = root();
        Path global = put(config, "orespawn-worldgen.json", "{\"schema_version\":6,"
                + "\"fluid_deposits\":{\"poweradvantage:fluid_deposit/desert_crude_oil\":{"
                + "\"block\":\"poweradvantage:crude_oil\",\"enabled\":false,\"source_provider\":\"poweradvantage\"}},"
                + "\"ores\":{\"electricadvantage:ore/lithium\":{\"block\":\"electricadvantage:li_ore\","
                + "\"enabled\":false,\"source_mod\":\"electricadvantage\"},\"electricadvantage:ore/sulfur\":{"
                + "\"block\":\"othermod:sulfur\",\"enabled\":true}},\"providers\":{"
                + "\"poweradvantage\":{\"provider_revision\":3,\"profile_defaults_applied\":true,"
                + "\"known_fluid_deposits\":[\"poweradvantage:fluid_deposit/desert_crude_oil\"]},"
                + "\"electricadvantage\":{\"provider_revision\":7,\"profile_defaults_applied\":true,"
                + "\"known_ores\":[\"electricadvantage:ore/lithium\",\"electricadvantage:ore/sulfur\"]}},"
                + "\"ore_providers\":{\"electricadvantage\":{\"provider_revision\":7,"
                + "\"known_ores\":[\"electricadvantage:ore/lithium\"]}},"
                + "\"resource_sources\":{\"crude_oil\":\"poweradvantage\",\"lithium\":\"electricadvantage\",\"sulfur\":\"none\"},"
                + "\"retrogen\":{\"enabled\":false,\"force\":false,\"revision\":17},\"notes\":null}");
        assertEquals(1, LegacyWorldgenIdentity.migrateConfig(config, "poweradvantage", "basepower"));
        assertEquals(1, LegacyWorldgenIdentity.migrateConfig(config, "electricadvantage", "baseelectric"));
        JsonObject migrated = read(global);
        JsonObject oil = migrated.getAsJsonObject("fluid_deposits").getAsJsonObject("basepower:fluid_deposit/desert_crude_oil");
        assertEquals("basepower:crude_oil", oil.get("block").getAsString());
        assertFalse(oil.get("enabled").getAsBoolean());
        JsonObject lithium = migrated.getAsJsonObject("ores").getAsJsonObject("baseelectric:ore/lithium");
        assertEquals("baseelectric:li_ore", lithium.get("block").getAsString());
        assertFalse(lithium.get("enabled").getAsBoolean());
        assertEquals("othermod:sulfur", migrated.getAsJsonObject("ores").getAsJsonObject("baseelectric:ore/sulfur")
                .get("block").getAsString());
        assertEquals(3, migrated.getAsJsonObject("providers").getAsJsonObject("basepower").get("provider_revision").getAsInt());
        assertEquals(7, migrated.getAsJsonObject("providers").getAsJsonObject("baseelectric").get("provider_revision").getAsInt());
        assertTrue(migrated.getAsJsonObject("ore_providers").has("baseelectric"));
        assertEquals("basepower", migrated.getAsJsonObject("resource_sources").get("crude_oil").getAsString());
        assertEquals("baseelectric", migrated.getAsJsonObject("resource_sources").get("lithium").getAsString());
        assertEquals("none", migrated.getAsJsonObject("resource_sources").get("sulfur").getAsString());
        assertEquals(17, migrated.getAsJsonObject("retrogen").get("revision").getAsInt());
        assertFalse(migrated.getAsJsonObject("retrogen").get("force").getAsBoolean());
        assertTrue(migrated.get("notes").isJsonNull());
        byte[] firstPass = Files.readAllBytes(global);
        List<Path> beforeBackups = backups(config);
        assertEquals(0, LegacyWorldgenIdentity.migrateConfig(config, "poweradvantage", "basepower"));
        assertEquals(0, LegacyWorldgenIdentity.migrateConfig(config, "electricadvantage", "baseelectric"));
        assertArrayEquals(firstPass, Files.readAllBytes(global));
        assertEquals(beforeBackups, backups(config));
    }

    @Test public void maintainedGlobalProfileIsAnExactNoOpOnRestart() throws Exception {
        Path config = root();
        Path profile = put(config, "orespawn-worldgen.json", currentProfile(8, "basepower"));
        byte[] before = Files.readAllBytes(profile);
        assertEquals(0, LegacyWorldgenIdentity.migrateConfig(config, "poweradvantage", "basepower"));
        assertEquals(0, LegacyWorldgenIdentity.migrateConfig(config, "electricadvantage", "baseelectric"));
        assertArrayEquals(before, Files.readAllBytes(profile));
        assertTrue(backups(config).isEmpty());
    }

    @Test public void maintainedWorldProfileIsAnExactNoOpOnRestart() throws Exception {
        Path world = root();
        Path profile = put(world, "serverconfig/orespawn-worldgen.json", currentProfile(7, "basepower"));
        byte[] before = Files.readAllBytes(profile);
        assertEquals(0, LegacyWorldgenIdentity.migrateWorld(world, "poweradvantage", "basepower"));
        assertEquals(0, LegacyWorldgenIdentity.migrateWorld(world, "electricadvantage", "baseelectric"));
        assertArrayEquals(before, Files.readAllBytes(profile));
        assertTrue(backups(world).isEmpty());
    }

    @Test public void maintainedProfilesMigrateIdentityWithoutChangingGenerationChoices() throws Exception {
        Path config = root();
        Path world = root();
        Path global = put(config, "orespawn-worldgen.json", currentProfile(8, "poweradvantage"));
        Path saved = put(world, "serverconfig/orespawn-worldgen.json", currentProfile(7, "poweradvantage"));
        byte[] globalBefore = Files.readAllBytes(global), worldBefore = Files.readAllBytes(saved);
        assertEquals(1, LegacyWorldgenIdentity.migrateConfig(config, "poweradvantage", "basepower"));
        assertEquals(1, LegacyWorldgenIdentity.migrateWorld(world, "poweradvantage", "basepower"));
        for (Path path : new Path[] { global, saved }) {
            JsonObject expected = new JsonParser().parse(currentProfile(path.equals(global) ? 8 : 7, "basepower")).getAsJsonObject();
            assertEquals(expected, read(path));
        }
        assertArrayEquals(globalBefore, Files.readAllBytes(backups(config).get(0)));
        assertArrayEquals(worldBefore, Files.readAllBytes(backups(world).get(0)));
        byte[] globalAfter = Files.readAllBytes(global), worldAfter = Files.readAllBytes(saved);
        assertEquals(0, LegacyWorldgenIdentity.migrateConfig(config, "poweradvantage", "basepower"));
        assertEquals(0, LegacyWorldgenIdentity.migrateWorld(world, "poweradvantage", "basepower"));
        assertArrayEquals(globalAfter, Files.readAllBytes(global));
        assertArrayEquals(worldAfter, Files.readAllBytes(saved));
        assertEquals(1, backups(config).size());
        assertEquals(1, backups(world).size());
    }

    @Test public void unknownGlobalAndWorldSchemasStillFailBeforeAnyWrite() throws Exception {
        for (boolean global : new boolean[] { true, false }) {
            Path root = root();
            Path profile = put(root, global ? "orespawn-worldgen.json" : "serverconfig/orespawn-worldgen.json",
                    currentProfile(global ? 9 : 8, "poweradvantage"));
            byte[] before = Files.readAllBytes(profile);
            try {
                if (global) LegacyWorldgenIdentity.migrateConfig(root, "poweradvantage", "basepower");
                else LegacyWorldgenIdentity.migrateWorld(root, "poweradvantage", "basepower");
                fail("Unknown schema must not be rewritten or downgraded");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("Unsupported worldgen profile schema")); }
            assertArrayEquals(before, Files.readAllBytes(profile));
            assertTrue(backups(root).isEmpty());
        }
    }

    private String currentProfile(int schema, String owner) {
        return "{\"schema_version\":" + schema + ",\"biome_defaults_revision\":3,\"ore_defaults_revision\":10,"
                + "\"geology_mode\":\"legacy\",\"manage_vanilla_ores\":false,\"suppress_all_ore_features\":true,"
                + "\"fluid_deposits\":{\"" + owner + ":fluid_deposit/desert_crude_oil\":{"
                + "\"block\":\"" + owner + ":crude_oil\",\"enabled\":false,\"source_provider\":\"" + owner + "\"}},"
                + "\"providers\":{\"" + owner + "\":{\"provider_revision\":3,\"profile_defaults_applied\":true,"
                + "\"known_fluid_deposits\":[\"" + owner + ":fluid_deposit/desert_crude_oil\"]}},"
                + "\"resource_sources\":{\"crude_oil\":\"" + owner + "\",\"sulfur\":\"mineralogy\"},"
                + "\"retrogen\":{\"enabled\":false,\"force\":false,\"revision\":17},"
                + "\"worldgen_aliases\":{\"example:stone\":\"minecraft:stone\"},"
                + "\"formations\":{\"algorithm\":\"stable_layers\",\"custom\":{\"waviness_amplitude\":60}},"
                + "\"notes\":\"poweradvantage:crude_oil\"}\n";
    }

    @Test public void bothLegacyAuthoritiesShareGlobalAndWorldProfilesWithNoFreshDefaultReinsertion() throws Exception {
        Path config = root();
        Path world = root();
        String json = "{\"schema_version\":5,\"ores\":{"
                + "\"poweradvantage:legacy/poweradvantage_crude_oil_0\":{\"block\":\"poweradvantage:crude_oil\",\"enabled\":false},"
                + "\"electricadvantage:legacy/electricadvantage_li_ore_0\":{\"block\":\"electricadvantage:li_ore\",\"enabled\":true},"
                + "\"electricadvantage:legacy/electricadvantage_sulfur_ore_1\":{\"block\":\"electricadvantage:sulfur_ore\",\"enabled\":false}}}";
        Path global = put(config, "orespawn-worldgen.json", json);
        Path saved = put(world, "serverconfig/orespawn-worldgen.json", json);
        for (String[] pair : new String[][] { { "poweradvantage", "basepower" }, { "electricadvantage", "baseelectric" } }) {
            assertEquals(1, LegacyWorldgenIdentity.migrateConfig(config, pair[0], pair[1]));
            assertEquals(1, LegacyWorldgenIdentity.migrateWorld(world, pair[0], pair[1]));
        }
        assertEquals(read(global), read(saved));
        JsonObject profile = read(saved);
        assertEquals(3, profile.getAsJsonObject("ores").entrySet().size());
        assertFalse(profile.has("fluid_deposits"));
        JsonObject manifests = profile.getAsJsonObject("providers");
        assertEquals("basepower:fluid_deposit/desert_crude_oil", manifests.getAsJsonObject("basepower")
                .getAsJsonArray("known_fluid_deposits").get(0).getAsString());
        assertEquals(2, manifests.getAsJsonObject("baseelectric").getAsJsonArray("known_ores").size());
        byte[] globalBefore = Files.readAllBytes(global);
        byte[] worldBefore = Files.readAllBytes(saved);
        for (String[] pair : new String[][] { { "poweradvantage", "basepower" }, { "electricadvantage", "baseelectric" } }) {
            assertEquals(0, LegacyWorldgenIdentity.migrateConfig(config, pair[0], pair[1]));
            assertEquals(0, LegacyWorldgenIdentity.migrateWorld(world, pair[0], pair[1]));
        }
        assertArrayEquals(globalBefore, Files.readAllBytes(global));
        assertArrayEquals(worldBefore, Files.readAllBytes(saved));
        assertEquals(2, backups(config).size());
        assertEquals(2, backups(world).size());
    }

    @Test public void electricOs1PlusDimensionsAndTwoOutputsSurviveFilenameMigration() throws Exception {
        Path config = root();
        put(config, "orespawn/electricadvantage.json", "{\"dimensions\":[{\"dimension\":\"+\",\"ores\":["
                + "{\"blockID\":\"electricadvantage:li_ore\",\"size\":8,\"variation\":2,\"frequency\":0.125,\"minHeight\":1,\"maxHeight\":32},"
                + "{\"blockID\":\"electricadvantage:sulfur_ore\",\"size\":16,\"variation\":8,\"frequency\":1,\"minHeight\":1,\"maxHeight\":32}]}]}");
        assertEquals(2, LegacyWorldgenIdentity.migrateConfig(config, "electricadvantage", "baseelectric"));
        JsonObject dimension = read(config.resolve("orespawn/baseelectric.json")).getAsJsonArray("dimensions").get(0).getAsJsonObject();
        assertEquals("+", dimension.get("dimension").getAsString());
        assertEquals(2, dimension.getAsJsonArray("ores").size());
        assertEquals("baseelectric:li_ore", dimension.getAsJsonArray("ores").get(0).getAsJsonObject().get("blockID").getAsString());
        assertEquals("baseelectric:sulfur_ore", dimension.getAsJsonArray("ores").get(1).getAsJsonObject().get("blockID").getAsString());
        assertEquals(0, LegacyWorldgenIdentity.migrateConfig(config, "electricadvantage", "baseelectric"));
    }
}
