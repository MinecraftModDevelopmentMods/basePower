package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import org.junit.Test;
import zone.moddev.mc.basepower.testing.NativePackResourceProof;
import static org.junit.Assert.*;

public class NativePackRangeContractTest {
    @Test public void combinedPackRangeAcceptsBothNativePackTypes() throws Exception {
        var proof = NativePackResourceProof.read();
        assertEquals(0, proof.getAsJsonArray("warningsAndErrors").size());
        for (PackType type : PackType.values()) {
            var side = proof.getAsJsonObject(type.name());
            JsonObject pack = side.getAsJsonObject("nativeMetadata");
            var range = PackFormat.packCodec(type).codec().parse(JsonOps.INSTANCE, pack).getOrThrow();
            assertEquals(type.toString(), type == PackType.CLIENT_RESOURCES,
                    range.isValueInRange(PackFormat.of(84, 0)));
            assertEquals(type.toString(), type == PackType.SERVER_DATA,
                    range.isValueInRange(PackFormat.of(101, 1)));
            assertFalse(range.isValueInRange(PackFormat.of(83)));
            assertFalse(range.isValueInRange(PackFormat.of(102)));
            assertFalse(range.isValueInRange(PackFormat.of(75, 0)));
            assertFalse(range.isValueInRange(PackFormat.of(94, 1)));
        }
    }

    @Test public void omissionDoesNotSynthesizeMetadataInTheNativeModPathSupplier() throws Exception {
        var root = Path.of("build/native-pack-resources").toAbsolutePath();
        Files.createDirectories(root);
        var omitted = Files.createTempDirectory(root, "missing-metadata-");
        var info = new net.minecraft.server.packs.PackLocationInfo("mod:basepower",
                net.minecraft.network.chat.Component.literal("basePower"),
                net.minecraft.server.packs.repository.PackSource.DEFAULT, java.util.Optional.empty());
        var supplier = new net.minecraft.server.packs.PathPackResources.PathResourcesSupplier(omitted);
        try (var resources = supplier.openPrimary(info)) {
            assertNull(resources.getRootResource("pack.mcmeta"));
            for (PackType type : PackType.values()) {
                assertNull(type.toString(), resources.getMetadataSection(
                        net.minecraft.server.packs.metadata.pack.PackMetadataSection.forPackType(type)));
            }
        }
    }

    @Test public void dataOnlyRangeDoesNotQualifyClientResources() {
        JsonObject dataOnly = JsonParser.parseString("{\"min_format\":[101,1],\"max_format\":[101,1]}")
                .getAsJsonObject();
        var range = PackFormat.packCodec(PackType.CLIENT_RESOURCES).codec()
                .parse(JsonOps.INSTANCE, dataOnly).getOrThrow();
        assertFalse(range.isValueInRange(PackFormat.of(84, 0)));
        assertFalse(range.isValueInRange(PackFormat.of(94, 1)));
        assertTrue(range.isValueInRange(PackFormat.of(101, 1)));
    }
}
