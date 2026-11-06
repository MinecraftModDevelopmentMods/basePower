package cyano.poweradvantage.api.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class LegacyOreSpawnConfigBridgeTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void convertsOs1RulesWithoutChangingTheirSource() throws Exception {
        Path config = temporary.newFolder("config").toPath();
        Path legacyDirectory = Files.createDirectories(config.resolve("orespawn"));
        Path source = legacyDirectory.resolve("poweradvantage.json");
        Files.copy(getClass().getResourceAsStream("/orespawn-legacy/poweradvantage.json"), source);
        byte[] original = Files.readAllBytes(source);

        LegacyOreSpawnConfigBridge.Result result =
                LegacyOreSpawnConfigBridge.migrate(config, "basepower", "poweradvantage");

        assertTrue(result.wasMigrated());
        assertEquals(1, result.getRuleCount());
        assertTrue(Files.exists(config.resolve("basepower-orespawn.json")));
        assertEquals(new String(original, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
        try (Reader reader = Files.newBufferedReader(result.getTarget(), StandardCharsets.UTF_8)) {
            JsonObject provider = new JsonParser().parse(reader).getAsJsonObject();
            JsonObject ores = provider.getAsJsonObject("ores");
            assertTrue(ores.has("basepower:legacy/basepower_crude_oil_0"));
            assertFalse(provider.getAsJsonObject("fluid_deposits")
                    .has("basepower:fluid_deposit/desert_crude_oil"));
        }
        String report = new String(Files.readAllBytes(
                config.resolve("orespawn-os3-migration-report.json")), StandardCharsets.UTF_8);
        assertTrue(report.contains("os1_config_source"));
        assertTrue(report.contains("basepower:legacy/basepower_crude_oil_0"));
    }

    @Test
    public void existingModernProfileRemainsAuthoritative() throws Exception {
        Path config = temporary.newFolder("authoritative").toPath();
        Path legacyDirectory = Files.createDirectories(config.resolve("orespawn"));
        Files.copy(getClass().getResourceAsStream("/orespawn-legacy/poweradvantage.json"),
                legacyDirectory.resolve("poweradvantage.json"));
        Files.write(config.resolve("orespawn-worldgen.json"), "{}".getBytes(StandardCharsets.UTF_8));

        LegacyOreSpawnConfigBridge.Result result =
                LegacyOreSpawnConfigBridge.migrate(config, "basepower", "poweradvantage");

        assertFalse(result.wasMigrated());
        assertFalse(Files.exists(config.resolve("basepower-orespawn.json")));
    }
}
