package zone.moddev.mc.advantageworks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native1201PackMetadataTest {
    @Test public void harnessResourcesDeclareTheNativePackFormats() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/pack.mcmeta")) {
            assertNotNull(input);
            JsonObject pack = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("pack");
            assertFalse(pack.has("pack_format"));
            assertFalse(pack.has("forge:resource_pack_format"));
            assertFalse(pack.has("forge:data_pack_format"));
            var section = net.minecraft.server.packs.metadata.pack.PackMetadataSection.forPackType(
                    net.minecraft.server.packs.PackType.SERVER_DATA).codec()
                    .parse(com.mojang.serialization.JsonOps.INSTANCE, pack).getOrThrow();
            var formats = section.supportedFormats();
            assertTrue(formats.isValueInRange(net.minecraft.server.packs.metadata.pack.PackFormat.of(121, 0)));
            assertFalse(formats.isValueInRange(net.minecraft.server.packs.metadata.pack.PackFormat.of(75, 0)));
            assertFalse(formats.isValueInRange(net.minecraft.server.packs.metadata.pack.PackFormat.of(120, 1)));
            assertFalse(formats.isValueInRange(net.minecraft.server.packs.metadata.pack.PackFormat.of(121, 1)));
        }
    }
}
