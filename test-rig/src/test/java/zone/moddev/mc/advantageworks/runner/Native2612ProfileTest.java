package zone.moddev.mc.advantageworks.runner;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class Native2612ProfileTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private JsonObject profile() {
        JsonObject result = new Gson().fromJson("{\"name\":\"native2612-test\",\"mode\":\"packaged\","
                + "\"minecraftVersion\":\"26.1.2\",\"runtimeBase\":\"build/works-native2612\","
                + "\"portStatus\":\"PORTED_UNQUALIFIED\",\"qualificationStatus\":\"EXPLORATORY_UNQUALIFIED\","
                + "\"forge\":{\"version\":\"26.1.2-64.1.5\"}}", JsonObject.class);
        JsonArray mods = new JsonArray();
        result.add("mods", mods);
        for (var entry : Map.of("basepower", "2.4.16.2601021", "advantageworkstest", "1.1.6.2601021",
                "basemetals", "3.1.2.2601021", "orespawn", "4.1.1.2601021").entrySet()) {
            JsonObject input = new JsonObject();
            input.addProperty("id", entry.getKey());
            input.addProperty("version", entry.getValue());
            input.addProperty("file", entry.getKey() + "-" + entry.getValue() + ".jar");
            mods.add(input);
        }
        return result;
    }

    private Path save(JsonObject profile) throws Exception {
        Path path = temporary.newFile().toPath();
        Files.writeString(path, new Gson().toJson(profile));
        return path;
    }

    @Test public void routesExplicit26ProfileWithoutCoercingHistoricalProfile() throws Exception {
        WorksRunner.Profile actual = WorksRunner.readRunnerProfile(save(profile()).toFile());
        assertEquals("26.1.2", actual.minecraftVersion);
        assertEquals("build/works-native2612", actual.runtimeBase);
        assertTrue(WorksRunner.serverLauncherPath(actual).contains("26.1.2-64.1.5"));
        var old = profile();
        old.addProperty("minecraftVersion", "1.21.11");
        assertThrows(IllegalArgumentException.class, () -> WorksRunner.readNative2612Profile(save(old).toFile()));
    }

    @Test public void selectedForgeAndIsolatedOutputCannotDrift() throws Exception {
        var wrongForge = profile();
        wrongForge.getAsJsonObject("forge").addProperty("version", "26.1.2-64.0.9");
        assertThrows(IllegalArgumentException.class, () -> WorksRunner.readNative2612Profile(save(wrongForge).toFile()));
        var oldOutput = profile();
        oldOutput.addProperty("runtimeBase", "build/works-native12111");
        assertThrows(IllegalArgumentException.class, () -> WorksRunner.readNative2612Profile(save(oldOutput).toFile()));
    }

    @Test public void missingMandatoryBaseMetalsCannotBeAPowerOnlyPass() throws Exception {
        var missing = profile();
        var mods = missing.getAsJsonArray("mods");
        for (int index = mods.size() - 1; index >= 0; index--) {
            if (mods.get(index).getAsJsonObject().get("id").getAsString().equals("basemetals")) mods.remove(index);
        }
        assertThrows(IllegalArgumentException.class, () -> WorksRunner.readNative2612Profile(save(missing).toFile()));
    }

    @Test public void inheritedJvmOptionsAndClasspathAreRejected() {
        WorksRunner.requireNative2612Environment(Map.of());
        for (String key : new String[] {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"}) {
            assertThrows(key, IllegalArgumentException.class,
                    () -> WorksRunner.requireNative2612Environment(Map.of(key, "injected")));
        }
    }

    @Test public void alteredLoggingSourceIsNotSilentlyAdapted() throws Exception {
        Path runtime = temporary.newFolder().toPath();
        Path jar = temporary.newFile("altered-loader.jar").toPath();
        Files.write(jar, new byte[] {1, 2, 3});
        assertThrows(IllegalArgumentException.class,
                () -> WorksRunner.native2612LoggingConfiguration(runtime.toFile(), jar.toFile()));
        assertFalse(Files.exists(runtime.resolve("forge64-log4j2-plain-stacktraces.xml")));
        assertFalse(Files.exists(runtime.resolve("forge64-logging-custody.json")));
    }
}
