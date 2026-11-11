package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.Test;
import zone.moddev.mc.basepower.testing.NativePackResourceProof;
import static org.junit.Assert.*;

public class RequiredDependenciesTest {
    @Test public void nativeForgeRequiresBaseMetalsOnClientAndServer() throws Exception {
        JsonObject dependency = dependency("basemetals");
        assertTrue("Base Metals is required, not an optional integration", dependency.get("mandatory").getAsBoolean());
        assertEquals("AFTER", dependency.get("ordering").getAsString());
        assertEquals("BOTH", dependency.get("side").getAsString());
        Properties pins = pins();
        VersionRange range = VersionRange.createFromVersionSpec(dependency.get("versionRange").getAsString());
        assertTrue(range.containsVersion(new DefaultArtifactVersion(pins.getProperty("basemetals_version"))));
        assertFalse(range.containsVersion(new DefaultArtifactVersion("3.1.1.121111")));
        assertFalse(range.containsVersion(new DefaultArtifactVersion("4.0.0")));
    }

    @Test public void nativeRequiredDependenciesAgreeWithCurseForgeRequirements() throws Exception {
        Set<String> requirements = Arrays.stream(pins().getProperty("cf_requirements").split(","))
                .collect(Collectors.toSet());
        assertTrue(requirements.contains("base-metals"));
        assertTrue(requirements.contains("mmd-orespawn"));
        for (String id : new String[] {"basemetals", "orespawn", "forge", "minecraft"}) {
            assertTrue(id, dependency(id).get("mandatory").getAsBoolean());
        }
    }

    private static JsonObject dependency(String id) throws Exception {
        var matches = NativePackResourceProof.read().getAsJsonArray("dependencies").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(entry -> id.equals(entry.get("modId").getAsString())).toList();
        assertEquals("Exactly one native dependency declaration for " + id, 1, matches.size());
        return matches.getFirst();
    }

    private static Properties pins() throws Exception {
        Properties pins = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("gradle.properties"))) {
            pins.load(reader);
        }
        return pins;
    }
}
