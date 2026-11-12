package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSources;
import net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister;
import net.minecraft.client.renderer.texture.atlas.sources.SingleFile;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native codec/source execution without a GPU; actual rendering remains a separate gate. */
public class NativeFamilyAtlasRegistrationTest {
    private static final List<String> OWNERS = List.of("basepower", "basesteam", "baseelectric");
    private static final Map<String, Path> ROOTS = Map.of(
            "basepower", Path.of("src/main/resources"),
            "basesteam", Path.of("../SteamAdvantage/SteamAdvantage/src/main/resources"),
            "baseelectric", Path.of("../ElectricAdvantage/ElectricAdvantage/src/main/resources"));
    private static final Map<String, List<Integer>> COUNTS = Map.of(
            "basepower", List.of(34, 6), "basesteam", List.of(24, 3), "baseelectric", List.of(49, 17));

    @BeforeClass public static void nativeBootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        assertEquals("26.2", SharedConstants.getCurrentVersion().name());
        var sample = JsonParser.parseString("{\"type\":\"minecraft:single\",\"resource\":\"minecraft:block/stone\"}");
        if (SpriteSources.CODEC.parse(JsonOps.INSTANCE, sample).error().isPresent()) SpriteSources.bootstrap();
        assertTrue(SpriteSources.CODEC.parse(JsonOps.INSTANCE, sample).result().isPresent());
    }

    @Test public void allSixFilesDecodeAsExactOwnedNativeSinglesWithCompatibleFrameSizes() throws Exception {
        Set<Identifier> all = new HashSet<>();
        for (String owner : OWNERS) for (String atlas : List.of("blocks", "items")) {
            List<SpriteSource> sources = validate(owner, atlas, document(owner, atlas));
            assertEquals(COUNTS.get(owner).get(atlas.equals("blocks") ? 0 : 1).intValue(), sources.size());
            for (SpriteSource source : sources) assertTrue("Duplicate cross-atlas sprite", all.add(((SingleFile) source).resourceId()));
        }
    }

    @Test public void everyModelMaterialAndManualFluidOrRendererSpriteIsPresent() throws Exception {
        Set<Identifier> registered = registrations();
        for (Path root : ROOTS.values()) try (var paths = Files.walk(root.resolve("assets"))) {
            for (Path path : paths.filter(p -> p.toString().contains("models") && p.toString().endsWith(".json")).toList()) {
                JsonObject model = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (!model.has("textures")) continue;
                JsonObject textures = model.getAsJsonObject("textures");
                TextureSlots.parseTextureMap(textures);
                for (JsonElement value : textures.asMap().values()) {
                    if (value.isJsonPrimitive() && value.getAsString().startsWith("#")) continue;
                    Identifier id = Identifier.parse(value.isJsonObject() ? value.getAsJsonObject().get("sprite").getAsString() : value.getAsString());
                    if (ROOTS.containsKey(id.getNamespace())) assertTrue("Missing model material " + id + " in " + path, registered.contains(id));
                }
            }
        }
        for (String id : List.of("basepower:blocks/crude_oil_still", "basepower:blocks/crude_oil_flow",
                "basepower:blocks/refined_oil_still", "basepower:blocks/refined_oil_flow",
                "basesteam:entity/drill_bit", "baseelectric:entity/laser_turret")) assertTrue(id, registered.contains(Identifier.parse(id)));
    }

    @Test public void effectiveNativeSourceExecutionIsIndependentOfModPackOrder() throws Exception {
        try (var resources = resources()) {
            for (String atlas : List.of("blocks", "items")) {
                Set<Identifier> expected = new HashSet<>();
                for (String owner : OWNERS) for (SpriteSource source : validate(owner, atlas, document(owner, atlas))) expected.add(((SingleFile) source).resourceId());
                for (List<String> order : List.of(OWNERS, List.of("baseelectric", "basesteam", "basepower"),
                        List.of("basesteam", "basepower", "baseelectric"), List.of("basepower", "baseelectric", "basesteam"),
                        List.of("baseelectric", "basepower", "basesteam"), List.of("basesteam", "baseelectric", "basepower"))) {
                    try (Collector output = new Collector()) {
                        for (String owner : order) for (SpriteSource source : validate(owner, atlas, document(owner, atlas))) source.run(resources, output);
                        assertEquals(expected, output.loaders.keySet());
                    }
                }
            }
        }
    }

    @Test public void nativeDirectoryCodecIgnoresInventedNamespaceAndReallyImportsForeignBeam() throws Exception {
        var unsafe = JsonParser.parseString("{\"source\":\"materials\",\"prefix\":\"materials/\",\"namespace\":\"basepower\"}");
        var directory = DirectoryLister.MAP_CODEC.codec().parse(JsonOps.INSTANCE, unsafe).getOrThrow();
        try (var resources = resources(); Collector output = new Collector()) {
            directory.run(resources, output);
            assertTrue(output.loaders.containsKey(Identifier.parse("baseelectric:materials/laser_beam")));
        }
        JsonObject mutated = document("basepower", "blocks").deepCopy();
        var source = unsafe.getAsJsonObject(); source.addProperty("type", "minecraft:directory");
        mutated.getAsJsonArray("sources").add(source);
        assertThrows(IllegalArgumentException.class, () -> validate("basepower", "blocks", mutated));
    }

    @Test public void foreignMissingTinyAndAliasedSpriteMutationsCannotPass() throws Exception {
        for (String value : List.of("baseelectric:materials/laser_beam", "basepower:blocks/definitely_missing_control")) {
            JsonObject mutated = document("basepower", "blocks").deepCopy();
            mutated.getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("resource", value);
            assertThrows(IllegalArgumentException.class, () -> validate("basepower", "blocks", mutated));
        }
        JsonObject tiny = document("baseelectric", "blocks").deepCopy();
        tiny.getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("resource", "baseelectric:materials/laser_beam");
        assertThrows(IllegalArgumentException.class, () -> validate("baseelectric", "blocks", tiny));
        JsonObject renamed = document("basepower", "blocks").deepCopy();
        renamed.getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("sprite", "basepower:renamed_control");
        assertThrows(IllegalArgumentException.class, () -> validate("basepower", "blocks", renamed));
        assertTrue(SpriteSources.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"type\":\"minecraft:unknown_control\"}")).error().isPresent());
    }

    @Test public void directBeamKeepsItsOriginalPixelsButIsNotAnAtlasSprite() throws Exception {
        var beam = ROOTS.get("baseelectric").resolve("assets/baseelectric/textures/materials/laser_beam.png");
        assertEquals("6730B89B451F837718F9D9FE6BEC99EB415F6BC9EA7106E9C7F166A6C2D7AA12",
                HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(beam))));
        assertFalse(registrations().contains(Identifier.parse("baseelectric:materials/laser_beam")));
    }

    private static JsonObject document(String owner, String atlas) throws Exception {
        return JsonParser.parseString(Files.readString(ROOTS.get(owner).resolve("assets/minecraft/atlases/" + atlas + ".json"))).getAsJsonObject();
    }

    private static List<SpriteSource> validate(String owner, String atlas, JsonObject document) throws Exception {
        check(document.keySet().equals(Set.of("sources")), "Unexpected atlas fields");
        List<SpriteSource> sources = SpriteSources.FILE_CODEC.parse(JsonOps.INSTANCE, document).getOrThrow();
        Set<Identifier> seen = new HashSet<>();
        for (int index = 0; index < sources.size(); index++) {
            var raw = document.getAsJsonArray("sources").get(index).getAsJsonObject();
            check(raw.keySet().equals(Set.of("type", "resource")) && raw.get("type").getAsString().equals("minecraft:single"), "Only explicit native singles are allowed");
            check(sources.get(index) instanceof SingleFile, "Global source type is forbidden");
            SingleFile single = (SingleFile) sources.get(index);
            Identifier id = single.resourceId();
            check(id.getNamespace().equals(owner) && single.spriteId().isEmpty() && seen.add(id), "Foreign/renamed/duplicate sprite");
            Path texture = ROOTS.get(owner).resolve("assets/" + owner + "/textures/" + id.getPath() + ".png");
            check(Files.isRegularFile(texture), "Missing sprite " + id);
            var image = ImageIO.read(texture.toFile());
            check(image != null, "Invalid PNG " + id);
            FrameSize size = new FrameSize(image.getWidth(), image.getHeight());
            Path metadata = Path.of(texture + ".mcmeta");
            if (Files.isRegularFile(metadata)) {
                JsonObject data = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
                if (data.has("animation")) size = AnimationMetadataSection.CODEC.parse(JsonOps.INSTANCE, data.get("animation")).getOrThrow().calculateFrameSize(image.getWidth(), image.getHeight());
            }
            check(!atlas.equals("blocks") || Math.max(size.width(), size.height()) >= 16, "Default native mip4 incompatible with " + id);
        }
        return sources;
    }

    private static Set<Identifier> registrations() throws Exception {
        Set<Identifier> result = new HashSet<>();
        for (String owner : OWNERS) for (String atlas : List.of("blocks", "items")) for (SpriteSource source : validate(owner, atlas, document(owner, atlas))) result.add(((SingleFile) source).resourceId());
        return result;
    }

    private static MultiPackResourceManager resources() {
        List<PackResources> packs = new ArrayList<>();
        for (String owner : OWNERS) packs.add(new PathPackResources(new PackLocationInfo(owner, Component.literal(owner), PackSource.BUILT_IN, Optional.empty()), ROOTS.get(owner)));
        return new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static final class Collector implements SpriteSource.Output, AutoCloseable {
        private final Map<Identifier, SpriteSource.DiscardableLoader> loaders = new LinkedHashMap<>();
        @Override public void add(Identifier id, SpriteSource.DiscardableLoader loader) {
            check(loaders.putIfAbsent(id, loader) == null, "Duplicate effective sprite " + id);
        }
        @Override public void removeAll(Predicate<Identifier> predicate) { loaders.keySet().removeIf(predicate); }
        @Override public void close() { loaders.values().forEach(SpriteSource.DiscardableLoader::discard); }
    }
}
