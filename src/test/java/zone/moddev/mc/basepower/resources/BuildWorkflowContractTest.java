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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class BuildWorkflowContractTest {
    @Test public void everySourceWorkflowTargetsTheDeclaredMinecraftBranch() throws Exception {
        Properties pins = new Properties();
        try (var input = Files.newBufferedReader(Paths.get("gradle.properties"), StandardCharsets.UTF_8)) {
            pins.load(input);
        }
        String branch = "master-" + pins.getProperty("minecraft_version");
        String valid = "on:\n  push:\n    branches: [" + branch
                + "]\n  pull_request:\n    branches:\n      - " + branch + "\npermissions:\n  contents: read\n";
        requireWorkflowTargets(valid, branch);
        assertThrows(AssertionError.class, () -> requireWorkflowTargets(
                valid.replace("      - " + branch, "      - " + branch + "-wrong # " + branch), branch));
        assertThrows(AssertionError.class, () -> requireWorkflowTargets(
                valid.replace("  pull_request:", "  unrelated:"), branch));
        for (String name : new String[] {"ci.yml", "codeql-analysis.yml", "validate-gradle-build.yml"}) {
            String workflow = Files.readString(Paths.get(".github/workflows", name), StandardCharsets.UTF_8);
            requireWorkflowTargets(workflow, branch);
        }
    }

    private static void requireWorkflowTargets(String workflow, String branch) {
        var events = Pattern.compile("(?ms)^  (push|pull_request):[ \\t]*\\R(.*?)(?=^  [A-Za-z_][A-Za-z_0-9]*:|^\\S|\\z)")
                .matcher(workflow);
        Set<String> found = new HashSet<>();
        Pattern reference = Pattern.compile(Pattern.quote(branch) + "(?![\\w.-])");
        while (events.find()) {
            String body = events.group(2).replaceAll("(?m)#.*$", "");
            assertTrue(events.group(1) + " must target " + branch, reference.matcher(body).find());
            found.add(events.group(1));
        }
        assertEquals(Set.of("push", "pull_request"), found);
    }

    @Test public void candidateUploadFollowsTheAuditedVersionInsteadOfAHardCodedFilename() throws Exception {
        String workflow = Files.readString(Paths.get(".github/workflows/ci.yml"), StandardCharsets.UTF_8);
        Pattern hardCodedJar = Pattern.compile("build/libs/basePower-[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+");
        assertTrue(hardCodedJar.matcher("build/libs/basePower-2.4.14.121111.jar").find());
        assertFalse("A version bump must not leave the candidate upload pointing at old jars",
                hardCodedJar.matcher(workflow).find());
        assertTrue(workflow.contains("build/libs/basePower-*.jar"));
        assertTrue(workflow.contains("if-no-files-found: error"));
        int audit = workflow.indexOf("- name: Build and audit release artifacts");
        int upload = workflow.indexOf("- name: Upload audited release candidate");
        assertTrue(audit >= 0 && upload > audit);
        assertTrue(workflow.contains("verifyReleaseArtifacts"));
    }

    @Test public void testFixturesDoNotImportRestrictedUnsafeTypes() throws Exception {
        Pattern restrictedImport = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?sun\\.misc\\.Unsafe\\b");
        assertTrue(restrictedImport.matcher("import sun.misc.Unsafe;").find());
        assertTrue(restrictedImport.matcher("import static sun.misc.Unsafe.INVALID_FIELD_OFFSET;").find());
        assertFalse(restrictedImport.matcher("Class.forName(\"sun.misc.Unsafe\")").find());
        try (Stream<Path> sources = Files.walk(Paths.get("src/test/java"))) {
            for (Path path : (Iterable<Path>) sources.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                assertFalse(path.toString(), restrictedImport.matcher(source).find());
            }
        }
    }

    @Test public void pinnedDependencyCheckoutPolicyPrecedesCheckout() throws Exception {
        Properties pins = new Properties();
        try (var input = Files.newBufferedReader(Paths.get("gradle.properties"), StandardCharsets.UTF_8)) {
            pins.load(input);
        }
        for (String name : new String[] {"ci.yml", "codeql-analysis.yml"}) {
            String workflow = new String(Files.readAllBytes(Paths.get(".github/workflows", name)),
                    StandardCharsets.UTF_8);
            int policy = workflow.indexOf("run: git config --global core.autocrlf false");
            int checkout = workflow.indexOf("- name: Check out pinned Base Metals");
            assertTrue(name, policy >= 0 && checkout > policy);
            assertTrue(name, workflow.contains("ref: " + pins.getProperty("basemetals_source_commit")));
            assertTrue(name, workflow.contains("build/libs/BaseMetals-" + pins.getProperty("basemetals_version") + ".jar"));
            assertTrue(name, workflow.contains("build/libs/OreSpawn-" + pins.getProperty("orespawn_version") + ".jar"));
            assertTrue(name, workflow.contains("ref: " + pins.getProperty("orespawn_source_commit")));
            assertTrue(name, workflow.contains("(cd \"$basemetals\" && bash gradle/stage-orespawn-release.sh \"$mirror\")"));
            assertTrue(name, workflow.contains("master-" + pins.getProperty("minecraft_version")));
            assertTrue(name, workflow.contains("java-version: '25.0.3+9.0.LTS'"));
            assertTrue(name, workflow.contains("java-version: '" + pins.getProperty("java_setup_version") + "'"));
            assertTrue(name, workflow.contains("java-version: '8.0.502+7'"));
            assertFalse(name, workflow.contains("JAVA_HOME_16_X64"));
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
    }
}
