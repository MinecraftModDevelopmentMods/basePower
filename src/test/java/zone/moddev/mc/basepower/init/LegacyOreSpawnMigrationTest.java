package zone.moddev.mc.basepower.init;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import zone.moddev.mc.basepower.compat.LegacyWorldgenIdentity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LegacyOreSpawnMigrationTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void legacyOilIsOneRetainedRuleAndOverridesFreshApiDefaults() throws Exception {
        Path config = temporary.newFolder("config").toPath();
        Path legacyDirectory = Files.createDirectories(config.resolve("orespawn"));
        Files.copy(getClass().getResourceAsStream("/orespawn-legacy/poweradvantage.json"),
                legacyDirectory.resolve("poweradvantage.json"));
        assertEquals(2, LegacyWorldgenIdentity.migrateConfig(config, "poweradvantage", "basepower"));
        JsonObject converted = new JsonParser().parse(new String(Files.readAllBytes(
                config.resolve("orespawn3/basepower.json")), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, converted.getAsJsonObject("spawns").entrySet().size());
        assertEquals(1, OreSpawnWorldGen.createProvider().toJson()
                .getAsJsonObject("fluid_deposits").entrySet().size());

        assertTrue(OreSpawnWorldGen.describeOverride(config).contains("legacy importer authority"));
    }
}
