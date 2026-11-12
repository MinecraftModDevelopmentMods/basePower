package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import org.junit.Test;
import zone.moddev.mc.basepower.testing.NativePackResourceProof;
import static org.junit.Assert.*;

public class NativePackFormatContractTest {
    @org.junit.BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void nativeClientAndServerMetadataAcceptTheSameCombinedPack() throws Exception {
        var proof = NativePackResourceProof.read();
        assertEquals(0, proof.getAsJsonArray("warningsAndErrors").size());
        var clientProof = proof.getAsJsonObject("CLIENT_RESOURCES");
        var serverProof = proof.getAsJsonObject("SERVER_DATA");
        assertEquals("mod_resources", clientProof.get("id").getAsString());
        assertEquals("net.minecraftforge.resource.DelegatingPackResources", clientProof.get("resourcesClass").getAsString());
        assertEquals("mod:basepower", serverProof.get("id").getAsString());
        assertEquals("net.minecraft.server.packs.PathPackResources", serverProof.get("resourcesClass").getAsString());
        assertEquals("COMPATIBLE", clientProof.get("currentCompatibility").getAsString());
        assertEquals("COMPATIBLE", serverProof.get("currentCompatibility").getAsString());
        assertTrue(clientProof.get("resourcesVerified").getAsInt() > 0);
        assertTrue(serverProof.get("resourcesVerified").getAsInt() > 0);
        var pack = JsonParser.parseString(Files.readString(Path.of("src/main/resources/pack.mcmeta")))
                .getAsJsonObject().getAsJsonObject("pack");
        assertFalse(pack.has("pack_format"));
        assertFalse(pack.has("supported_formats"));
        var client = PackMetadataSection.forPackType(PackType.CLIENT_RESOURCES).codec()
                .parse(JsonOps.INSTANCE, clientProof.get("nativeMetadata")).getOrThrow();
        var server = PackMetadataSection.forPackType(PackType.SERVER_DATA).codec()
                .parse(JsonOps.INSTANCE, serverProof.get("nativeMetadata")).getOrThrow();
        assertEquals(server, PackMetadataSection.forPackType(PackType.SERVER_DATA).codec()
                .parse(JsonOps.INSTANCE, pack).getOrThrow());
        assertTrue(client.supportedFormats().isValueInRange(PackFormat.of(
                SharedConstants.RESOURCE_PACK_FORMAT_MAJOR, SharedConstants.RESOURCE_PACK_FORMAT_MINOR)));
        assertTrue(server.supportedFormats().isValueInRange(PackFormat.of(
                SharedConstants.DATA_PACK_FORMAT_MAJOR, SharedConstants.DATA_PACK_FORMAT_MINOR)));
        assertEquals(PackFormat.of(84, 0).minorRange(), client.supportedFormats());
        assertEquals(PackFormat.of(101, 1), server.supportedFormats().minInclusive());
        assertEquals(PackFormat.of(101, 1), server.supportedFormats().maxInclusive());
        assertFalse(client.supportedFormats().isValueInRange(PackFormat.of(83)));
        assertFalse(client.supportedFormats().isValueInRange(PackFormat.of(85)));
        assertFalse(server.supportedFormats().isValueInRange(PackFormat.of(101, 0)));
        assertFalse(server.supportedFormats().isValueInRange(PackFormat.of(101, 2)));
        assertFalse(server.supportedFormats().isValueInRange(PackFormat.of(102)));
        assertFalse(client.supportedFormats().isValueInRange(PackFormat.of(94, 1)));
        assertFalse(server.supportedFormats().isValueInRange(PackFormat.of(75, 0)));
    }
}
