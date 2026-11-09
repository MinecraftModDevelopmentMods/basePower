package zone.moddev.mc.advantageworks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native118PackMetadataTest {
    @Test public void harnessResourcesDeclareTheNative118PackFormats() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/pack.mcmeta")) {
            assertNotNull(input);
            JsonObject pack = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("pack");
            assertTrue(pack.getAsJsonPrimitive("pack_format").isNumber());
            assertEquals(9, pack.get("pack_format").getAsInt());
            assertEquals(8, pack.get("forge:resource_pack_format").getAsInt());
            assertEquals(9, pack.get("forge:data_pack_format").getAsInt());
        }
    }
}
