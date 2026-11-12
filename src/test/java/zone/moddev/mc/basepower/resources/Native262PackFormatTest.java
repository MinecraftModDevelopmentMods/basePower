package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native262PackFormatTest {
    @BeforeClass public static void detectActualEngine() {
        SharedConstants.tryDetectVersion();
    }

    @Test public void actualEngineDeclaresTheExactTargetFormats() {
        var version = SharedConstants.getCurrentVersion();
        assertEquals("26.2", version.name());
        assertEquals(4903, version.dataVersion().version());
        assertEquals(PackFormat.of(88, 0), version.packVersion(PackType.CLIENT_RESOURCES));
        assertEquals(PackFormat.of(107, 1), version.packVersion(PackType.SERVER_DATA));
        assertEquals(25, Runtime.version().feature());
    }

    @Test public void rootMetadataUsesExactlyTheNativeDataVersion() throws Exception {
        JsonObject metadata = JsonParser.parseString(Files.readString(Path.of("src/main/resources/pack.mcmeta")))
                .getAsJsonObject().getAsJsonObject("pack");
        var range = PackFormat.packCodec(PackType.SERVER_DATA).codec().parse(JsonOps.INSTANCE, metadata).getOrThrow();
        assertEquals(PackFormat.of(107, 1), range.minInclusive());
        assertEquals(PackFormat.of(107, 1), range.maxInclusive());
        assertFalse(metadata.has("pack_format"));
        assertFalse(metadata.has("supported_formats"));
        assertTrue(range.isValueInRange(SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA)));
        assertFalse(range.isValueInRange(SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES)));
    }

    @Test public void nativeCodecRejectsAnInvertedRange() {
        var malformed = JsonParser.parseString("{\"min_format\":[107,2],\"max_format\":[107,1]}");
        assertTrue(PackFormat.packCodec(PackType.SERVER_DATA).codec().parse(JsonOps.INSTANCE, malformed).error().isPresent());
    }

    @Test public void serverOnlyRangeCannotQualifyTheClientOrOlderTargets() {
        var dataOnly = JsonParser.parseString("{\"min_format\":[107,1],\"max_format\":[107,1]}");
        var range = PackFormat.packCodec(PackType.CLIENT_RESOURCES).codec().parse(JsonOps.INSTANCE, dataOnly).getOrThrow();
        assertFalse(range.isValueInRange(PackFormat.of(88, 0)));
        assertFalse(range.isValueInRange(PackFormat.of(75, 0)));
        assertFalse(range.isValueInRange(PackFormat.of(94, 1)));
        assertFalse(range.isValueInRange(PackFormat.of(107, 0)));
        assertFalse(range.isValueInRange(PackFormat.of(107, 2)));
        assertTrue(range.isValueInRange(PackFormat.of(107, 1)));
    }
}
