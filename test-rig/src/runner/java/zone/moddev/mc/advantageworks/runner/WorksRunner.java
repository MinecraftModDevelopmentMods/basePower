package zone.moddev.mc.advantageworks.runner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URL;
import java.net.URLConnection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Owns disposable runtime directories and complete server-process restarts. */
public final class WorksRunner {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // The 2,000 mB batch needs 1,000 ticks at the still's 2:1, one-step/tick rate.
    private static final int OIL_DISTILLATION_RUN_SECONDS = 60;
    private static final List<String> DEFAULT_LIFECYCLE_STATIONS = Arrays.asList(
            "W-01", "W-02", "W-03", "W-04",
            "S-01", "S-02", "S-03", "S-04", "S-05", "S-06", "S-07", "S-08",
            "E-01", "E-02", "E-03", "E-04", "E-05", "E-06", "E-07", "E-08",
            "P-API-01");

    private WorksRunner() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        Profile profile = readNative119Profile(arguments.profile);
        if (arguments.serverPort != null) profile.serverPort = arguments.serverPort;
        File testRig = findTestRig(arguments.profile);
        File powerRoot = testRig.getParentFile().getCanonicalFile();
        // Pin validation precedes directory creation, downloads, and world access.
        requireInputPins(profile);
        profile.preparedSeed = preparedServerSeed(testRig, profile, arguments);
        if (arguments.validateOnly) {
            List<Map<String, Object>> inputs = stageMods(testRig, null, profile.mods);
            if ("packaged".equals(profile.mode)) {
                runtimeJavaHome(profile);
                if (profile.preparedSeed != null) packagedCommand(profile, profile.preparedSeed.root.toFile());
            }
            System.out.println("Native " + profile.minecraftVersion + " profile preflight only (no world opened): " + GSON.toJson(inputs));
            return;
        }
        String stamp = timestamp();
        File runtimeBase = resolve(testRig, profile.runtimeBase);
        File runtime = new File(runtimeBase, profile.name + "-" + stamp).getCanonicalFile();
        requireChild(powerRoot, runtime);
        if (profile.preparedSeed != null) profile.preparedSeed.requireDisjoint(runtime);
        if (!runtime.mkdirs()) throw new IllegalStateException("Cannot create runtime " + runtime);

        File mods = new File(runtime, "mods");
        File results = new File(runtime, "advantage-works-results");
        if (!mods.mkdirs() || !results.mkdirs()) {
            throw new IllegalStateException("Cannot create runtime directories under " + runtime);
        }
        writeRuntimeFiles(runtime, profile);
        stageConfigFixtures(testRig, runtime, profile.configFixtures);
        List<Map<String, Object>> staged = stageMods(testRig, mods, profile.mods);
        if ("packaged".equals(profile.mode)) preparePackagedServer(testRig, runtime, profile);
        List<String> lifecycleStations = arguments.lifecycleStations == null
                ? profile.lifecycleStations : arguments.lifecycleStations;
        boolean selectedLifecycle = arguments.lifecycleStations != null
                || !DEFAULT_LIFECYCLE_STATIONS.equals(lifecycleStations);
        int stationRunSeconds = arguments.stationRunSeconds == null
                ? profile.stationRunSeconds : arguments.stationRunSeconds;
        writeRunManifest(runtime, profile, staged, arguments.automated,
                lifecycleStations, stationRunSeconds);

        System.out.println("Advantage Works runtime: " + runtime);
        try {
        Process first = launch(profile, powerRoot, runtime, results, "first-run");
        File latestLog = new File(runtime, "logs/latest.log");
        waitForReady(first, latestLog, profile.startupTimeoutSeconds);
        if (profile.buildWorks) {
            if (selectedLifecycle) {
                for (String station : lifecycleStations) send(first, "advworks build " + station);
            } else {
                send(first, "advworks build all");
            }
            Thread.sleep(3000L);
        }

        if (arguments.automated) {
            if (profile.buildWorks) {
                for (String station : lifecycleStations) {
                    send(first, "advworks start " + station);
                    int runSeconds = "W-03".equals(station)
                            ? Math.max(stationRunSeconds, OIL_DISTILLATION_RUN_SECONDS) : stationRunSeconds;
                    Thread.sleep(runSeconds * 1000L);
                    send(first, "advworks checkpoint " + station + " before-restart");
                    send(first, "advworks stop " + station);
                }
                archiveStationResults(results, "first-run", lifecycleStations);
                checkStations(first, lifecycleStations, selectedLifecycle);
            }
            long firstShutdownDeadline = 0L;
            if (profile.worldgenSampleRadius > 0) {
                Thread.sleep(stationRunSeconds * 1000L);
                firstShutdownDeadline = worldgenDeadline(profile.shutdownTimeoutSeconds, System.nanoTime());
                awaitWorldgen(first, results, "first-run", profile.worldgenSampleRadius, firstShutdownDeadline);
            }
            send(first, "save-all");
            send(first, "stop");
            if (profile.worldgenSampleRadius > 0) requireExitUntil(first, firstShutdownDeadline);
            else requireExit(first, profile.shutdownTimeoutSeconds);
            auditPreparedServer(profile, runtime, "first-run-after");
            archiveBoot(profile, "first-run");
            if (profile.buildWorks) requireAcceptableStations(results, lifecycleStations);
            File firstWorldgen = new File(results, "worldgen-first-run.json");
            if (profile.worldgenSampleRadius > 0) {
                copyIfPresent(new File(results, "worldgen-sample.json"), firstWorldgen);
                requireWorldgenSummary(firstWorldgen, profile.worldgenExpectations, "first-run");
            }
            requireProfileAssertions(runtime, results, profile, "first-run",
                    profile.firstProfileAssertions);
            requireFileAssertions(runtime, results, "first-run", profile.firstFileAssertions);
            applyProfileMutations(runtime, profile);
            profile.boots.get(0).logs.clearLiveLogs();

            Process second = launch(profile, powerRoot, runtime, results, "restart-run");
            waitForReady(second, latestLog, profile.startupTimeoutSeconds);
            Thread.sleep(stationRunSeconds * 1000L);
            if (profile.buildWorks) {
                for (String station : lifecycleStations) {
                    send(second, "advworks checkpoint " + station + " after-restart");
                }
                archiveStationResults(results, "restart-run", lifecycleStations);
                checkStations(second, lifecycleStations, selectedLifecycle);
            }
            long secondShutdownDeadline = 0L;
            if (profile.worldgenSampleRadius > 0) {
                secondShutdownDeadline = worldgenDeadline(profile.shutdownTimeoutSeconds, System.nanoTime());
                awaitWorldgen(second, results, "restart-run", profile.worldgenSampleRadius, secondShutdownDeadline);
            }
            send(second, "save-all");
            send(second, "stop");
            if (profile.worldgenSampleRadius > 0) requireExitUntil(second, secondShutdownDeadline);
            else requireExit(second, profile.shutdownTimeoutSeconds);
            auditPreparedServer(profile, runtime, "restart-run-after");
            archiveBoot(profile, "restart-run");
            File restartWorldgen = new File(results, "worldgen-restart-run.json");
            if (profile.worldgenSampleRadius > 0) {
                copyIfPresent(new File(results, "worldgen-sample.json"), restartWorldgen);
                requireWorldgenSummary(restartWorldgen, profile.worldgenExpectations, "restart-run");
                if (profile.requireStableWorldgenCounts) {
                    requireStableWorldgenCounts(firstWorldgen, restartWorldgen,
                            profile.stableWorldgenCountExclusions);
                }
            }
            requireProfileAssertions(runtime, results, profile, "restart-run",
                    profile.restartProfileAssertions);
            requireFileAssertions(runtime, results, "restart-run", profile.restartFileAssertions);
            if (profile.buildWorks) {
                if (!selectedLifecycle) {
                    requireAcceptableSummary(new File(results, "all-summary.json"));
                }
                requireAcceptableStations(results, lifecycleStations);
            }
            for (Boot boot : profile.boots) boot.logs.requireArchiveUnchanged();
            System.out.println("Automated assertion checks complete; manual procedures remain unverified: " + results);
            return;
        }

        System.out.println("Server ready at localhost:" + profile.serverPort);
        System.out.println("Use the in-world signs or type Advantage Works commands. Type stop here when finished.");
        Thread input = new Thread(() -> bridgeInput(first), "advantage-works-console-input");
        input.setDaemon(true);
        input.start();
        requireExit(first, 0);
        auditPreparedServer(profile, runtime, "interactive-after");
        archiveBoot(profile, "first-run");
        for (Boot boot : profile.boots) boot.logs.requireArchiveUnchanged();
        } catch (Exception failure) {
            if (profile.preparedSeed != null) {
                profile.preparedSeed.closeAfterFailure(profile, runtime, failure);
            } else {
                closeBootsAfterFailure(profile, failure);
            }
            for (Boot boot : profile.boots) {
                if (!boot.logs.archived) {
                    try {
                        if (boot.process != null && boot.process.isAlive()) {
                            throw new IllegalStateException("Cannot archive a running server's logs");
                        }
                        boot.logs.archive();
                    } catch (Exception logFailure) { failure.addSuppressed(logFailure); }
                }
            }
            throw failure;
        }
    }

    private static Process launch(Profile profile, File powerRoot, File runtime, File results, String phase) throws Exception {
        if (profile.preparedSeed != null) {
            auditPreparedServer(profile, runtime, "boot-" + (profile.preparedSeed.processes.size() + 1) + "-before");
        }
        List<String> command = new ArrayList<>();
        File workingDirectory;
        if ("development".equals(profile.mode)) {
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            command.add(new File(powerRoot, windows ? "gradlew.bat" : "gradlew").getAbsolutePath());
            command.add("runServer");
            String relative = powerRoot.toPath().relativize(runtime.toPath()).toString().replace('\\', '/');
            command.add("-PpowerAdvantageRunDirectory=" + relative);
            command.add("--no-daemon");
            workingDirectory = powerRoot;
        } else {
            if (modularForge(profile)) {
                command.addAll(packagedCommand(profile, runtime));
            } else if (profile.launchCommand != null && !profile.launchCommand.isEmpty()) {
                for (String token : profile.launchCommand) {
                    command.add(expand(token).replace("${runtime}", runtime.getAbsolutePath())
                            .replace("${powerRoot}", powerRoot.getAbsolutePath()));
                }
            } else {
                command.add(javaExecutable(runtimeJavaHome(profile)).getAbsolutePath());
                command.add("-Xms512M");
                command.add("-Xmx2G");
                command.add("-jar");
                command.add(serverLauncherPath(profile));
                command.add("nogui");
            }
            workingDirectory = runtime;
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory);
        builder.redirectErrorStream(true);
        Boot boot = new Boot(new BootLogs(runtime, results, phase));
        profile.boots.add(boot);
        builder.redirectOutput(boot.logs.console);
        builder.environment().put("JAVA_HOME", "development".equals(profile.mode)
                ? gradleJavaHome(profile).getAbsolutePath() : runtimeJavaHome(profile).getAbsolutePath());
        builder.environment().put("ADVANTAGE_WORKS_RUNTIME", runtime.getAbsolutePath());
        Process process = builder.start();
        boot.process = process;
        if (profile.preparedSeed != null) profile.preparedSeed.processes.add(process);
        System.out.println("Server console: " + boot.logs.console);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (process.isAlive()) {
                try {
                    send(process, "save-all");
                    send(process, "stop");
                } catch (Exception ignored) {
                    process.destroy();
                }
            }
        }));
        return process;
    }

    static List<String> packagedCommand(Profile profile, File runtime) throws Exception {
        if (profile.launchCommand != null && !profile.launchCommand.isEmpty()) {
            throw new IllegalArgumentException("Packaged launches cannot override the pinned command");
        }
        File java = javaExecutable(runtimeJavaHome(profile));
        String launcher = serverLauncherPath(profile);
        requireModularLauncher(runtime, launcher);
        File loggingJar = new File(runtime, "libraries/net/minecraftforge/fmlloader/"
                + profile.forge.version + "/fmlloader-" + profile.forge.version + ".jar");
        PreparedServerSeed.requirePlain(loggingJar.toPath());
        try (ZipFile jar = new ZipFile(loggingJar)) {
            if (jar.getEntry("log4j2.xml") == null) throw new IllegalStateException("Forge logging configuration missing");
        }
        return Arrays.asList(java.getAbsolutePath(), "-Xms512M", "-Xmx2G", "--add-opens",
                "java.base/java.lang.invoke=ALL-UNNAMED",
                "-Dlog4j.configurationFile=jar:" + loggingJar.toURI().toASCIIString() + "!/log4j2.xml",
                "-Dfml.queryResult=confirm", "@" + launcher, "nogui");
    }

    static void requireModularLauncher(File runtime, String launcher) throws Exception {
        Path root = runtime.toPath().toAbsolutePath().normalize();
        PreparedServerSeed.requirePlain(root);
        Path args = root.resolve(launcher);
        PreparedServerSeed.requirePlain(args);
        boolean native118 = launcher.startsWith("libraries/net/minecraftforge/forge/1.18.2-40.3.0/");
        boolean native119 = launcher.startsWith("libraries/net/minecraftforge/forge/1.19.4-45.4.0/");
        Set<String> expected = new LinkedHashSet<>(Arrays.asList(
                "--add-modules ALL-MODULE-PATH",
                "--add-opens java.base/java.util.jar=cpw.mods.securejarhandler",
                "--add-exports java.base/sun.security.util=cpw.mods.securejarhandler",
                "-DlibraryDirectory=libraries", "cpw.mods.bootstraplauncher.BootstrapLauncher",
                "--launchTarget forgeserver", "--fml.forgeVersion " + (native119 ? "45.4.0" : native118 ? "40.3.0" : "37.1.1"),
                "--fml.mcVersion " + (native119 ? "1.19.4" : native118 ? "1.18.2" : "1.17.1"),
                "--fml.forgeGroup net.minecraftforge",
                "--fml.mcpVersion " + (native119 ? "20230314.122934" : native118 ? "20220404.173914" : "20210706.113038")));
        if (native118 || native119) expected.addAll(Arrays.asList(
                "--add-opens java.base/java.lang.invoke=cpw.mods.securejarhandler",
                "--add-exports jdk.naming.dns/com.sun.jndi.dns=java.naming",
                "-Djava.net.preferIPv6Addresses=system"));
        Set<String> seen = new LinkedHashSet<>();
        for (String line : Files.readAllLines(args, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String key = line;
            String paths = null;
            if (line.startsWith("-p ")) { key = "module-path"; paths = line.substring(3); }
            else if (line.startsWith("-DlegacyClassPath=")) { key = "legacy-class-path"; paths = line.substring(18); }
            else if (line.matches("-DignoreList=[A-Za-z0-9_.+-]+(?:,[A-Za-z0-9_.+-]+)*")) key = "ignore-list";
            else if (!expected.contains(line)) throw new IllegalArgumentException("Unexpected Forge modular argument: " + line);
            if (!seen.add(key)) throw new IllegalArgumentException("Duplicate Forge modular argument: " + key);
            if (paths != null) {
                String separator = launcher.endsWith("win_args.txt") ? ";" : ":";
                for (String path : paths.split(separator, -1)) {
                    if (!path.matches("libraries/[A-Za-z0-9_.+/-]+\\.jar")
                            || Arrays.asList(path.split("/", -1)).stream().anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) {
                        throw new IllegalArgumentException("Escaping or non-library Forge code path: " + path);
                    }
                    Path code = root.resolve(path);
                    PreparedServerSeed.requirePlain(code);
                    if (!Files.isRegularFile(code)) throw new IllegalArgumentException("Missing Forge code: " + path);
                }
            }
        }
        expected.addAll(Arrays.asList("module-path", "legacy-class-path", "ignore-list"));
        if (!seen.equals(expected)) throw new IllegalArgumentException("Incomplete Forge modular arguments");
    }

    private static void archiveBoot(Profile profile, String phase) throws Exception {
        for (Boot boot : profile.boots) {
            if (boot.logs.phase.equals(phase)) {
                if (boot.process.isAlive()) throw new IllegalStateException("Cannot archive a running server's logs");
                boot.logs.archive();
                return;
            }
        }
        throw new IllegalStateException("Missing boot record: " + phase);
    }

    private static void closeBootsAfterFailure(Profile profile, Exception primary) {
        for (Boot boot : profile.boots) {
            if (boot.process == null) continue;
            try {
                withWorldgenFailureCleanup(boot.process,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, Math.min(300, profile.shutdownTimeoutSeconds))),
                        System::nanoTime, () -> { throw primary; });
            } catch (Exception failure) {
                if (failure != primary) primary.addSuppressed(failure);
            }
        }
    }

    static final class Boot {
        final BootLogs logs;
        Process process;
        Boot(BootLogs logs) { this.logs = logs; }
    }

    /** Captures each process separately; no earlier boot can supply a later boot's evidence. */
    static final class BootLogs {
        private static final String NETTY_DEBUG = "[io.netty.util.internal.PlatformDependent0/]: ";
        private static final Map<String, String> NETTY_PROBES = Map.of(
                "direct buffer constructor: unavailable",
                "java.lang.UnsupportedOperationException: Reflective setAccessible(true) disabled",
                "jdk.internal.misc.Unsafe.allocateUninitializedArray(int): unavailable",
                "java.lang.IllegalAccessException: class io.netty.util.internal.PlatformDependent0$6 (in module io.netty.all) cannot access class jdk.internal.misc.Unsafe (in module java.base) because module java.base does not export jdk.internal.misc to module io.netty.all");
        private static final String NETTY_119_UNSAFE_PROBE =
                "java.lang.IllegalAccessException: class io.netty.util.internal.PlatformDependent0$7 (in module io.netty.common) cannot access class jdk.internal.misc.Unsafe (in module java.base) because module java.base does not export jdk.internal.misc to module io.netty.common";
        private static final Pattern NETTY_HEADER = Pattern.compile(
                "^\\[[^\\]]+\\] \\[[^\\]]+/DEBUG\\] " + Pattern.quote(NETTY_DEBUG) + "(.*)$");
        private static final Pattern NETTY_FRAME = Pattern.compile(
                "^\\tat [\\w.$/<>]+\\([^()]*\\)(?: ~?\\[[^\\]]*\\])?$");
        static final Pattern ERROR = Pattern.compile("/(?:ERROR|FATAL)\\]|\\[(?:ERROR|FATAL)\\]|^(?:ERROR|FATAL) StatusLogger|"
                + "\\b(?:[\\w$]+\\.)*[\\w$]*(?:Exception|Error)\\b|Exception in thread|Caused by:|"
                + "Failed to reload data packs|Encountered an unexpected exception|Advantage Works command failed|"
                + "^\\s+at\\s+\\S+\\([^\\r\\n]*\\)\\s*$|^\\s*\\.\\.\\.\\s+\\d+\\s+more\\s*$");
        // Pinned Mixin 0.8.4 catches this cleanup IOException and logs only its message at DEBUG.
        static final Pattern MIXIN_DEBUG_CLEANUP = Pattern.compile(
                "^\\[(?:[0-9]{2}:[0-9]{2}:[0-9]{2}|[0-9]{2}[A-Za-z]{3}[0-9]{4} "
                + "[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3})\\] "
                + "\\[main/DEBUG\\] \\[mixin/\\]: Error cleaning class output directory: \\.mixin\\.out$");
        static final String MIXIN_DEBUG_REASON = "Mixin 0.8.4 ExtensionClassExporter catches debug-export "
                + "cleanup IOException and logs its message at DEBUG";
        final File runtime, results, console;
        final String phase;
        final Map<String, String> archivedHashes = new LinkedHashMap<>();
        String receiptSha256;
        boolean archived, clean;

        BootLogs(File runtime, File results, String phase) throws Exception {
            if (!phase.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Unsafe boot phase");
            this.runtime = runtime;
            this.results = results;
            this.phase = phase;
            PreparedServerSeed.requirePlain(runtime.toPath());
            PreparedServerSeed.requirePlain(results.toPath());
            Path logDirectory = new File(runtime, "logs").toPath();
            if (Files.exists(logDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) PreparedServerSeed.requirePlain(logDirectory);
            console = new File(results, phase + "-console.log");
            for (String name : Arrays.asList("latest", "debug")) {
                if (Files.exists(new File(runtime, "logs/" + name + ".log").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("Stale live log before boot: " + name);
                }
                if (Files.exists(new File(results, phase + "-" + name + ".log").toPath())) {
                    throw new IllegalStateException("Existing archived boot log: " + name);
                }
            }
            if (new File(results, phase + "-logs.json").exists()) throw new IllegalStateException("Existing boot log receipt");
            Files.createFile(console.toPath());
        }

        void archive() throws Exception {
            if (archived) throw new IllegalStateException("Boot logs already archived: " + phase);
            archived = true;
            List<String> failures = new ArrayList<>();
            List<Map<String, Object>> logs = new ArrayList<>();
            for (String kind : Arrays.asList("console", "latest", "debug")) {
                File source = kind.equals("console") ? console : new File(runtime, "logs/" + kind + ".log");
                File target = new File(results, phase + "-" + kind + ".log");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", kind);
                row.put("file", target.getName());
                try {
                    PreparedServerSeed.requirePlain(source.toPath());
                    if (!source.equals(target)) Files.copy(source.toPath(), target.toPath());
                    row.put("size", Files.size(target.toPath()));
                    String hash = sha256(target);
                    row.put("sha256", hash);
                    archivedHashes.put(kind, hash);
                    boolean ready = false, stopped = false;
                    int count = 0;
                    List<Map<String, String>> nonFatalDiagnostics = new ArrayList<>();
                    try (BufferedReader reader = Files.newBufferedReader(target.toPath(), StandardCharsets.UTF_8)) {
                        String line;
                        String nettyProbe = null;
                        boolean nettyFrames = false;
                        while ((line = reader.readLine()) != null) {
                            ready |= line.contains("Done (");
                            stopped |= line.contains("Stopping server");
                            java.util.regex.Matcher header = NETTY_HEADER.matcher(line);
                            if (header.matches() && NETTY_PROBES.containsKey(header.group(1))) {
                                nettyProbe = header.group(1);
                                nettyFrames = false;
                                continue;
                            }
                            if (nettyProbe != null && (line.equals(NETTY_PROBES.get(nettyProbe))
                                    || (nettyProbe.startsWith("jdk.internal.misc.Unsafe") && line.equals(NETTY_119_UNSAFE_PROBE)))) {
                                nonFatalDiagnostics.add(Map.of("line", line, "reason",
                                        "Netty DEBUG optional capability probe: " + nettyProbe));
                                nettyProbe = null;
                                nettyFrames = true;
                                continue;
                            }
                            if (nettyFrames && NETTY_FRAME.matcher(line).matches()) continue;
                            nettyProbe = null;
                            nettyFrames = false;
                            if (MIXIN_DEBUG_CLEANUP.matcher(line).matches()) {
                                Map<String, String> diagnostic = new LinkedHashMap<>();
                                diagnostic.put("line", line);
                                diagnostic.put("reason", MIXIN_DEBUG_REASON);
                                nonFatalDiagnostics.add(diagnostic);
                            } else if (ERROR.matcher(line).find()) {
                                if (count++ < 5) failures.add(kind + ": " + line);
                            }
                        }
                    }
                    if (!ready || !stopped) failures.add(kind + ": missing startup/shutdown markers");
                    row.put("errorLines", count);
                    row.put("nonFatalDiagnostics", nonFatalDiagnostics);
                    row.put("ready", ready);
                    row.put("stopped", stopped);
                } catch (Exception failure) {
                    row.put("failure", failure.toString());
                    failures.add(kind + ": " + failure);
                }
                logs.add(row);
            }
            clean = failures.isEmpty();
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("phase", phase);
            receipt.put("status", clean ? "PASS" : "FAIL");
            receipt.put("logs", logs);
            receipt.put("failures", failures);
            Files.write(new File(results, phase + "-logs.json").toPath(),
                    GSON.toJson(receipt).getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW);
            receiptSha256 = sha256(new File(results, phase + "-logs.json"));
            if (!clean) throw new IllegalStateException("Boot log validation failed for " + phase + ": " + failures);
        }

        void requireArchiveUnchanged() throws Exception {
            if (!archived || !clean) throw new IllegalStateException("Unverified boot logs");
            for (String kind : Arrays.asList("console", "latest", "debug")) {
                File archivedLog = new File(results, phase + "-" + kind + ".log");
                PreparedServerSeed.requirePlain(archivedLog.toPath());
                if (!archivedHashes.get(kind).equals(sha256(archivedLog))) {
                    throw new IllegalStateException("Archived log changed: " + kind);
                }
            }
            File receipt = new File(results, phase + "-logs.json");
            PreparedServerSeed.requirePlain(receipt.toPath());
            if (!receiptSha256.equals(sha256(receipt))) throw new IllegalStateException("Boot log receipt changed");
        }

        void clearLiveLogs() throws Exception {
            requireArchiveUnchanged();
            for (String name : Arrays.asList("latest", "debug")) {
                File live = new File(runtime, "logs/" + name + ".log");
                PreparedServerSeed.requirePlain(live.toPath());
                if (!sha256(live).equals(archivedHashes.get(name))) {
                    throw new IllegalStateException("Log changed after archive: " + name);
                }
            }
            for (String name : Arrays.asList("latest", "debug")) Files.delete(new File(runtime, "logs/" + name + ".log").toPath());
        }
    }

    private static void waitForReady(Process process, File log, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new IllegalStateException("Server exited before becoming ready");
            if (log.isFile() && contains(log, "Done (")) return;
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Server did not become ready within " + timeoutSeconds + " seconds");
    }

    private static boolean contains(File file, String needle) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) if (line.contains(needle)) return true;
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void requireExit(Process process, int timeoutSeconds) throws Exception {
        if (timeoutSeconds <= 0) {
            int exit = process.waitFor();
            if (exit != 0) throw new IllegalStateException("Server process failed with exit " + exit);
            return;
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Server did not stop within " + timeoutSeconds + " seconds");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Server process failed with exit " + process.exitValue());
        }
    }

    static long worldgenDeadline(int timeoutSeconds, long now) {
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("Worldgen completion requires a bounded shutdown budget");
        return now + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    }

    private static void requireExitUntil(Process process, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Worldgen completion and shutdown exceeded the shared shutdown budget");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("Server process failed with exit " + process.exitValue());
    }

    private static void awaitWorldgen(Process process, File results, String phase, int radius, long deadline)
            throws Exception {
        withWorldgenFailureCleanup(process, deadline, System::nanoTime, () -> {
            WorldgenCompletion completion = new WorldgenCompletion(results, phase);
            send(process, "advworks sample-worldgen " + radius);
            awaitWorldgenCompletion(process::isAlive, completion, deadline, System::nanoTime, () -> Thread.sleep(100L));
        });
    }

    interface WorldgenPause { void pause() throws Exception; }

    static void withWorldgenFailureCleanup(Process process, long deadline, LongSupplier clock,
                                          WorldgenPause request) throws Exception {
        try {
            request.pause();
        } catch (Exception failure) {
            boolean interrupted = failure instanceof InterruptedException || Thread.currentThread().isInterrupted();
            try {
                if (process.isAlive()) {
                    send(process, "stop");
                    long remaining = deadline - clock.getAsLong();
                    if (!interrupted && !Thread.currentThread().isInterrupted() && remaining > 0) {
                        process.waitFor(remaining, TimeUnit.NANOSECONDS);
                    }
                }
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                if (cleanupFailure instanceof InterruptedException) interrupted = true;
            } finally {
                try {
                    if (process.isAlive()) process.destroyForcibly();
                } catch (Throwable cleanupFailure) {
                    if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            throw failure;
        }
    }

    static void awaitWorldgenCompletion(BooleanSupplier alive, WorldgenCompletion completion, long deadline,
                                       LongSupplier clock, WorldgenPause pause) throws Exception {
        while (true) {
            if (clock.getAsLong() >= deadline) {
                throw new IllegalStateException("Worldgen sample did not complete within the shared shutdown budget");
            }
            if (!alive.getAsBoolean()) throw new IllegalStateException("Server exited before worldgen sample completion");
            if (completion.complete()) return;
            pause.pause();
        }
    }

    static final class WorldgenCompletion {
        final File report;
        final File failure;
        final File previousReport;
        final File previousFailure;

        WorldgenCompletion(File results, String phase) throws Exception {
            report = new File(results, "worldgen-sample.json");
            failure = new File(results, "worldgen-sample-failed.json");
            // Removing the old path by archival is the freshness barrier, even for identical counts/timestamps.
            previousReport = archive(report, phase);
            previousFailure = archive(failure, phase);
        }

        boolean complete() throws Exception {
            if (failure.isFile()) throw new IllegalStateException("Worldgen sample failed: " + readJsonObject(failure));
            if (!report.isFile()) return false;
            JsonObject document = readJsonObject(report);
            if (!document.has("format") || document.get("format").getAsInt() != 1
                    || !document.has("station") || !"worldgen".equals(document.get("station").getAsString())
                    || !document.has("kind") || !"sample".equals(document.get("kind").getAsString())) {
                throw new IllegalStateException("Invalid worldgen completion report " + report);
            }
            for (String metric : Arrays.asList("radius", "chunksScanned", "centers", "counts",
                    "biomeViolations", "biomeBoundaryBlocks")) {
                if (!document.has(metric)) throw new IllegalStateException("Incomplete worldgen report: " + metric);
            }
            return true;
        }

        private static File archive(File file, String phase) throws Exception {
            if (!file.exists()) return null;
            File archived = new File(file.getParentFile(), file.getName() + ".before-" + phase + "-" + UUID.randomUUID());
            Files.move(file.toPath(), archived.toPath());
            return archived;
        }
    }

    private static void send(Process process, String command) throws Exception {
        process.getOutputStream().write((command + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
    }

    private static void bridgeInput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (process.isAlive() && (line = reader.readLine()) != null) send(process, line);
        } catch (Exception ignored) {
        }
    }

    private static void drainOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) System.out.println("[server] " + line);
        } catch (Exception ignored) {
        }
    }

    private static List<Map<String, Object>> stageMods(File testRig, File mods, List<ModInput> inputs)
            throws Exception {
        List<Map<String, Object>> staged = new ArrayList<>();
        for (ModInput input : inputs) {
            File source = resolve(testRig, input.path);
            if (!source.isFile()) {
                if (input.required) throw new IllegalStateException("Missing required mod " + input.id + ": " + source);
                continue;
            }
            if (input.file != null && !input.file.equals(source.getName())) {
                throw new IllegalStateException("Filename mismatch for " + input.id + ": expected "
                        + input.file + ", found " + source.getName());
            }
            String hash = sha256(source);
            if (!input.workspaceProduced && (input.sha256 == null || input.sha256.isEmpty())) {
                throw new IllegalStateException("Supplied mod " + input.id + " must pin SHA-256");
            }
            if (input.sha256 != null && !input.sha256.isEmpty()
                    && !hash.equalsIgnoreCase(input.sha256)) {
                throw new IllegalStateException("SHA-256 mismatch for " + input.id + ": " + hash);
            }
            if (input.verifyIdentity) verifyModIdentity(source, input.id, input.version);
            File destination = mods == null ? source : new File(mods, source.getName());
            if (mods != null) Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", input.id);
            record.put("version", input.version);
            record.put("file", destination.getName());
            record.put("sha256", hash);
            staged.add(record);
        }
        return staged;
    }

    private static void verifyModIdentity(File jar, String expectedId, String expectedVersion) throws Exception {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry("META-INF/mods.toml");
            if (entry == null) throw new IllegalStateException("No META-INF/mods.toml in " + jar);
            try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) >= 0) text.append(buffer, 0, count);
                Matcher sections = Pattern.compile("(?s)\\[\\[mods\\]\\](.*?)(?=\\[\\[|$)")
                        .matcher(text);
                while (sections.find()) {
                    String section = sections.group(1);
                    String actualId = tomlString(section, "modId");
                    if (!expectedId.equals(actualId)) continue;
                    String actualVersion = tomlString(section, "version");
                    if (expectedVersion != null && !expectedVersion.equals(actualVersion)) {
                        throw new IllegalStateException("Version mismatch for " + expectedId
                                + ": expected " + expectedVersion + ", found " + actualVersion);
                    }
                    return;
                }
                throw new IllegalStateException("Mod identity " + expectedId + " not found in " + jar);
            }
        }
    }

    private static String tomlString(String section, String key) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key)
                + "\\s*=\\s*\"([^\"]*)\"\\s*(?:#.*)?$").matcher(section);
        return matcher.find() ? matcher.group(1) : null;
    }

    static PreparedServerSeed preparedServerSeed(File testRig, Profile profile, Arguments arguments) throws Exception {
        if (arguments.preparedServerSeed == null && arguments.preparedServerSeedInventory == null
                && arguments.preparedServerSeedSha256 == null) return null;
        if (!"packaged".equals(profile.mode) || profile.forge == null || profile.forge.version == null
                || profile.minecraftVersion == null) {
            throw new IllegalArgumentException("Prepared server seed requires a packaged Forge profile");
        }
        if (arguments.preparedServerSeed == null || arguments.preparedServerSeedInventory == null
                || arguments.preparedServerSeedSha256 == null) {
            throw new IllegalArgumentException("Prepared seed requires path, inventory and inventory SHA-256 together");
        }
        return new PreparedServerSeed(preparedPath(testRig, arguments.preparedServerSeed),
                preparedPath(testRig, arguments.preparedServerSeedInventory), arguments.preparedServerSeedSha256,
                serverLauncherPath(profile), "1.19.4".equals(profile.minecraftVersion)
                        ? "libraries/net/minecraft/server/1.19.4-20230314.122934/server-1.19.4-20230314.122934-extra.jar"
                        : "1.18.2".equals(profile.minecraftVersion)
                        ? "libraries/net/minecraft/server/1.18.2-20220404.173914/server-1.18.2-20220404.173914-extra.jar"
                        : "1.17.1".equals(profile.minecraftVersion)
                        ? "libraries/net/minecraft/server/1.17.1-20210706.113038/server-1.17.1-20210706.113038-extra.jar"
                        : "minecraft_server." + profile.minecraftVersion + ".jar");
    }

    private static Path preparedPath(File base, File path) {
        return (path.isAbsolute() ? path.toPath() : base.toPath().resolve(path.toPath())).toAbsolutePath().normalize();
    }

    static void auditPreparedServer(Profile profile, File runtime, String phase) throws Exception {
        if (profile.preparedSeed == null) return;
        File results = new File(runtime, "advantage-works-results");
        String filename = "prepared-server-seed-" + phase + ".json";
        if (new File(results, filename).exists()) throw new IllegalStateException("Existing custody receipt: " + filename);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("phase", phase);
        report.put("inventorySha256", profile.preparedSeed.inventorySha256);
        report.put("codeFiles", profile.preparedSeed.files.size());
        try {
            profile.preparedSeed.verifySource();
            profile.preparedSeed.verifyTree(runtime.toPath(), false);
            report.put("status", "PASS");
        } catch (Exception failure) {
            report.put("status", "FAIL");
            report.put("failure", failure.toString());
            try { writeRunnerReport(results, filename, report); }
            catch (Exception receiptFailure) { failure.addSuppressed(receiptFailure); }
            throw failure;
        }
        writeRunnerReport(results, filename, report);
    }

    static final class PreparedServerSeed {
        static final String INVENTORY = "native117-runner-code-inventory.json";
        static final int MAX_INVENTORY_BYTES = 1024 * 1024;
        static final int MAX_FILES = 4096;
        static final long MAX_CODE_BYTES = 2L * 1024 * 1024 * 1024;
        final Path root, inventory;
        final String inventorySha256;
        final byte[] inventoryBytes;
        final Map<String, SeedFile> files = new LinkedHashMap<>();
        final Set<String> directories = new LinkedHashSet<>();
        final List<Process> processes = new ArrayList<>();

        PreparedServerSeed(Path root, Path inventory, String expectedHash, String launcher, String minecraft)
                throws Exception {
            this.root = root.toAbsolutePath().normalize();
            this.inventory = inventory.toAbsolutePath().normalize();
            if (expectedHash == null || !expectedHash.matches("[0-9A-F]{64}")) {
                throw new IllegalArgumentException("Prepared inventory requires an uppercase SHA-256 pin");
            }
            inventorySha256 = expectedHash;
            requirePlain(this.root);
            requirePlain(this.inventory);
            if (!Files.isDirectory(this.root) || !Files.isRegularFile(this.inventory)) {
                throw new IllegalArgumentException("Missing prepared seed or inventory");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = Files.newInputStream(this.inventory)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > MAX_INVENTORY_BYTES) throw new IllegalArgumentException("Inventory exceeds size bound");
                    bytes.write(buffer, 0, count);
                }
            }
            inventoryBytes = bytes.toByteArray();
            String actualHash = hex(MessageDigest.getInstance("SHA-256").digest(inventoryBytes));
            if (!inventorySha256.equals(actualHash)) throw new IllegalStateException("Prepared inventory SHA-256 mismatch");
            Set<String> folded = new LinkedHashSet<>();
            long total = 0;
            try (JsonReader reader = new JsonReader(new InputStreamReader(
                    new ByteArrayInputStream(inventoryBytes), StandardCharsets.UTF_8))) {
                reader.setLenient(false);
                reader.beginObject();
                if (!reader.hasNext() || !"files".equals(reader.nextName())) throw new IllegalArgumentException("Inventory requires files object");
                reader.beginObject();
                while (reader.hasNext()) {
                    if (files.size() >= MAX_FILES) throw new IllegalArgumentException("Inventory exceeds file bound");
                    String name = reader.nextName();
                    if (name.length() > 1024 || !name.matches("[A-Za-z0-9_.+/-]+") || name.startsWith("/")
                            || !(name.startsWith("libraries/") || name.equals(launcher) || name.equals(minecraft))) {
                        throw new IllegalArgumentException("Non-code or unsafe inventory path: " + name);
                    }
                    for (String part : name.split("/", -1)) {
                        if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Unsafe inventory path: " + name);
                    }
                    if (!folded.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate inventory path: " + name);
                    String hash = null;
                    Long size = null;
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String field = reader.nextName();
                        if (field.equals("sha256") && hash == null && reader.peek() == JsonToken.STRING) {
                            hash = reader.nextString();
                        } else if (field.equals("size") && size == null && reader.peek() == JsonToken.NUMBER) {
                            String number = reader.nextString();
                            if (!number.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Inventory size must be an integer");
                            size = Long.valueOf(number);
                        } else throw new IllegalArgumentException("Invalid or duplicate inventory field: " + field);
                    }
                    reader.endObject();
                    if (hash == null || !hash.matches("[0-9A-F]{64}") || size == null
                            || size > MAX_CODE_BYTES - total) throw new IllegalArgumentException("Invalid inventory hash/size");
                    total += size;
                    files.put(name, new SeedFile(hash, size));
                    int slash = name.indexOf('/');
                    while (slash >= 0) {
                        directories.add(name.substring(0, slash));
                        slash = name.indexOf('/', slash + 1);
                    }
                }
                reader.endObject();
                if (reader.hasNext()) throw new IllegalArgumentException("Unexpected inventory root field");
                reader.endObject();
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing inventory data");
            }
            if (!files.containsKey(launcher) || !files.containsKey(minecraft) || !directories.contains("libraries")) {
                throw new IllegalArgumentException("Inventory omits launcher, Minecraft server or libraries");
            }
            verifySource();
        }

        void requireDisjoint(File runtime) {
            Path target = runtime.toPath().toAbsolutePath().normalize();
            if (target.startsWith(root) || root.startsWith(target) || inventory.startsWith(target)) {
                throw new IllegalArgumentException("Prepared seed/inventory overlaps runtime");
            }
        }

        void verifySource() throws Exception {
            requirePlain(inventory);
            if (Files.size(inventory) != inventoryBytes.length || !inventorySha256.equals(sha256(inventory.toFile()))) {
                throw new IllegalStateException("Prepared inventory changed");
            }
            verifyTree(root, true);
        }

        void verifyTree(Path location, boolean codeOnly) throws Exception {
            final Path base = location.toAbsolutePath().normalize();
            requirePlain(base);
            final Set<String> seen = new LinkedHashSet<>();
            Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                    String relative = base.relativize(path).toString().replace('\\', '/');
                    if (!relative.isEmpty() && !directories.contains(relative)) {
                        if (codeOnly || relative.startsWith("libraries/") || relative.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                            throw new IOException("Unexpected code directory: " + relative);
                        }
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    requirePlain(path);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    String relative = base.relativize(path).toString().replace('\\', '/');
                    if (relative.equals(INVENTORY)) {
                        requirePlain(path);
                        try {
                            if (!inventorySha256.equals(sha256(path.toFile()))) throw new IOException("Staged inventory changed");
                        } catch (IOException failure) { throw failure; }
                        catch (Exception failure) { throw new IOException(failure); }
                        return FileVisitResult.CONTINUE;
                    }
                    if (!codeOnly && !relative.startsWith("libraries/") && !relative.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        return FileVisitResult.CONTINUE;
                    }
                    requirePlain(path);
                    SeedFile expected = files.get(relative);
                    if (!attributes.isRegularFile() || expected == null) throw new IOException("Unexpected code file: " + relative);
                    try {
                        if (Files.size(path) != expected.size || !sha256(path.toFile()).equals(expected.sha256)) {
                            throw new IOException("Prepared code differs: " + relative);
                        }
                    } catch (IOException failure) { throw failure; }
                    catch (Exception failure) { throw new IOException(failure); }
                    seen.add(relative);
                    return FileVisitResult.CONTINUE;
                }
            });
            if (!seen.equals(files.keySet())) throw new IllegalStateException("Prepared code files missing");
            if (!codeOnly) {
                Path receipt = base.resolve(INVENTORY);
                requirePlain(receipt);
                if (!inventorySha256.equals(sha256(receipt.toFile()))) throw new IllegalStateException("Staged inventory changed");
            }
        }

        void stage(File runtime) throws Exception {
            requireDisjoint(runtime);
            verifySource();
            Path target = runtime.toPath().toAbsolutePath().normalize();
            requirePlain(target);
            if (Files.exists(target.resolve("libraries"), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(target.resolve(INVENTORY), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Prepared runtime already contains code or an inventory");
            }
            File[] existing = runtime.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (existing == null || existing.length != 0) throw new IllegalStateException("Prepared runtime already contains root jars");
            for (String name : files.keySet()) {
                Path destination = target.resolve(name);
                Files.createDirectories(destination.getParent());
                Files.copy(root.resolve(name), destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Files.write(target.resolve(INVENTORY), inventoryBytes);
            verifySource();
            verifyTree(target, false);
        }

        void closeAfterFailure(Profile profile, File runtime, Exception primary) {
            boolean interrupted = primary instanceof InterruptedException || Thread.currentThread().isInterrupted();
            for (Process process : processes) {
                try {
                    if (process.isAlive()) {
                        send(process, "stop");
                        if (!interrupted) process.waitFor(Math.max(1, Math.min(300, profile.shutdownTimeoutSeconds)), TimeUnit.SECONDS);
                        if (process.isAlive()) process.destroyForcibly();
                        if (!interrupted) process.waitFor(5, TimeUnit.SECONDS);
                        if (process.isAlive()) throw new IllegalStateException("Prepared server still running; post-close audit unavailable");
                    }
                } catch (Exception failure) {
                    primary.addSuppressed(failure);
                    try { process.destroyForcibly(); }
                    catch (RuntimeException destroyFailure) { primary.addSuppressed(destroyFailure); }
                    if (failure instanceof InterruptedException) interrupted = true;
                }
            }
            try {
                if (processes.stream().anyMatch(Process::isAlive)) throw new IllegalStateException("Prepared server has not closed");
                auditPreparedServer(profile, runtime, "failure-after-close");
            } catch (Exception failure) { primary.addSuppressed(failure); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }

        private static void requirePlain(Path path) throws IOException {
            if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path.toAbsolutePath().normalize())) {
                throw new IOException("Linked/reparse prepared path rejected: " + path);
            }
        }

        private static String hex(byte[] bytes) {
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02X", value & 255));
            return result.toString();
        }
    }

    static final class SeedFile {
        final String sha256;
        final long size;
        SeedFile(String sha256, long size) { this.sha256 = sha256; this.size = size; }
    }

    static void preparePackagedServer(File testRig, File runtime, Profile profile) throws Exception {
        if (profile.preparedSeed != null) {
            profile.preparedSeed.stage(runtime);
            return;
        }
        if (profile.forge == null || profile.forge.version == null
                || profile.forge.installerPath == null || profile.forge.sha256 == null) {
            throw new IllegalArgumentException("Packaged profile must pin Forge installer path, version, and SHA-256");
        }
        File installer = resolve(testRig, profile.forge.installerPath);
        if (!installer.isFile()) {
            if (profile.forge.url == null) throw new IllegalStateException("Missing Forge installer " + installer);
            File parent = installer.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("Cannot create Forge cache " + parent);
            }
            download(profile.forge.url, installer);
        }
        String actual = sha256(installer);
        if (!profile.forge.sha256.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("Forge installer SHA-256 mismatch: " + actual);
        }
        File launcher = new File(runtime, serverLauncherPath(profile));
        if (launcher.isFile()) return;

        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable(runtimeJavaHome(profile)).getAbsolutePath(),
                "-jar", installer.getAbsolutePath(), "--installServer");
        builder.directory(runtime);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        drainOutput(process);
        if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0 || !launcher.isFile()) {
            if (process.isAlive()) process.destroyForcibly();
            throw new IllegalStateException("Forge server installation failed under " + runtime);
        }
    }

    private static void download(String address, File destination) throws Exception {
        System.out.println("Downloading pinned Forge installer from " + address);
        URLConnection connection = new URL(address).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
    }

    static String serverLauncherPath(Profile profile) {
        if (!modularForge(profile)) return "forge-" + profile.forge.version + ".jar";
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return "libraries/net/minecraftforge/forge/" + profile.forge.version + "/"
                + (windows ? "win_args.txt" : "unix_args.txt");
    }

    private static boolean modularForge(Profile profile) {
        return "1.17.1".equals(profile.minecraftVersion) || "1.18.2".equals(profile.minecraftVersion)
                || "1.19.4".equals(profile.minecraftVersion);
    }

    private static File runtimeJavaHome(Profile profile) throws Exception {
        if (!modularForge(profile)) return java8Home(profile);
        boolean native119 = "1.19.4".equals(profile.minecraftVersion);
        boolean java17 = native119 || "1.18.2".equals(profile.minecraftVersion);
        String variable = java17 ? "ADVANTAGE_WORKS_JAVA17_HOME" : "ADVANTAGE_WORKS_JAVA16_HOME";
        String configured = java17 ? profile.java17Home : profile.java16Home;
        if (configured == null || configured.trim().isEmpty()) configured = System.getenv(variable);
        File home = configured == null || configured.trim().isEmpty()
                ? new File(System.getProperty("java.home")) : new File(expand(configured));
        File release = new File(home, "release");
        java.util.Properties metadata = new java.util.Properties();
        if (release.isFile()) {
            try (InputStream input = Files.newInputStream(release.toPath())) { metadata.load(input); }
        }
        String javaVersion = native119 ? "17.0.1" : java17 ? "17.0.20.1" : "16.0.2";
        String temurin = native119 ? "17.0.1+12" : java17 ? "17.0.20.1+1" : "16.0.2+7";
        if (!javaExecutable(home).isFile() || !("\"" + javaVersion + "\"").equals(metadata.getProperty("JAVA_VERSION"))
                || !("\"Temurin-" + temurin + "\"").equals(metadata.getProperty("IMPLEMENTOR_VERSION"))) {
            throw new IllegalStateException("Set " + variable + " to the pinned Temurin " + temurin + " runtime: " + home);
        }
        return home.getCanonicalFile();
    }

    private static File java8Home(Profile profile) throws Exception {
        String configured = profile.java8Home;
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv("ADVANTAGE_WORKS_JAVA8_HOME");
        }
        File home;
        if (configured != null && !configured.trim().isEmpty()) {
            home = new File(expand(configured));
        } else {
            home = new File(System.getProperty("user.home"),
                    ".gradle/jdks/temurin-8-amd64-windows.2");
        }
        File executable = javaExecutable(home);
        if (!executable.isFile()) {
            throw new IllegalStateException("Java 8 is required for packaged Forge 1.16.5. Set "
                    + "ADVANTAGE_WORKS_JAVA8_HOME; checked " + home);
        }
        return home.getCanonicalFile();
    }

    private static File gradleJavaHome(Profile profile) throws Exception {
        String configured = profile.gradleJavaHome;
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv("ADVANTAGE_WORKS_GRADLE_JAVA_HOME");
        }
        File home;
        if (configured != null && !configured.trim().isEmpty()) {
            home = new File(expand(configured));
        } else if (javaMajorVersion() >= 17) {
            home = new File(System.getProperty("java.home"));
        } else {
            home = new File(System.getProperty("user.home"),
                    ".gradle/jdks/eclipse_adoptium-17-amd64-windows.2");
        }
        if (!javaExecutable(home).isFile()) {
            throw new IllegalStateException("Java 17 is required to invoke Gradle. Set "
                    + "ADVANTAGE_WORKS_GRADLE_JAVA_HOME; checked " + home);
        }
        return home.getCanonicalFile();
    }

    private static int javaMajorVersion() {
        String version = System.getProperty("java.specification.version", "8");
        if (version.startsWith("1.")) version = version.substring(2);
        int dot = version.indexOf('.');
        return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
    }

    private static File javaExecutable(File home) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return new File(new File(home, "bin"), windows ? "java.exe" : "java");
    }

    private static void writeRuntimeFiles(File runtime, Profile profile) throws Exception {
        write(new File(runtime, "eula.txt"), "eula=true\n");
        write(new File(runtime, "advantage-works.allow"), "Generated by Advantage Works runner.\n");
        write(new File(runtime, "banned-ips.json"), "[]\n");
        write(new File(runtime, "banned-players.json"), "[]\n");
        write(new File(runtime, "ops.json"), "[]\n");
        write(new File(runtime, "usercache.json"), "[]\n");
        write(new File(runtime, "whitelist.json"), "[]\n");
        File config = new File(runtime, "config");
        if (!config.isDirectory() && !config.mkdirs()) {
            throw new IllegalStateException("Cannot create config directory " + config);
        }
        write(new File(config, "poweradvantage.cfg"),
                "options {\n    S:recipe_mode=" + profile.recipeMode + "\n}\n");
        String properties = "allow-flight=true\n"
                + "difficulty=0\n"
                + "enable-command-block=true\n"
                + "force-gamemode=true\n"
                + "gamemode=1\n"
                + "generate-structures=false\n"
                + "generator-settings=" + profile.generatorSettings + "\n"
                + "level-name=" + profile.levelName + "\n"
                + "level-seed=" + profile.levelSeed + "\n"
                + "level-type=" + profile.levelType + "\n"
                + "max-players=8\n"
                + "motd=Advantage Works 1.16.5 Commissioning Ground\n"
                + "online-mode=false\n"
                + "server-ip=127.0.0.1\n"
                + "pvp=false\n"
                + "server-port=" + profile.serverPort + "\n"
                + "spawn-animals=false\n"
                + "spawn-monsters=false\n"
                + "spawn-npcs=false\n"
                + "spawn-protection=0\n"
                + "view-distance=10\n";
        write(new File(runtime, "server.properties"), properties);
    }

    private static void writeRunManifest(File runtime, Profile profile,
                                         List<Map<String, Object>> staged, boolean automated,
                                         List<String> lifecycleStations,
                                         int stationRunSeconds) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("profile", profile.name);
        manifest.put("mode", profile.mode);
        manifest.put("recipeMode", profile.recipeMode);
        manifest.put("sourceCommit", profile.sourceCommit);
        manifest.put("sourceVersion", profile.sourceVersion);
        manifest.put("minecraftVersion", profile.minecraftVersion);
        manifest.put("qualificationStatus", profile.qualificationStatus);
        manifest.put("legacySourceProfile", profile.legacySourceProfile);
        manifest.put("levelName", profile.levelName);
        manifest.put("levelType", profile.levelType);
        manifest.put("buildWorks", profile.buildWorks);
        manifest.put("worldgenSampleRadius", profile.worldgenSampleRadius);
        manifest.put("createdAtUtc", timestamp());
        manifest.put("automated", automated);
        manifest.put("lifecycleStations", lifecycleStations);
        manifest.put("stationRunSeconds", stationRunSeconds);
        manifest.put("oilDistillationMinimumRunSeconds", OIL_DISTILLATION_RUN_SECONDS);
        manifest.put("mods", staged);
        if (profile.preparedSeed != null) {
            manifest.put("preparedServerSeed", profile.preparedSeed.root.toString());
            manifest.put("preparedServerSeedInventorySha256", profile.preparedSeed.inventorySha256);
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(new File(runtime, "run-manifest.json")), StandardCharsets.UTF_8)) {
            GSON.toJson(manifest, writer);
        }
    }

    static Profile readProfile(File file) throws Exception {
        return readProfile(file, "1.15.2", "1.15.2-31.2.57");
    }

    static Profile readNative117Profile(File file) throws Exception {
        Profile profile = readProfile(file, "1.17.1", "1.17.1-37.1.1");
        if (!"UNQUALIFIED".equals(profile.qualificationStatus)) {
            throw new IllegalArgumentException("Native117 destination must start UNQUALIFIED");
        }
        for (ModInput input : profile.mods) {
            if (input.version == null || !input.version.endsWith(".117011")) {
                throw new IllegalArgumentException("Non-native117 destination mod version: " + input.id);
            }
        }
        return profile;
    }

    static Profile readNative119Profile(File file) throws Exception {
        Profile profile = readProfile(file, "1.19.4", "1.19.4-45.4.0");
        if (!"UNQUALIFIED".equals(profile.qualificationStatus)) {
            throw new IllegalArgumentException("The 1.19.4 destination must start UNQUALIFIED");
        }
        for (ModInput input : profile.mods) {
            if (input.version == null || !input.version.endsWith(".119041")) {
                throw new IllegalArgumentException("Non-1.19.4 destination mod version: " + input.id);
            }
        }
        if ("minecraft:flat".equals(profile.levelType)) {
            if (!profile.generatorSettings.isEmpty()) {
                throw new IllegalArgumentException("Works flat profiles must use the shared generator settings");
            }
            try (InputStream input = WorksRunner.class.getResourceAsStream(
                    "/assets/advantageworkstest/works/flat-generator.json")) {
                if (input == null) throw new IllegalStateException("Missing shared Works generator settings");
                var settings = GSON.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class);
                profile.generatorSettings = GSON.toJson(settings);
            }
        }
        return profile;
    }

    static Profile readNative116Profile(File file) throws Exception {
        Profile profile = readProfile(file, "1.16.5", "1.16.5-36.2.34");
        if (!"UNQUALIFIED".equals(profile.qualificationStatus)) {
            throw new IllegalArgumentException("Native116 destination must start UNQUALIFIED");
        }
        for (ModInput input : profile.mods) {
            if (input.version == null || !input.version.endsWith(".116051")) {
                throw new IllegalArgumentException("Non-native116 destination mod version: " + input.id);
            }
        }
        return profile;
    }

    static Profile readProfile(File file, String minecraft, String forge) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            Profile profile = GSON.fromJson(reader, Profile.class);
            if (profile == null || profile.name == null || profile.mode == null) {
                throw new IllegalArgumentException("Incomplete profile " + file);
            }
            if (!minecraft.equals(profile.minecraftVersion)) {
                throw new IllegalArgumentException("Historical source profile, not a native " + minecraft + " destination: " + file);
            }
            if (!"PORTED_UNQUALIFIED".equals(profile.portStatus)) {
                throw new IllegalStateException("Native " + minecraft + " profile is not ported: " + profile.portBlocker);
            }
            if (profile.forge == null || !forge.equals(profile.forge.version)) {
                throw new IllegalArgumentException("Native destination must select Forge " + forge);
            }
            if (profile.levelName == null || !profile.levelName.startsWith("AdvantageWorks")) {
                throw new IllegalArgumentException("Profile levelName must begin with AdvantageWorks: " + file);
            }
            return profile;
        }
    }

    static void requireInputPins(Profile profile) {
        if (modularForge(profile) && "packaged".equals(profile.mode)
                && profile.launchCommand != null && !profile.launchCommand.isEmpty()) {
            throw new IllegalArgumentException("Packaged launches cannot override the pinned command");
        }
        for (ModInput input : profile.mods) {
            if (input.sha256 == null || !input.sha256.matches("[A-Fa-f0-9]{64}")) {
                throw new IllegalStateException("Pending native destination SHA-256 pin: " + input.id);
            }
        }
    }

    static File findTestRig(File profile) throws Exception {
        File current = profile.getCanonicalFile().getParentFile();
        while (current != null) {
            if (new File(current, "build.gradle").isFile() && new File(current, "profiles").isDirectory()
                    && new File(current, "src/main/resources/assets/advantageworkstest").isDirectory()) return current;
            current = current.getParentFile();
        }
        throw new IllegalArgumentException("Profile must be inside the Advantage Works test-rig: " + profile);
    }

    private static void stageConfigFixtures(File testRig, File runtime,
                                            List<ConfigFixture> fixtures) throws Exception {
        for (ConfigFixture fixture : fixtures) {
            File source = resolve(testRig, fixture.source);
            if (!source.isFile()) throw new IllegalStateException("Missing config fixture " + source);
            File destination = resolve(runtime, fixture.destination);
            requireChild(runtime, destination);
            File parent = destination.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("Cannot create fixture directory " + parent);
            }
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void requireProfileAssertions(File runtime, File results, Profile profile,
                                                 String phase, List<JsonAssertion> assertions)
            throws Exception {
        if (assertions.isEmpty()) return;
        File profileFile = worldProfileFile(runtime, profile);
        JsonObject root = readJsonObject(profileFile);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean failed = false;
        for (JsonAssertion assertion : assertions) {
            JsonElement actual = jsonValue(root, assertion);
            boolean passed = assertion.present ? actual != null : actual == null;
            if (passed && assertion.present && actual != null) {
                if (assertion.expectedBoolean != null) {
                    passed = actual.isJsonPrimitive()
                            && actual.getAsBoolean() == assertion.expectedBoolean;
                } else if (assertion.expectedString != null) {
                    passed = actual.isJsonPrimitive()
                            && assertion.expectedString.equals(actual.getAsString());
                } else if (assertion.expectedNumber != null) {
                    passed = actual.isJsonPrimitive()
                            && Math.abs(actual.getAsDouble() - assertion.expectedNumber) < 0.0001D;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", assertion.id);
            row.put("section", assertion.section);
            row.put("entry", assertion.entry);
            row.put("field", assertion.field);
            row.put("expectedPresent", assertion.present);
            row.put("actual", actual == null ? null : actual);
            row.put("result", passed ? "PASS" : "FAIL");
            rows.add(row);
            failed |= !passed;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("phase", phase);
        report.put("profileFile", profileFile.getAbsolutePath());
        report.put("assertions", rows);
        writeRunnerReport(results, phase + "-orespawn-profile.json", report);
        if (failed) throw new IllegalStateException("OreSpawn profile assertions failed during " + phase);
    }

    private static void applyProfileMutations(File runtime, Profile profile) throws Exception {
        if (profile.profileMutations.isEmpty()) return;
        File profileFile = worldProfileFile(runtime, profile);
        JsonObject root = readJsonObject(profileFile);
        for (JsonMutation mutation : profile.profileMutations) {
            JsonObject entry = jsonEntry(root, mutation);
            if (mutation.field == null || mutation.field.isEmpty()) {
                throw new IllegalArgumentException("Profile mutation requires a field");
            }
            if (mutation.booleanValue != null) entry.addProperty(mutation.field, mutation.booleanValue);
            else if (mutation.stringValue != null) entry.addProperty(mutation.field, mutation.stringValue);
            else if (mutation.numberValue != null) entry.addProperty(mutation.field, mutation.numberValue);
            else throw new IllegalArgumentException("Profile mutation has no value");
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(profileFile), StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static JsonElement jsonValue(JsonObject root, JsonTarget target) {
        if (target.section == null || !root.has(target.section)
                || !root.get(target.section).isJsonObject()) return null;
        JsonObject section = root.getAsJsonObject(target.section);
        if (target.entry == null || !section.has(target.entry)) return null;
        JsonElement value = section.get(target.entry);
        if (target.field == null || target.field.isEmpty()) return value;
        if (!value.isJsonObject() || !value.getAsJsonObject().has(target.field)) return null;
        return value.getAsJsonObject().get(target.field);
    }

    private static JsonObject jsonEntry(JsonObject root, JsonTarget target) {
        JsonElement value = jsonValue(root, new JsonTargetCopy(target));
        if (value == null || !value.isJsonObject()) {
            throw new IllegalStateException("Missing OreSpawn profile entry "
                    + target.section + "/" + target.entry);
        }
        return value.getAsJsonObject();
    }

    private static File worldProfileFile(File runtime, Profile profile) throws Exception {
        File file = new File(new File(runtime, profile.levelName),
                "serverconfig/orespawn-worldgen.json").getCanonicalFile();
        requireChild(runtime, file);
        return file;
    }

    private static JsonObject readJsonObject(File file) throws Exception {
        if (!file.isFile()) throw new IllegalStateException("Missing JSON file " + file);
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement parsed = GSON.fromJson(reader, JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new IllegalStateException("Expected JSON object in " + file);
            }
            return parsed.getAsJsonObject();
        }
    }

    private static void requireFileAssertions(File runtime, File results, String phase,
                                              List<FileAssertion> assertions) throws Exception {
        if (assertions.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean failed = false;
        for (FileAssertion assertion : assertions) {
            File file = resolve(runtime, assertion.path);
            requireChild(runtime, file);
            int occurrences = file.isFile() && assertion.contains != null
                    ? occurrences(file, assertion.contains) : 0;
            boolean passed = file.isFile()
                    && (assertion.contains == null || occurrences >= assertion.minimumOccurrences);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", assertion.id);
            row.put("path", file.getAbsolutePath());
            row.put("contains", assertion.contains);
            row.put("occurrences", occurrences);
            row.put("result", passed ? "PASS" : "FAIL");
            rows.add(row);
            failed |= !passed;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("phase", phase);
        report.put("assertions", rows);
        writeRunnerReport(results, phase + "-file-assertions.json", report);
        if (failed) throw new IllegalStateException("File assertions failed during " + phase);
    }

    private static int occurrences(File file, String needle) throws Exception {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int result = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            result++;
            from += needle.length();
        }
        return result;
    }

    private static void requireWorldgenSummary(File reportFile, WorldgenExpectations expectations,
                                               String phase) throws Exception {
        JsonObject report = readJsonObject(reportFile);
        JsonObject counts = report.has("counts") ? report.getAsJsonObject("counts") : new JsonObject();
        JsonObject violations = report.has("biomeViolations")
                ? report.getAsJsonObject("biomeViolations") : new JsonObject();
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Long> expected : expectations.minimumCounts.entrySet()) {
            long actual = counts.has(expected.getKey()) ? counts.get(expected.getKey()).getAsLong() : 0L;
            if (actual < expected.getValue()) {
                failures.add(expected.getKey() + " count " + actual + " is below " + expected.getValue());
            }
        }
        for (Map.Entry<String, Long> expected : expectations.maximumCounts.entrySet()) {
            long actual = counts.has(expected.getKey()) ? counts.get(expected.getKey()).getAsLong() : 0L;
            if (actual > expected.getValue()) {
                failures.add(expected.getKey() + " count " + actual + " exceeds " + expected.getValue());
            }
        }
        if (expectations.zeroBiomeViolations) {
            for (Map.Entry<String, JsonElement> entry : violations.entrySet()) {
                if (entry.getValue().getAsLong() != 0L) {
                    failures.add(entry.getKey() + " has " + entry.getValue().getAsLong()
                            + " biome placement violations");
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Worldgen assertions failed during " + phase + ": " + failures);
        }
    }

    private static void requireStableWorldgenCounts(File first, File restart,
                                                    List<String> exclusions) throws Exception {
        JsonObject firstRoot = readJsonObject(first);
        JsonObject restartRoot = readJsonObject(restart);
        if (!equalExcept(firstRoot.getAsJsonObject("counts"), restartRoot.getAsJsonObject("counts"), exclusions)
                || !equalExcept(firstRoot.getAsJsonObject("biomeViolations"),
                restartRoot.getAsJsonObject("biomeViolations"), exclusions)) {
            throw new IllegalStateException("Worldgen sample changed across full process restart");
        }
    }

    private static boolean equalExcept(JsonObject first, JsonObject restart, List<String> exclusions) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : first.entrySet()) keys.add(entry.getKey());
        for (Map.Entry<String, JsonElement> entry : restart.entrySet()) keys.add(entry.getKey());
        for (String key : keys) {
            if (exclusions.contains(key)) continue;
            JsonElement firstValue = first.get(key);
            JsonElement restartValue = restart.get(key);
            if (firstValue == null ? restartValue != null : !firstValue.equals(restartValue)) return false;
        }
        return true;
    }

    private static void writeRunnerReport(File results, String name,
                                          Map<String, Object> report) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(new File(results, name)), StandardCharsets.UTF_8)) {
            GSON.toJson(report, writer);
        }
    }

    private static final class JsonTargetCopy extends JsonTarget {
        JsonTargetCopy(JsonTarget source) {
            section = source.section;
            entry = source.entry;
        }
    }
    private static File resolve(File base, String path) throws Exception {
        path = expand(path);
        File file = new File(path);
        return (file.isAbsolute() ? file : new File(base, path)).getCanonicalFile();
    }

    private static String expand(String value) {
        if (value == null) return null;
        String result = value;
        int start;
        while ((start = result.indexOf("${env:")) >= 0) {
            int end = result.indexOf('}', start);
            if (end < 0) throw new IllegalArgumentException("Unclosed environment variable in " + value);
            String name = result.substring(start + 6, end);
            String replacement = System.getenv(name);
            if (replacement == null || replacement.isEmpty()) {
                throw new IllegalStateException("Environment variable " + name + " is required by the profile");
            }
            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }
        return result;
    }

    private static void requireChild(File root, File child) {
        String rootPath = root.getAbsolutePath() + File.separator;
        if (!child.getAbsolutePath().startsWith(rootPath)) {
            throw new IllegalArgumentException("Runtime must remain inside PowerAdvantage: " + child);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02X", value));
        return result.toString();
    }

    private static void write(File file, String content) throws Exception {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.print(content);
        }
    }

    private static void copyIfPresent(File source, File destination) throws Exception {
        if (source.isFile()) Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void requireAcceptableSummary(File summary) throws Exception {
        if (!summary.isFile()) throw new IllegalStateException("Missing final result summary " + summary);
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(summary), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null || !root.has("summary") || !root.get("summary").isJsonObject()
                    || !root.has("stationStatuses") || !root.get("stationStatuses").isJsonObject()) {
                throw new IllegalStateException("Missing station summary or identities: " + summary);
            }
            JsonObject counts = root.getAsJsonObject("summary");
            JsonObject stations = root.getAsJsonObject("stationStatuses");
            Map<String, Integer> observed = new LinkedHashMap<>();
            int completed = 0;
            if (stations.entrySet().size() != DEFAULT_LIFECYCLE_STATIONS.size()) {
                throw new IllegalStateException("Incomplete station summary: " + stations);
            }
            for (String station : DEFAULT_LIFECYCLE_STATIONS) {
                if (!stations.has(station)) throw new IllegalStateException("Missing station " + station);
                String status = stations.get(station).getAsString();
                requireKnownStatus(status);
                observed.put(status, observed.getOrDefault(status, 0) + 1);
                if (!"SKIP".equals(status)) completed++;
            }
            if (completed == 0 || counts.entrySet().size() != observed.size()) {
                throw new IllegalStateException("No completed station checks or inconsistent counts: " + counts);
            }
            for (Map.Entry<String, Integer> count : observed.entrySet()) {
                if (!counts.has(count.getKey()) || !counts.get(count.getKey()).getAsJsonPrimitive().isNumber()
                        || counts.get(count.getKey()).getAsDouble() != count.getValue()) {
                    throw new IllegalStateException("Station counts disagree with identities: " + counts);
                }
            }
        }
    }

    private static void checkStations(Process process, List<String> stations, boolean selected) throws Exception {
        if (!selected) {
            send(process, "advworks check all");
            return;
        }
        for (String station : stations) send(process, "advworks check " + station);
    }

    private static void requireAcceptableStations(File results, List<String> stations) throws Exception {
        if (stations.isEmpty()) throw new IllegalStateException("No stations requested");
        int completed = 0;
        for (String station : stations) {
            File result = new File(results, station + "-latest.json");
            if (!result.isFile()) throw new IllegalStateException("Missing final station result " + result);
            try (InputStreamReader reader = new InputStreamReader(
                    new FileInputStream(result), StandardCharsets.UTF_8)) {
                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                String status = root != null && root.has("status") ? root.get("status").getAsString() : "";
                requireKnownStatus(status);
                if (!root.has("station") || !station.equals(root.get("station").getAsString())) {
                    throw new IllegalStateException("Wrong station result: " + result);
                }
                if ("SKIP".equals(status)) {
                    if (!root.has("skipReason") || root.get("skipReason").getAsString().trim().isEmpty()) {
                        throw new IllegalStateException("Skipped station needs an explicit reason: " + result);
                    }
                    continue;
                }
                if (!root.has("assertions") || !root.get("assertions").isJsonArray()) {
                    throw new IllegalStateException("Missing assertions: " + result);
                }
                Set<String> ids = new LinkedHashSet<>();
                int passed = 0;
                for (JsonElement element : root.getAsJsonArray("assertions")) {
                    JsonObject assertion = element.getAsJsonObject();
                    if (!assertion.has("id") || assertion.get("id").getAsString().isEmpty()
                            || !ids.add(assertion.get("id").getAsString()) || !assertion.has("status")) {
                        throw new IllegalStateException("Missing or duplicate assertion identity: " + result);
                    }
                    String value = assertion.get("status").getAsString();
                    if ("PASS".equals(value)) passed++;
                    else if (!"SKIP".equals(value)) throw new IllegalStateException("Unacceptable assertion " + assertion);
                }
                if (passed == 0) throw new IllegalStateException("No passing measurements: " + result);
                boolean manual = root.has("manualChecks") && root.getAsJsonArray("manualChecks").size() > 0;
                if (manual != "MANUAL_REQUIRED".equals(status)) {
                    throw new IllegalStateException("Manual checks/status disagree: " + result);
                }
                completed++;
            }
        }
        if (completed == 0) throw new IllegalStateException("All requested stations skipped");
    }

    private static void requireKnownStatus(String status) {
        if (!"PASS".equals(status) && !"MANUAL_REQUIRED".equals(status) && !"SKIP".equals(status)) {
            throw new IllegalStateException("Unacceptable or incomplete station status: " + status);
        }
    }

    static void archiveStationResults(File results, String phase, List<String> stations) throws Exception {
        File previous = new File(results, "previous-" + phase);
        if (!previous.mkdir()) throw new IllegalStateException("Cannot create result custody directory: " + previous);
        List<String> names = new ArrayList<>();
        names.add("all-summary.json");
        for (String station : stations) names.add(station + "-latest.json");
        for (String name : names) {
            File stale = new File(results, name);
            if (stale.isFile()) Files.move(stale.toPath(), new File(previous, name).toPath());
        }
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    static final class Arguments {
        File profile;
        boolean automated;
        boolean validateOnly;
        List<String> lifecycleStations;
        Integer stationRunSeconds;
        Integer serverPort;
        File preparedServerSeed;
        File preparedServerSeedInventory;
        String preparedServerSeedSha256;

        static Arguments parse(String[] args) {
            Arguments result = new Arguments();
            for (int i = 0; i < args.length; i++) {
                if ("--profile".equals(args[i]) && i + 1 < args.length) result.profile = new File(args[++i]);
                else if ("--automated".equals(args[i])) result.automated = true;
                else if ("--validate-only".equals(args[i])) result.validateOnly = true;
                else if ("--prepared-server-seed".equals(args[i]) && i + 1 < args.length) {
                    if (result.preparedServerSeed != null) throw new IllegalArgumentException("Duplicate prepared seed option");
                    result.preparedServerSeed = new File(args[++i]);
                }
                else if ("--prepared-server-seed-inventory".equals(args[i]) && i + 1 < args.length) {
                    if (result.preparedServerSeedInventory != null) throw new IllegalArgumentException("Duplicate inventory option");
                    result.preparedServerSeedInventory = new File(args[++i]);
                }
                else if ("--prepared-server-seed-sha256".equals(args[i]) && i + 1 < args.length) {
                    if (result.preparedServerSeedSha256 != null) throw new IllegalArgumentException("Duplicate inventory hash option");
                    result.preparedServerSeedSha256 = args[++i];
                }
                else if ("--stations".equals(args[i]) && i + 1 < args.length) {
                    result.lifecycleStations = new ArrayList<>();
                    for (String station : args[++i].split(",")) {
                        if (!station.trim().isEmpty()) result.lifecycleStations.add(station.trim());
                    }
                    if (result.lifecycleStations.isEmpty()) {
                        throw new IllegalArgumentException("--stations requires at least one station ID");
                    }
                }
                else if ("--run-seconds".equals(args[i]) && i + 1 < args.length) {
                    result.stationRunSeconds = Integer.parseInt(args[++i]);
                    if (result.stationRunSeconds < 1) {
                        throw new IllegalArgumentException("--run-seconds must be positive");
                    }
                }
                else if ("--server-port".equals(args[i]) && i + 1 < args.length) {
                    result.serverPort = Integer.parseInt(args[++i]);
                    if (result.serverPort < 1 || result.serverPort > 65535) {
                        throw new IllegalArgumentException("--server-port must be between 1 and 65535");
                    }
                }
                else throw new IllegalArgumentException("Unknown runner argument " + args[i]);
            }
            if (result.profile == null) throw new IllegalArgumentException("--profile is required");
            boolean anySeed = result.preparedServerSeed != null || result.preparedServerSeedInventory != null
                    || result.preparedServerSeedSha256 != null;
            if (anySeed && (result.preparedServerSeed == null || result.preparedServerSeedInventory == null
                    || result.preparedServerSeedSha256 == null)) {
                throw new IllegalArgumentException("Prepared seed requires path, inventory and inventory SHA-256 together");
            }
            return result;
        }
    }

    static final class Profile {
        String name;
        String mode;
        String runtimeBase = "build/works-native117";
        int serverPort = 25565;
        int startupTimeoutSeconds = 120;
        int shutdownTimeoutSeconds = 60;
        int stationRunSeconds = 5;
        String levelName = "AdvantageWorks";
        String levelSeed = "advantage-works-1.17.1-v1";
        String levelType = "advantageworkstest:test_flat";
        String generatorSettings = "";
        boolean buildWorks = true;
        int worldgenSampleRadius;
        boolean requireStableWorldgenCounts = true;
        List<String> stableWorldgenCountExclusions = new ArrayList<>();
        String java8Home;
        String java16Home;
        String java17Home;
        String gradleJavaHome;
        String recipeMode = "NORMAL";
        String sourceCommit;
        String sourceVersion;
        String minecraftVersion;
        String portStatus;
        String portBlocker;
        String qualificationStatus;
        String legacySourceProfile;
        ForgeInput forge;
        transient PreparedServerSeed preparedSeed;
        transient List<Boot> boots = new ArrayList<>();
        List<String> launchCommand = new ArrayList<>();
        List<ModInput> mods = new ArrayList<>();
        List<ConfigFixture> configFixtures = new ArrayList<>();
        List<JsonAssertion> firstProfileAssertions = new ArrayList<>();
        List<JsonAssertion> restartProfileAssertions = new ArrayList<>();
        List<JsonMutation> profileMutations = new ArrayList<>();
        List<FileAssertion> firstFileAssertions = new ArrayList<>();
        List<FileAssertion> restartFileAssertions = new ArrayList<>();
        WorldgenExpectations worldgenExpectations = new WorldgenExpectations();
        List<String> lifecycleStations = DEFAULT_LIFECYCLE_STATIONS;
    }

    private static final class ModInput {
        String id;
        String path;
        String file;
        String version;
        String sha256;
        boolean required = true;
        boolean workspaceProduced;
        boolean verifyIdentity = true;
    }

    private static final class ForgeInput {
        String version;
        String installerPath;
        String url;
        String sha256;
    }

    private static final class ConfigFixture {
        String source;
        String destination;
    }

    private static class JsonTarget {
        String section;
        String entry;
        String field;
    }

    private static final class JsonAssertion extends JsonTarget {
        String id;
        boolean present = true;
        Boolean expectedBoolean;
        String expectedString;
        Double expectedNumber;
    }

    private static final class JsonMutation extends JsonTarget {
        Boolean booleanValue;
        String stringValue;
        Double numberValue;
    }

    private static final class FileAssertion {
        String id;
        String path;
        String contains;
        int minimumOccurrences = 1;
    }

    private static final class WorldgenExpectations {
        Map<String, Long> minimumCounts = new LinkedHashMap<>();
        Map<String, Long> maximumCounts = new LinkedHashMap<>();
        boolean zeroBiomeViolations = true;
    }
}
