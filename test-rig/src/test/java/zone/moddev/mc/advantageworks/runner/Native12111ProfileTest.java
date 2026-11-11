package zone.moddev.mc.advantageworks.runner;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class Native12111ProfileTest {
    private static final Gson GSON = new Gson();
    private static final String FORGE = "1.21.11-61.1.0";
    private static final String SHIM = "forge-" + FORGE + "-shim.jar";
    private static final String LIBRARY = "libraries/native/runtime.jar";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private interface Action { void run() throws Exception; }

    private static Exception rejected(Action action) throws Exception {
        try { action.run(); }
        catch (Exception failure) { return failure; }
        throw new AssertionError("Unsafe Forge 61 input was accepted");
    }

    private static String hash(Path path) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) {
            result.append(String.format(Locale.ROOT, "%02X", value & 255));
        }
        return result.toString();
    }

    private File javaHome(String version) throws Exception {
        File home = temporary.newFolder("java with spaces " + System.nanoTime());
        Files.createDirectory(home.toPath().resolve("bin"));
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Files.write(home.toPath().resolve("bin/" + executable), new byte[] {1});
        Files.writeString(home.toPath().resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        return home;
    }

    private JsonObject profile() throws Exception {
        Path rig = temporary.newFolder("rig " + System.nanoTime()).toPath();
        Files.createDirectory(rig.resolve("profiles"));
        Files.createDirectories(rig.resolve("src/main/resources/assets/advantageworkstest"));
        Files.write(rig.resolve("build.gradle"), new byte[0]);
        JsonObject profile = new JsonObject();
        profile.addProperty("name", "native12111-test");
        profile.addProperty("mode", "packaged");
        profile.addProperty("minecraftVersion", "1.21.11");
        profile.addProperty("runtimeBase", "build/works-native12111");
        profile.addProperty("portStatus", "PORTED_UNQUALIFIED");
        profile.addProperty("qualificationStatus", "EXPLORATORY_UNQUALIFIED");
        profile.addProperty("java21Home", javaHome("21.0.7").getAbsolutePath());
        JsonObject forge = new JsonObject();
        forge.addProperty("version", FORGE);
        profile.add("forge", forge);
        JsonArray mods = new JsonArray();
        profile.add("mods", mods);
        String powerHash = null;
        for (String id : Arrays.asList("basepower", "advantageworkstest", "orespawn")) {
            String version = id.equals("basepower") ? "2.4.15.121111"
                    : id.equals("advantageworkstest") ? "1.1.5.121111" : "4.1.0.121111";
            Path jar = rig.resolve(id + "-" + version + ".jar");
            writeMod(jar, id, version, powerHash);
            JsonObject mod = new JsonObject();
            mod.addProperty("id", id);
            mod.addProperty("version", version);
            mod.addProperty("path", jar.toString());
            mod.addProperty("file", jar.getFileName().toString());
            mod.addProperty("sha256", hash(jar));
            mods.add(mod);
            if (id.equals("basepower")) powerHash = hash(jar);
        }
        return profile;
    }

    private static void writeMod(Path path, String id, String version, String powerHash) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        if (powerHash != null) {
            attributes.putValue("Built-On", FORGE);
            attributes.putValue("Power-Input-Status", "EXPLORATORY_UNQUALIFIED");
            attributes.putValue("Power-Input-SHA256", powerHash);
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            output.write(("[[mods]]\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static Path rig(JsonObject profile) {
        return Path.of(profile.getAsJsonArray("mods").get(0).getAsJsonObject().get("path").getAsString()).getParent();
    }

    private static File save(JsonObject profile) throws Exception {
        Path path = rig(profile).resolve("profiles/native12111.json");
        Files.writeString(path, profile.toString());
        return path.toFile();
    }

    private static void validate(JsonObject profile) throws Exception {
        // Exercise the entry point in-process without changing its inherited environment.
        WorksRunner.main(new String[] {"--profile", save(profile).getAbsolutePath(), "--validate-only"});
    }

    private static WorksRunner.Profile parsed(JsonObject profile) {
        return GSON.fromJson(profile, WorksRunner.Profile.class);
    }

    @Test public void validateOnlyAcceptsMaterializedPythonProfileWithoutOpeningAWorld() throws Exception {
        JsonObject profile = profile();
        profile.addProperty("levelType", "minecraft:flat");
        validate(profile);
        assertFalse(Files.exists(rig(profile).resolve("build")));
        assertFalse(Files.exists(rig(profile).resolve("AdvantageWorks")));
    }

    @Test public void native61PinsEmptyServerPauseOffEvenWhenPropertiesAlreadyExist() throws Exception {
        WorksRunner.Profile profile = parsed(profile());
        Path runtime = temporary.newFolder().toPath();
        Path propertiesFile = runtime.resolve("server.properties");
        Files.writeString(propertiesFile, "pause-when-empty-seconds=60\n");
        WorksRunner.writeRuntimeFiles(runtime.toFile(), profile);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(propertiesFile)) { properties.load(input); }
        assertEquals("-1", properties.getProperty("pause-when-empty-seconds"));
        assertEquals(1L, Files.readAllLines(propertiesFile).stream()
                .filter(line -> line.startsWith("pause-when-empty-seconds=")).count());
        assertFalse(Files.exists(runtime.resolve(profile.levelName)));
    }

    @Test public void olderVersionsDoNotAddAnEmptyServerPauseOverride() throws Exception {
        WorksRunner.Profile profile = parsed(profile());
        for (String minecraft : Arrays.asList("1.10.2", "1.12.2", "1.13.2", "1.14.4", "1.15.2", "1.16.5",
                "1.17.1", "1.18.2", "1.19.4", "1.20.1", "1.20.6", "1.21.1")) {
            profile.minecraftVersion = minecraft;
            Path runtime = temporary.newFolder().toPath();
            WorksRunner.writeRuntimeFiles(runtime.toFile(), profile);
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(runtime.resolve("server.properties"))) { properties.load(input); }
            assertFalse(minecraft, properties.containsKey("pause-when-empty-seconds"));
            assertFalse(minecraft, Files.readString(runtime.resolve("server.properties")).contains("pause-when-empty-seconds"));
        }
    }

    @Test public void rejectsUnportedQualifiedDevelopmentAndWrongForgeProfiles() throws Exception {
        for (String field : Arrays.asList("portStatus", "qualificationStatus", "mode", "forge", "runtimeBase")) {
            JsonObject profile = profile();
            if (field.equals("forge")) profile.getAsJsonObject("forge").addProperty("version", "1.21.11-61.1.1");
            else profile.addProperty(field, field.equals("mode") ? "development" : "PASS");
            String message = rejected(() -> validate(profile)).getMessage();
            assertTrue(message, message.contains(field.equals("forge") ? "must select Forge"
                    : field.equals("portStatus") ? "not ported"
                    : field.equals("runtimeBase") ? "isolated build/works-native12111" : "ordinary pinned packaged artifacts"));
            assertFalse(Files.exists(rig(profile).resolve("build")));
        }
    }

    @Test public void strictOrdinaryInputsRejectMissingDuplicateAndBypassedIdentities() throws Exception {
        for (String mutation : Arrays.asList("duplicate", "missing", "target", "file", "optional", "identity", "workspace")) {
            JsonObject profile = profile();
            JsonArray mods = profile.getAsJsonArray("mods");
            JsonObject mod = mods.get(2).getAsJsonObject();
            if (mutation.equals("duplicate")) mods.add(GSON.fromJson(mod, JsonObject.class));
            if (mutation.equals("missing")) mods.remove(2);
            if (mutation.equals("target")) mod.addProperty("version", "4.1.0.121011");
            if (mutation.equals("file")) mod.addProperty("file", "OreSpawn-4.1.0.121111-deobf.jar");
            if (mutation.equals("optional")) mod.addProperty("required", false);
            if (mutation.equals("identity")) mod.addProperty("verifyIdentity", false);
            if (mutation.equals("workspace")) mod.addProperty("workspaceProduced", true);
            String message = rejected(() -> validate(profile)).getMessage();
            assertTrue(message, message.contains(mutation.equals("missing")
                    ? "must be explicitly pinned" : "Invalid ordinary native mod input"));
            assertFalse(Files.exists(rig(profile).resolve("build")));
        }
    }

    @Test public void rejectsUnpinnedChangedAndMisidentifiedModBytes() throws Exception {
        for (String mutation : Arrays.asList("pin", "bytes", "identity", "compile-pin")) {
            JsonObject profile = profile();
            JsonObject mod = profile.getAsJsonArray("mods").get(mutation.equals("compile-pin") ? 1 : 2).getAsJsonObject();
            Path jar = Path.of(mod.get("path").getAsString());
            if (mutation.equals("pin")) mod.remove("sha256");
            if (mutation.equals("bytes")) Files.write(jar, new byte[] {1});
            if (mutation.equals("identity") || mutation.equals("compile-pin")) {
                writeMod(jar, mutation.equals("identity") ? "not_orespawn" : "advantageworkstest",
                        mod.get("version").getAsString(), mutation.equals("compile-pin") ? "0".repeat(64) : null);
                mod.addProperty("sha256", hash(jar));
            }
            String message = rejected(() -> validate(profile)).getMessage();
            assertTrue(message, message.contains(mutation.equals("pin") ? "SHA-256 pin"
                    : mutation.equals("bytes") ? "SHA-256 mismatch"
                    : mutation.equals("identity") ? "Mod identity orespawn not found" : "same ordinary jar"));
            assertFalse(Files.exists(rig(profile).resolve("build")));
        }
    }

    @Test public void requiresJava21AndCannotOverrideThePinnedCommand() throws Exception {
        for (String version : Arrays.asList("8.0.502", "17.0.1", "25.0.1")) {
            JsonObject profile = profile();
            profile.addProperty("java21Home", javaHome(version).getAbsolutePath());
            assertTrue(rejected(() -> validate(profile)).getMessage().contains("Java 21"));
        }
        JsonObject profile = profile();
        JsonArray command = new JsonArray();
        command.add("java");
        command.add("-javaagent:unverified.jar");
        profile.add("launchCommand", command);
        assertTrue(rejected(() -> validate(profile)).getMessage().contains("cannot override"));
    }

    @Test public void usesForge61ModularLauncherOnBothPlatforms() throws Exception {
        WorksRunner.Profile profile = parsed(profile());
        String os = System.getProperty("os.name");
        try {
            for (String platform : Arrays.asList("Windows 11", "Linux")) {
                System.setProperty("os.name", platform);
                assertEquals("libraries/net/minecraftforge/forge/" + FORGE + "/"
                        + (platform.startsWith("Windows") ? "win_args.txt" : "unix_args.txt"),
                        WorksRunner.serverLauncherPath(profile));
            }
        } finally { System.setProperty("os.name", os); }
    }

    @Test public void rejectsInheritedJvmAgentsAndNestedArgumentsOnlyForForge61() throws Exception {
        WorksRunner.requireNative12111Environment(Map.of());
        for (String variable : Arrays.asList("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            for (String options : Arrays.asList("-javaagent:outside.jar", "-agentpath:outside.dll", "@outside.args")) {
                String message = rejected(() -> WorksRunner.requireNative12111Environment(Map.of(variable, options))).getMessage();
                assertTrue(message, message.contains(variable));
            }
        }
    }

    @Test public void rejectsInheritedProcessorLimitsAndOtherJvmOverrides() throws Exception {
        for (String variable : Arrays.asList("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            for (String options : Arrays.asList("-XX:ActiveProcessorCount=4", "-XX:ActiveProcessorCount=4 -javaagent:outside.jar",
                    "-XX:ActiveProcessorCount=4 @outside.args", "-XX:ActiveProcessorCount=4\n-agentlib:outside",
                    "-XX:ActiveProcessorCount=4 -Dunexpected=1", "-XX:ActiveProcessorCount=0")) {
                assertTrue(rejected(() -> WorksRunner.requireNative12111Environment(Map.of(variable, options)))
                        .getMessage().contains(variable));
            }
        }
    }

    @Test public void oldShimProfileReadersAndLaunchersKeepTheirOriginalTargets() throws Exception {
        for (String minecraft : Arrays.asList("1.20.6", "1.21.1")) {
            JsonObject profile = profile();
            String suffix = minecraft.equals("1.20.6") ? ".120061" : ".121011";
            String forge = minecraft.equals("1.20.6") ? "1.20.6-50.2.0" : "1.21.1-52.1.0";
            profile.addProperty("minecraftVersion", minecraft);
            profile.getAsJsonObject("forge").addProperty("version", forge);
            for (var element : profile.getAsJsonArray("mods")) {
                JsonObject mod = element.getAsJsonObject();
                for (String field : Arrays.asList("version", "file")) {
                    mod.addProperty(field, mod.get(field).getAsString().replace(".121111", suffix));
                }
            }
            File file = save(profile);
            WorksRunner.Profile legacy = minecraft.equals("1.20.6")
                    ? WorksRunner.readNative1206Profile(file) : WorksRunner.readRunnerProfile(file);
            assertEquals(minecraft, legacy.minecraftVersion);
            assertTrue(WorksRunner.serverLauncherPath(legacy).startsWith("libraries/net/minecraftforge/forge/" + forge + "/"));
            assertTrue(rejected(() -> WorksRunner.readNative12111Profile(file)).getMessage().contains("Historical source profile"));
        }
    }

    private Path seed(JsonObject profile) throws Exception {
        Path seed = temporary.newFolder("seed " + System.nanoTime()).toPath();
        String launcher = WorksRunner.serverLauncherPath(parsed(profile));
        Files.createDirectories(seed.resolve(launcher).getParent());
        Files.createDirectories(seed.resolve(LIBRARY).getParent());
        Files.writeString(seed.resolve(launcher), "-Djava.net.preferIPv6Addresses=system -jar " + SHIM + "\n");
        Files.write(seed.resolve(SHIM), new byte[] {3, 4});
        Files.write(seed.resolve(LIBRARY), new byte[] {5, 6});
        return seed;
    }

    private Path inventory(Path seed, JsonObject profile, String forge) throws Exception {
        JsonObject inventory = new JsonObject();
        JsonObject files = new JsonObject();
        inventory.add("files", files);
        for (String name : Arrays.asList(SHIM, LIBRARY, WorksRunner.serverLauncherPath(parsed(profile)))) {
            JsonObject row = new JsonObject();
            row.addProperty("sha256", hash(seed.resolve(name)));
            row.addProperty("size", Files.size(seed.resolve(name)));
            files.add(name, row);
        }
        if (forge != null) inventory.addProperty("forge", forge);
        Path path = temporary.newFile().toPath();
        Files.writeString(path, inventory.toString());
        return path;
    }

    private WorksRunner.PreparedServerSeed load(Path seed, Path inventory, JsonObject profile) throws Exception {
        return WorksRunner.preparedServerSeed(temporary.getRoot(), parsed(profile), WorksRunner.Arguments.parse(
                new String[] {"--profile", "p", "--prepared-server-seed", seed.toString(),
                        "--prepared-server-seed-inventory", inventory.toString(), "--prepared-server-seed-sha256", hash(inventory)}));
    }

    @Test public void stagesSealedForge61CodeAndAllowsOnlyTheOfficialInstallerScaffolding() throws Exception {
        JsonObject profile = profile();
        Path seed = seed(profile);
        Path inventory = inventory(seed, profile, FORGE);
        for (String name : Arrays.asList("README.txt", "run.bat", "run.sh", "user_jvm_args.txt",
                "forge-" + FORGE + "-installer.jar.log")) Files.writeString(seed.resolve(name), "installer scaffolding");
        WorksRunner.PreparedServerSeed prepared = load(seed, inventory, profile);
        Path runtime = temporary.newFolder().toPath();
        prepared.stage(runtime.toFile());
        assertArrayEquals(Files.readAllBytes(seed.resolve(SHIM)), Files.readAllBytes(runtime.resolve(SHIM)));
        assertArrayEquals(Files.readAllBytes(inventory),
                Files.readAllBytes(runtime.resolve(WorksRunner.PreparedServerSeed.INVENTORY)));
        assertFalse(Files.exists(runtime.resolve("run.bat")));
        prepared.verifySource();
        Files.write(runtime.resolve(LIBRARY), new byte[] {9});
        rejected(() -> prepared.verifyTree(runtime, false));
        Files.write(seed.resolve(LIBRARY), new byte[] {9});
        rejected(prepared::verifySource);
    }

    @Test public void rejectsMissingWrongOrDuplicateForgeInventoryMetadataAndExtraCode() throws Exception {
        for (String forge : Arrays.asList(null, "1.21.1-52.1.0")) {
            JsonObject profile = profile();
            Path seed = seed(profile), inventory = inventory(seed, profile, forge);
            rejected(() -> load(seed, inventory, profile));
        }
        JsonObject profile = profile();
        Path seed = seed(profile), duplicateInventory = inventory(seed, profile, FORGE);
        Files.writeString(duplicateInventory, Files.readString(duplicateInventory).replace("\"forge\":", "\"forge\":\"" + FORGE + "\",\"forge\":"));
        rejected(() -> load(seed, duplicateInventory, profile));
        Path receipt = inventory(seed, profile, FORGE);
        Files.write(seed.resolve("unlisted.jar"), new byte[] {1});
        rejected(() -> load(seed, receipt, profile));
    }

    @Test public void rejectsAgentNestedArgfileWrongShimAndModifiedOfficialShimBytes() throws Exception {
        JsonObject profile = profile();
        for (String command : Arrays.asList("-Djava.net.preferIPv6Addresses=system -jar " + SHIM,
                "-javaagent:outside.jar -jar " + SHIM, "@outside.txt", "-jar forge-1.21.1-52.1.0-shim.jar")) {
            Path runtime = seed(profile);
            Files.writeString(runtime.resolve(WorksRunner.serverLauncherPath(parsed(profile))), command);
            rejected(() -> WorksRunner.requireModularLauncher(runtime.toFile(), WorksRunner.serverLauncherPath(parsed(profile))));
        }
    }

    @Test public void installerFallbackRetainsExactHashCustodyWithoutStartingAProcess() throws Exception {
        JsonObject profile = profile();
        Path installer = rig(profile).resolve("forge-" + FORGE + "-installer.jar");
        Files.write(installer, new byte[] {7});
        JsonObject forge = profile.getAsJsonObject("forge");
        forge.addProperty("installerPath", installer.toString());
        forge.addProperty("sha256", hash(installer));
        Path runtime = seed(profile);
        WorksRunner.preparePackagedServer(rig(profile).toFile(), runtime.toFile(), parsed(profile));
        Files.write(installer, new byte[] {8});
        assertTrue(rejected(() -> WorksRunner.preparePackagedServer(rig(profile).toFile(), runtime.toFile(), parsed(profile)))
                .getMessage().contains("installer SHA-256 mismatch"));
    }
}
