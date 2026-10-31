package zone.moddev.mc.advantageworks.runner;

import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Unit evidence for the log gate, not a substitute for a packaged operating run. */
public class Native117RunnerLogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String CLEAN = "[Server thread/INFO]: Done (1.0s)!\n[Server thread/INFO]: Stopping server\n";

    private WorksRunner.BootLogs boot(String phase) throws Exception {
        File runtime = temporary.newFolder();
        File results = new File(runtime, "advantage-works-results");
        assertTrue(results.mkdir());
        return new WorksRunner.BootLogs(runtime, results, phase);
    }

    private static void write(File file, String text) throws Exception {
        Files.createDirectories(file.toPath().getParent());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static File log(WorksRunner.BootLogs boot, String kind) {
        return kind.equals("console") ? boot.console : new File(boot.runtime, "logs/" + kind + ".log");
    }

    private static void fill(WorksRunner.BootLogs boot) throws Exception {
        for (String kind : Arrays.asList("console", "latest", "debug")) write(log(boot, kind), CLEAN);
    }

    private static String receipt(WorksRunner.BootLogs boot) throws Exception {
        return Files.readString(new File(boot.results, boot.phase + "-logs.json").toPath());
    }

    private interface Action { void run() throws Exception; }
    private static Exception rejected(Action action) throws Exception {
        try { action.run(); } catch (Exception failure) { return failure; }
        throw new AssertionError("Invalid log evidence accepted");
    }

    @Test public void completeBootArchivesAllThreeExactLogsAndHashes() throws Exception {
        WorksRunner.BootLogs boot = boot("first-run");
        fill(boot);
        boot.archive();
        assertEquals("PASS", new JsonParser().parse(receipt(boot)).getAsJsonObject().get("status").getAsString());
        for (String kind : Arrays.asList("console", "latest", "debug")) {
            assertEquals(CLEAN, Files.readString(new File(boot.results, "first-run-" + kind + ".log").toPath()));
        }
        assertEquals(3, new JsonParser().parse(receipt(boot)).getAsJsonObject().getAsJsonArray("logs").size());
        assertTrue(receipt(boot).contains("sha256"));
    }

    @Test public void everyMissingLogBlocksPassAndStillArchivesTheOtherLogs() throws Exception {
        for (String missing : Arrays.asList("console", "latest", "debug")) {
            WorksRunner.BootLogs boot = boot("first-run");
            fill(boot);
            Files.delete(log(boot, missing).toPath());
            rejected(boot::archive);
            assertTrue(receipt(boot).contains("FAIL"));
            assertTrue(receipt(boot).contains(missing));
            assertTrue(boot.archived);
            assertFalse(boot.clean);
            rejected(boot::clearLiveLogs);
            for (String kind : Arrays.asList("console", "latest", "debug")) {
                if (!kind.equals(missing)) assertTrue(new File(boot.results, "first-run-" + kind + ".log").isFile());
            }
        }
    }

    @Test public void anErrorInAnySingleLogBlocksOtherwiseCleanEvidence() throws Exception {
        for (String kind : Arrays.asList("console", "latest", "debug")) {
            for (String error : Arrays.asList("[Worker/ERROR]: failed", "[Worker/FATAL]: failed",
                    "java.lang.IllegalStateException: failure", "java.lang.Error: failure", "Caused by: trouble", "ERROR StatusLogger: broken logging",
                    "Failed to reload data packs", "Advantage Works command failed")) {
                WorksRunner.BootLogs boot = boot("first-run");
                fill(boot);
                write(log(boot, kind), CLEAN + error + "\n");
                rejected(boot::archive);
                assertFalse(boot.clean);
                assertTrue(receipt(boot).contains(error));
                assertTrue(new File(boot.results, "first-run-" + kind + ".log").isFile());
            }
        }
    }

    @Test public void emptyOrTruncatedLogsCannotPassEvenWithoutErrorText() throws Exception {
        for (String text : Arrays.asList("", "[INFO]: Starting server\n", "Done (1s)!\n", "Stopping server\n")) {
            WorksRunner.BootLogs boot = boot("first-run");
            fill(boot);
            write(log(boot, "debug"), text);
            assertTrue(rejected(boot::archive).getMessage().contains("startup/shutdown"));
        }
    }

    @Test public void aCleanEarlierBootCannotSupplyMissingRestartDebugLog() throws Exception {
        WorksRunner.BootLogs first = boot("first-run");
        fill(first);
        first.archive();
        rejected(() -> new WorksRunner.BootLogs(first.runtime, first.results, "restart-run"));
        first.clearLiveLogs();
        WorksRunner.BootLogs second = new WorksRunner.BootLogs(first.runtime, first.results, "restart-run");
        write(second.console, CLEAN);
        write(log(second, "latest"), CLEAN);
        rejected(second::archive);
        assertTrue(receipt(second).contains("FAIL"));
        assertTrue(receipt(first).contains("PASS"));
    }

    @Test public void aFullRestartHasSeparateImmutableReceipts() throws Exception {
        WorksRunner.BootLogs first = boot("first-run");
        fill(first);
        first.archive();
        String original = receipt(first);
        first.clearLiveLogs();
        WorksRunner.BootLogs second = new WorksRunner.BootLogs(first.runtime, first.results, "restart-run");
        fill(second);
        second.archive();
        assertTrue(second.clean);
        assertEquals(original, receipt(first));
        rejected(first::archive);
        assertEquals(original, receipt(first));
    }

    @Test public void logsChangedAfterArchiveArePreservedAndPreventRestart() throws Exception {
        WorksRunner.BootLogs first = boot("first-run");
        fill(first);
        first.archive();
        write(log(first, "debug"), CLEAN + "[Worker/ERROR]: late failure\n");
        rejected(first::clearLiveLogs);
        assertTrue(log(first, "latest").isFile());
        assertTrue(log(first, "debug").isFile());
    }

    @Test public void staleConsoleAndUnsafePhaseCannotOverwriteOrEscape() throws Exception {
        WorksRunner.BootLogs boot = boot("first-run");
        write(boot.console, "preserve me");
        rejected(() -> new WorksRunner.BootLogs(boot.runtime, boot.results, "first-run"));
        assertEquals("preserve me", Files.readString(boot.console.toPath()));
        rejected(() -> new WorksRunner.BootLogs(boot.runtime, boot.results, "../escape"));
        assertFalse(new File(boot.runtime, "escape-console.log").exists());
    }

    @Test public void changedArchivesOrReceiptsCannotBeRepinnedByChangingLiveLogsToo() throws Exception {
        for (String changed : Arrays.asList("console", "latest", "debug", "receipt")) {
            WorksRunner.BootLogs boot = boot("first-run");
            fill(boot);
            boot.archive();
            File archived = new File(boot.results, "first-run-" + (changed.equals("receipt") ? "logs.json" : changed + ".log"));
            write(archived, CLEAN + "changed\n");
            if (!changed.equals("receipt")) write(log(boot, changed), CLEAN + "changed\n");
            rejected(boot::requireArchiveUnchanged);
            rejected(boot::clearLiveLogs);
            assertTrue(log(boot, "latest").isFile());
            assertTrue(log(boot, "debug").isFile());
        }
    }

    private static final String MIXIN_CLEANUP = "[31Oct2026 06:47:24.115] [main/DEBUG] [mixin/]: "
            + "Error cleaning class output directory: .mixin.out";

    private void rejectEventInEveryLog(String event) throws Exception {
        for (String kind : Arrays.asList("console", "latest", "debug")) {
            WorksRunner.BootLogs boot = boot("first-run");
            fill(boot);
            write(log(boot, kind), CLEAN + event + "\n");
            rejected(boot::archive);
            assertFalse(kind + ": " + event, boot.clean);
            assertTrue(Files.readString(new File(boot.results, "first-run-" + kind + ".log").toPath())
                    .contains(event));
        }
    }

    @Test public void exactNativeMixinDebugCleanupIsRetainedAndClassifiedSeparately() throws Exception {
        for (String event : Arrays.asList(MIXIN_CLEANUP,
                MIXIN_CLEANUP.replace("31Oct2026 06:47:24.115", "06:47:24"))) {
            for (String kind : Arrays.asList("console", "latest", "debug")) {
                WorksRunner.BootLogs boot = boot("first-run");
                fill(boot);
                write(log(boot, kind), CLEAN + event + "\n");
                boot.archive();
                assertTrue(boot.clean);
                String json = receipt(boot);
                assertTrue(json.contains("nonFatalDiagnostics"));
                assertTrue(json.contains("Mixin 0.8.4"));
                assertTrue(json.contains("Error cleaning class output directory: .mixin.out"));
                assertEquals(CLEAN + event + "\n", Files.readString(
                        new File(boot.results, "first-run-" + kind + ".log").toPath()));
                boot.requireArchiveUnchanged();
            }
        }
    }

    @Test public void cleanupAtErrorOrFatalSeverityStillFails() throws Exception {
        for (String severity : Arrays.asList("ERROR", "FATAL")) {
            rejectEventInEveryLog(MIXIN_CLEANUP.replace("main/DEBUG", "main/" + severity));
        }
    }

    @Test public void otherCleanupPathsLoggersThreadsAndExtraTextAreNotExempted() throws Exception {
        for (String event : Arrays.asList(
                MIXIN_CLEANUP.replace("[mixin/]", "[other/]"),
                MIXIN_CLEANUP.replace("main/DEBUG", "worker/DEBUG"),
                MIXIN_CLEANUP.replace("main/DEBUG", "main/INFO"),
                MIXIN_CLEANUP + "/class",
                MIXIN_CLEANUP + " java.io.IOException: denied",
                MIXIN_CLEANUP + " extra text")) {
            rejectEventInEveryLog(event);
        }
    }

    @Test public void debugDoesNotHideThrowableLinkageStackOrCommandFailures() throws Exception {
        String prefix = "[31Oct2026 06:47:24.115] [main/DEBUG] [mixin/]: ";
        for (String event : Arrays.asList(
                "java.lang.IllegalStateException: broken",
                "java.lang.LinkageError: broken",
                "java.lang.NoClassDefFoundError: missing",
                "java.lang.NoSuchMethodError: missing",
                "java.lang.Error: broken",
                "Exception in thread \"Server thread\" java.lang.IllegalStateException: broken",
                "Caused by: java.io.IOException: broken",
                "Advantage Works command failed")) {
            rejectEventInEveryLog(prefix + event);
        }
    }

    @Test public void nativeStackFramesAreFailuresEvenIfTheHeaderWasTruncated() throws Exception {
        for (String event : Arrays.asList(
                "\tat zone.moddev.Native117.run(Native117.java:42)",
                "\tat java.base/java.lang.Thread.run(Thread.java:833)",
                "\t... 12 more")) {
            rejectEventInEveryLog(event);
        }
    }

    @Test public void harmlessCleanupDoesNotMaskALaterRealFailure() throws Exception {
        rejectEventInEveryLog(MIXIN_CLEANUP + "\n[Server thread/ERROR] [minecraft/MinecraftServer]: broken");
        rejectEventInEveryLog(MIXIN_CLEANUP + "\njava.lang.NoSuchMethodError: broken\n"
                + "\tat zone.moddev.Native117.run(Native117.java:42)");
    }
}
