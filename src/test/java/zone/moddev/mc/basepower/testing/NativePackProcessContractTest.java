package zone.moddev.mc.basepower.testing;

import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativePackProcessContractTest {
    @Test public void redirectedChildUsesNonInteractiveTerminalWithoutDisablingLogging() {
        Path root = Path.of("build", "native-pack-resources").toAbsolutePath();
        var process = NativePackResourceProof.proofProcess(Path.of("java"), "classpath", root, root, root.resolve("test.log"));
        assertTrue(process.command().contains("-Dterminal.jline=false"));
        assertEquals("dumb", process.environment().get("TERM"));
        assertEquals(ProcessBuilder.Redirect.Type.WRITE, process.redirectOutput().type());
        assertTrue(process.redirectErrorStream());
        assertFalse(process.command().stream().anyMatch(argument -> argument.contains("log4j")));
    }

    @Test public void allWarningAndErrorFormsRemainFailures() {
        for (String diagnostic : new String[] {
                "2026-11-11T23:24:32Z main WARN Advanced terminal features are not available in this environment",
                "[main/WARN] Resource pack metadata is invalid",
                "[main/ERROR] Dependency discovery failed", "[main/FATAL] Cannot read resource pack"}) {
            assertThrows(AssertionError.class, () -> NativePackResourceProof.requireCleanOutput(diagnostic, Path.of("proof.log")));
        }
        NativePackResourceProof.requireCleanOutput("[main/INFO] Resources loaded", Path.of("proof.log"));
    }

    @Test public void nativeProofActuallyRunsWithTerminalDisabled() throws Exception {
        var proof = NativePackResourceProof.read();
        assertTrue(proof.get("interactiveTerminalDisabled").getAsBoolean());
        assertEquals(0, proof.getAsJsonArray("warningsAndErrors").size());
        assertTrue(proof.getAsJsonObject("CLIENT_RESOURCES").get("resourcesVerified").getAsInt() > 0);
        assertTrue(proof.getAsJsonObject("SERVER_DATA").get("resourcesVerified").getAsInt() > 0);
    }
}
