package zone.moddev.mc.advantageworks.runner;

import com.google.gson.JsonParser;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class Native1201ProfileTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void flatGeneratorSettingsSurviveLoadingServerProperties() throws Exception {
        var profile = WorksRunner.readNative1201Profile(
                new File("profiles/native1201/packaged-optional-power.json"));
        File runtime = temporary.newFolder();
        WorksRunner.writeRuntimeFiles(runtime, profile);

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(runtime.toPath().resolve("server.properties"))) {
            properties.load(input);
        }
        assertEquals("minecraft:flat", properties.getProperty("level-type"));
        var generator = new JsonParser().parse(properties.getProperty("generator-settings")).getAsJsonObject();
        assertEquals("minecraft:plains", generator.get("biome").getAsString());
        assertEquals(3, generator.getAsJsonArray("layers").size());
        assertEquals(0, generator.getAsJsonArray("structure_overrides").size());
    }
}
