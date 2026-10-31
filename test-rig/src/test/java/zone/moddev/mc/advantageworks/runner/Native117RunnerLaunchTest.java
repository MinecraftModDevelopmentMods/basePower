package zone.moddev.mc.advantageworks.runner;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Command construction only: fixtures never execute a JVM or start a server. */
public class Native117RunnerLaunchTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String LIBRARY = "libraries/native/runtime.jar";

    private WorksRunner.Profile profile() throws Exception {
        WorksRunner.Profile profile = new Gson().fromJson("{\"mode\":\"packaged\",\"minecraftVersion\":\"1.17.1\","
                + "\"forge\":{\"version\":\"1.17.1-37.1.1\"}}", WorksRunner.Profile.class);
        File javaHome = temporary.newFolder("java " + System.nanoTime());
        assertTrue(new File(javaHome, "bin").mkdir());
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        Files.write(new File(javaHome, "bin/" + executable).toPath(), new byte[] {1});
        Files.writeString(new File(javaHome, "release").toPath(),
                "JAVA_VERSION=\"16.0.2\"\nIMPLEMENTOR_VERSION=\"Temurin-16.0.2+7\"\n");
        profile.java16Home = javaHome.getAbsolutePath();
        return profile;
    }

    private Path runtime(WorksRunner.Profile profile) throws Exception {
        Path runtime = temporary.newFolder("runtime with spaces " + System.nanoTime()).toPath();
        Files.createDirectories(runtime.resolve(LIBRARY).getParent());
        Files.write(runtime.resolve(LIBRARY), new byte[] {1, 2});
        writeArgs(runtime, profile, modularArgs());
        writeLoggingJar(runtime, true);
        return runtime;
    }

    private static String modularArgs() {
        return String.join("\n", "-p " + LIBRARY, "--add-modules ALL-MODULE-PATH",
                "--add-opens java.base/java.util.jar=cpw.mods.securejarhandler",
                "--add-exports java.base/sun.security.util=cpw.mods.securejarhandler",
                "-DignoreList=runtime.jar", "-DlibraryDirectory=libraries", "-DlegacyClassPath=" + LIBRARY,
                "cpw.mods.bootstraplauncher.BootstrapLauncher", "--launchTarget forgeserver",
                "--fml.forgeVersion 37.1.1", "--fml.mcVersion 1.17.1", "--fml.forgeGroup net.minecraftforge",
                "--fml.mcpVersion 20210706.113038") + "\n";
    }

    private static void writeArgs(Path runtime, WorksRunner.Profile profile, String text) throws Exception {
        Path args = runtime.resolve(WorksRunner.serverLauncherPath(profile));
        Files.createDirectories(args.getParent());
        Files.writeString(args, text);
    }

    private static Path loggingJar(Path runtime) {
        return runtime.resolve("libraries/net/minecraftforge/fmlloader/1.17.1-37.1.1/fmlloader-1.17.1-37.1.1.jar");
    }

    private static void writeLoggingJar(Path runtime, boolean config) throws Exception {
        Path jar = loggingJar(runtime);
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(config ? "log4j2.xml" : "other.xml"));
            output.write("<Configuration/>".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private interface Action { void run() throws Exception; }
    private static Exception rejected(Action action) throws Exception {
        try { action.run(); } catch (Exception failure) { return failure; }
        throw new AssertionError("Unsafe native launch accepted");
    }

    @Test public void usesModularArgfilePinnedJavaAndForgesOwnLoggingConfiguration() throws Exception {
        WorksRunner.Profile profile = profile();
        Path runtime = runtime(profile);
        List<String> command = WorksRunner.packagedCommand(profile, runtime.toFile());
        assertTrue(command.get(0), command.get(0).startsWith(profile.java16Home));
        assertFalse(command.contains("-jar"));
        assertTrue(command.contains("@" + WorksRunner.serverLauncherPath(profile)));
        assertTrue(command.contains("java.base/java.lang.invoke=ALL-UNNAMED"));
        String logging = "-Dlog4j.configurationFile=jar:" + loggingJar(runtime).toFile().toURI().toASCIIString() + "!/log4j2.xml";
        assertTrue(command.toString(), command.contains(logging));
        assertTrue(logging, logging.contains("%20"));
        assertTrue(command.indexOf(logging) < command.indexOf("@" + WorksRunner.serverLauncherPath(profile)));
        assertEquals("nogui", command.get(command.size() - 1));
    }

    @Test public void missingArgfileOrLoggingConfigurationCannotFallBackToALegacyLauncher() throws Exception {
        for (String missing : Arrays.asList("argfile", "logging-jar", "logging-entry")) {
            WorksRunner.Profile profile = profile();
            Path runtime = runtime(profile);
            Files.write(runtime.resolve("forge-1.17.1-37.1.1.jar"), new byte[] {9});
            if (missing.equals("argfile")) Files.delete(runtime.resolve(WorksRunner.serverLauncherPath(profile)));
            if (missing.equals("logging-jar")) Files.delete(loggingJar(runtime));
            if (missing.equals("logging-entry")) writeLoggingJar(runtime, false);
            rejected(() -> WorksRunner.packagedCommand(profile, runtime.toFile()));
        }
    }

    @Test public void customCommandsCannotBypassRuntimeOrLogCustody() throws Exception {
        WorksRunner.Profile profile = profile();
        Path runtime = runtime(profile);
        profile.launchCommand = Arrays.asList("java", "-jar", "unverified.jar");
        assertTrue(rejected(() -> WorksRunner.packagedCommand(profile, runtime.toFile())).getMessage().contains("cannot override"));
        assertTrue(rejected(() -> WorksRunner.requireInputPins(profile)).getMessage().contains("cannot override"));
    }

    @Test public void anotherJavaPatchBuildOrMajorCannotSatisfyTheRuntimePin() throws Exception {
        for (String version : Arrays.asList("Temurin-16.0.2+6", "Temurin-16.0.1+9", "Temurin-17.0.1+12")) {
            WorksRunner.Profile profile = profile();
            Path runtime = runtime(profile);
            Files.writeString(new File(profile.java16Home, "release").toPath(),
                    "JAVA_VERSION=\"16.0.2\"\nIMPLEMENTOR_VERSION=\"" + version + "\"\n");
            assertTrue(rejected(() -> WorksRunner.packagedCommand(profile, runtime.toFile())).getMessage().contains("16.0.2+7"));
        }
    }

    @Test public void incompleteOrContradictoryJavaMetadataCannotSatisfyTheRuntimePin() throws Exception {
        for (String metadata : Arrays.asList("IMPLEMENTOR_VERSION=\"Temurin-16.0.2+7\"\n",
                "JAVA_VERSION=\"17.0.1\"\nIMPLEMENTOR_VERSION=\"Temurin-16.0.2+7\"\n",
                "JAVA_VERSION=\"16.0.2\"\n")) {
            WorksRunner.Profile profile = profile();
            Path runtime = runtime(profile);
            Files.writeString(new File(profile.java16Home, "release").toPath(), metadata);
            rejected(() -> WorksRunner.packagedCommand(profile, runtime.toFile()));
        }
    }

    @Test public void argfileCannotReadCodeOutsideThePreparedRuntime() throws Exception {
        for (String path : Arrays.asList("../escape.jar", "libraries/../escape.jar", "libraries//escape.jar",
                "/absolute.jar", "C:/absolute.jar", "libraries/a.jar@elsewhere")) {
            WorksRunner.Profile profile = profile();
            Path runtime = runtime(profile);
            writeArgs(runtime, profile, modularArgs().replace(LIBRARY, path));
            assertTrue(rejected(() -> WorksRunner.packagedCommand(profile, runtime.toFile())).getMessage().contains("code path"));
        }
    }

    @Test public void argfileCannotAddJvmAgentsNestedArgumentsOrDifferentLaunchTargets() throws Exception {
        for (String text : Arrays.asList(modularArgs() + "-javaagent:outside.jar\n", modularArgs() + "@outside.txt\n",
                modularArgs().replace("forgeserver", "forgeclient"), modularArgs().replace("--fml.mcVersion 1.17.1\n", ""),
                modularArgs() + "--launchTarget forgeserver\n")) {
            WorksRunner.Profile profile = profile();
            Path runtime = runtime(profile);
            writeArgs(runtime, profile, text);
            rejected(() -> WorksRunner.packagedCommand(profile, runtime.toFile()));
        }
    }

    @Test public void missingModularLibraryFailsBeforeAProcessCanStart() throws Exception {
        WorksRunner.Profile profile = profile();
        Path runtime = runtime(profile);
        Files.delete(runtime.resolve(LIBRARY));
        rejected(() -> WorksRunner.packagedCommand(profile, runtime.toFile()));
    }
}
