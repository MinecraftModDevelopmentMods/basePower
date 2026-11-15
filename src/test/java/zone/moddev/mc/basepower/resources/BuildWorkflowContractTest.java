package zone.moddev.mc.basepower.resources;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class BuildWorkflowContractTest {
    @Test public void pinnedDependencyCheckoutPolicyPrecedesCheckout() throws Exception {
        for (String name : new String[] {"ci.yml", "codeql-analysis.yml"}) {
            String workflow = new String(Files.readAllBytes(Paths.get(".github/workflows", name)),
                    StandardCharsets.UTF_8);
            int policy = workflow.indexOf("run: git config --global core.autocrlf false");
            int checkout = workflow.indexOf("- name: Check out pinned Base Metals");
            assertTrue(name, policy >= 0 && checkout > policy);
            assertTrue(name, workflow.contains("ref: ${{ steps.basemetals-pin.outputs.ref }}"));
            assertTrue(name, workflow.contains("s/^basemetals_source_commit=//p"));
        }
    }

    @Test public void workflowsBuildTheReleasedOreSpawnSource() throws Exception {
        for (String name : new String[] {"ci.yml", "codeql-analysis.yml"}) {
            String workflow = new String(Files.readAllBytes(Paths.get(".github/workflows", name)),
                    StandardCharsets.UTF_8);
            assertTrue(name, workflow.contains("ref: 487805da1c3ad9f90fd4679eacc052bab2271b0b"));
            assertTrue(name, workflow.contains("OreSpawn-4.1.7.113021-deobf.jar"));
        }
    }

    @Test public void localDependencyModeCannotPublish() throws Exception {
        String support = new String(Files.readAllBytes(Paths.get("gradle/development-dependencies.gradle")),
                StandardCharsets.UTF_8);
        assertTrue(support.contains("getProperty('allowUnreleasedDependencies', 'false')"));
        assertTrue(support.contains("tasks.register('verifyPublishableDependencies')"));
        assertTrue(support.contains("project.findProperty('basemetals_source_commit') == 'UNRELEASED'"));
        assertTrue(support.contains("it.name.startsWith('publish')"));
        assertTrue(support.contains("dependsOn publicationGuard"));
    }
}
