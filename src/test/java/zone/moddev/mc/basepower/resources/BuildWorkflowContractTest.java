package zone.moddev.mc.basepower.resources;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.google.gson.JsonParser;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

public class BuildWorkflowContractTest {
    @Test public void testFixturesDoNotImportRestrictedUnsafeTypes() throws Exception {
        Pattern restrictedImport = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?sun\\.misc\\.Unsafe\\b");
        assertTrue(restrictedImport.matcher("import sun.misc.Unsafe;").find());
        assertTrue(restrictedImport.matcher("import static sun.misc.Unsafe.INVALID_FIELD_OFFSET;").find());
        assertFalse(restrictedImport.matcher("Class.forName(\"sun.misc.Unsafe\")").find());
        try (Stream<Path> sources = Files.walk(zone.moddev.mc.advantageworks.unit.TestProject.path("src/test/java"))) {
            for (Path path : (Iterable<Path>) sources.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                assertFalse(path.toString(), restrictedImport.matcher(source).find());
            }
        }
    }

    @Test public void nativeDependencyPolicyPrecedesNetworkAndBuild() throws Exception {
        Properties pins = new Properties();
        try (var input = Files.newBufferedReader(zone.moddev.mc.advantageworks.unit.TestProject.path("gradle.properties"), StandardCharsets.UTF_8)) {
            pins.load(input);
        }
        for (String name : new String[] {"ci.yml", "codeql-analysis.yml"}) {
            String workflow = new String(Files.readAllBytes(zone.moddev.mc.advantageworks.unit.TestProject.path(".github/workflows", name)),
                    StandardCharsets.UTF_8);
            int policy = workflow.indexOf("run: git config --global core.autocrlf false");
            int guardTests = workflow.indexOf("run: ./.github/test-native-dependencies.ps1");
            int dependencyGate = workflow.indexOf("run: ./.github/verify-native-dependencies.ps1");
            int compile = workflow.indexOf("./gradlew $MMD_GRADLE_TOOLCHAINS ");
            assertTrue(name, policy >= 0 && guardTests > policy && dependencyGate > guardTests && compile > dependencyGate);
            assertTrue(name, workflow.contains("fetch-depth: 0"));
            assertTrue(name, workflow.contains("master-" + pins.getProperty("minecraft_version") + "-neo"));
            assertTrue(name, workflow.contains("java-version: '" + pins.getProperty("java_setup_version") + ".0.LTS'"));
            assertFalse(name, workflow.contains("25.0.3"));
            assertFalse(name, workflow.contains("8.0.502"));
            assertFalse(name, workflow.contains("JAVA_HOME_16_X64"));
            assertFalse(name, workflow.contains("stage-orespawn-release"));
            assertFalse(name, workflow.contains("forge52Mavenizer"));
            assertTrue(name, workflow.contains("-I .github/pinned-native-inputs.gradle"));
            assertTrue(name, workflow.contains("-PbaseMetalsJar=$BASE_METALS_JAR"));
            assertTrue(name, workflow.contains("-PmmdlibJar=$MMDLIB_JAR"));
            assertTrue(name, workflow.contains("-PoreSpawnJar=$ORESPAWN_JAR"));
            assertTrue(name, workflow.contains("-Porg.gradle.java.installations.paths="));
            assertTrue(name, workflow.contains("-Porg.gradle.java.installations.auto-detect=false"));
            assertTrue(name, workflow.contains("-Porg.gradle.java.installations.auto-download=false"));
            assertFalse(name, workflow.contains("-Dorg.gradle.java.installations."));
            for (String line : workflow.split("\\R")) {
                if (line.contains("./gradlew ") && !line.contains("chmod")) {
                    assertTrue(name + ": " + line, line.contains("./gradlew $MMD_GRADLE_TOOLCHAINS "));
                }
            }
        }
        var root = zone.moddev.mc.advantageworks.unit.TestProject.path(".github");
        var lock = JsonParser.parseString(Files.readString(root.resolve("native-dependencies.json"))).getAsJsonObject();
        var target = lock.getAsJsonObject("target");
        assertEquals("21.1.265", target.get("neoforge").getAsString());
        assertEquals("HOSTED_INPUTS_READY", lock.get("status").getAsString());
        assertEquals("21", target.get("java").getAsString());
        assertEquals("2.0.148", target.get("moddev").getAsString());
        assertEquals("9.8.0", target.get("gradle").getAsString());
        Set<String> ids = new HashSet<>();
        for (var entry : lock.getAsJsonArray("dependencies")) {
            var pin = entry.getAsJsonObject();
            String id = pin.get("id").getAsString();
            assertTrue("Duplicate native dependency " + id, ids.add(id));
            assertEquals(pins.getProperty(pin.get("versionProperty").getAsString()), pin.get("version").getAsString());
            if (pin.get("state").getAsString().equals("PUBLISHED")) {
                String sha = pin.get("sha256").getAsString();
                assertTrue(id, sha.matches("[A-F0-9]{64}"));
                assertEquals(id, pins.getProperty(pin.get("hashProperty").getAsString()), sha);
            } else {
                assertEquals("basemetals", id);
                assertEquals("BOOTSTRAP_LOCAL_ONLY", lock.get("status").getAsString());
                assertEquals("LOCAL_SOURCE_UNCOMMITTED", pin.get("state").getAsString());
                assertTrue(pin.get("sha256").isJsonNull());
                assertTrue(pin.get("mavenVersion").isJsonNull());
                assertTrue(pin.getAsJsonObject("source").get("commit").isJsonNull());
            }
        }
        assertEquals(Set.of("basemetals", "mmdlib", "orespawn"), ids);
        assertEquals(3, lock.getAsJsonArray("dependencies").size());
        String gate = Files.readString(root.resolve("verify-native-dependencies.ps1"));
        assertTrue(gate.indexOf("Read-NativeDependencyPins") < gate.indexOf("Invoke-WebRequest"));
        assertTrue(gate.indexOf("Assert-NativeSourceAncestry $pin") < gate.indexOf("Invoke-WebRequest"));
        assertTrue(gate.indexOf("Assert-NativeDependencyArchive $path $pin") > gate.indexOf("Invoke-WebRequest"));
        String module = Files.readString(root.resolve("NativeDependencyPins.psm1"));
        assertTrue(module.contains("BOOTSTRAP_BLOCKED:"));
        assertTrue(module.contains("HOSTED_INPUTS_READY"));
        assertTrue(module.contains("gh api $Uri --jq '.status'"));
        assertTrue(module.contains("@('identical', 'ahead')"));
        assertTrue(module.contains("SHA-256 mismatch"));
        assertTrue(module.contains("META-INF/neoforge.mods.toml"));
        assertTrue(module.contains("zone/moddev/mc/basemetals/migration/LegacyWorldDataHook.class"));
        String metadata = Files.readString(zone.moddev.mc.advantageworks.unit.TestProject.path(
                "src/main/resources/META-INF/neoforge.mods.toml"));
        for (String id : ids) {
            assertTrue("Mandatory native dependency " + id, Pattern.compile(
                    "(?s)\\[\\[dependencies\\.basepower\\]\\]\\s*modId=\"" + id + "\"\\s*type=\"required\"")
                    .matcher(metadata).find());
        }
        String build = Files.readString(zone.moddev.mc.advantageworks.unit.TestProject.path("build.gradle"));
        assertTrue(build.contains("id 'net.neoforged.moddev' version '2.0.148'"));
        String wrapper = Files.readString(zone.moddev.mc.advantageworks.unit.TestProject.path(
                "gradle/wrapper/gradle-wrapper.properties"));
        assertTrue(wrapper.contains("gradle-9.8.0-bin.zip"));
        assertTrue(wrapper.contains("bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c"));
    }
}
