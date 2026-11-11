package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.Test;
import static org.junit.Assert.*;

public class CombinedDevelopmentProfileTest {
    @Test public void combinedLaunchPinsTheCurrentMinecraftAndReleaseMetadata() throws Exception {
        Properties release = new Properties();
        try (var input = Files.newInputStream(Path.of("gradle.properties"))) { release.load(input); }
        String minecraft = release.getProperty("minecraft_version");
        Path path = Path.of("test-rig/profiles/native" + minecraft.replace(".", ""), "dev-core.json");
        var profile = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertEquals("development", profile.get("mode").getAsString());
        assertEquals(minecraft, profile.get("minecraftVersion").getAsString());
        assertEquals(release.getProperty("forge_version"), profile.get("forgeVersion").getAsString());
        assertEquals(release.getProperty("release_player_sha256"), profile.get("powerDeobfSha256").getAsString());
        assertEquals(2, profile.getAsJsonArray("mods").size());
        var seen = new java.util.HashSet<String>();
        for (var element : profile.getAsJsonArray("mods")) {
            var mod = element.getAsJsonObject();
            String id = mod.get("id").getAsString();
            assertTrue(seen.add(id));
            assertEquals(release.getProperty(id + "_version"), mod.get("version").getAsString());
            assertTrue(mod.get("sha256").getAsString().matches("[A-F0-9]{64}"));
        }
        assertEquals(java.util.Set.of("basesteam", "baseelectric"), seen);
    }
}
