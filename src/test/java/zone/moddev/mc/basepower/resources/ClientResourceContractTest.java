package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class ClientResourceContractTest {
    private static final Path ROOT = Paths.get("src/main/resources");
    private static final Path ASSETS = ROOT.resolve("assets/basepower");

    @Test public void blockstatesResolveLiteralModelPaths() throws IOException {
        for (Path file : jsonFiles(ASSETS.resolve("blockstates"))) {
            verifyModelReferences(read(file), file);
        }
    }

    @Test public void blockstatesDoNotUseRemovedLegacySelectors() throws IOException {
        for (Path file : jsonFiles(ASSETS.resolve("blockstates"))) {
            JsonObject state = read(file);
            assertFalse(file + " uses the removed Forge blockstate format", state.has("forge_marker"));
            if (state.has("variants")) {
                assertFalse(file + " uses the removed normal selector", state.getAsJsonObject("variants").has("normal"));
                assertFalse(file + " uses the removed fluid selector", state.getAsJsonObject("variants").has("fluid"));
            }
        }
    }

    @Test public void modelParentsAndTexturesExistInActualResourcePacks() throws IOException {
        for (Path file : jsonFiles(ASSETS.resolve("models"))) {
            JsonObject model = read(file);
            if (model.has("parent")) verifyReference(model.get("parent").getAsString(), "models", ".json", file);
            if (model.has("textures")) {
                for (Map.Entry<String, JsonElement> texture : model.getAsJsonObject("textures").entrySet()) {
                    verifyReference(texture.getValue().getAsString(), "textures", ".png", file);
                }
            }
        }
    }

    @Test public void oilBucketsAndHiddenFluidItemsHaveModels() throws IOException {
        for (String id : new String[] {"crude_oil", "refined_oil", "crude_oil_bucket", "refined_oil_bucket"}) {
            assertTrue("Missing item model for " + id, Files.isRegularFile(ASSETS.resolve("models/item/" + id + ".json")));
        }
        List<Path> models = jsonFiles(ASSETS.resolve("models/item"));
        List<Path> definitions = jsonFiles(ASSETS.resolve("items"));
        assertEquals("Every preserved item model needs a native item definition", 35, models.size());
        assertEquals("Native item definitions must cover aliases and hidden items too", models.size(), definitions.size());
        for (Path model : models) {
            String name = model.getFileName().toString();
            Path file = ASSETS.resolve("items").resolve(name);
            JsonObject definition = read(file);
            assertEquals(file + " must retain the native model node", 1, definition.size());
            JsonObject node = definition.getAsJsonObject("model");
            assertEquals(file + " must not redirect or conditionally hide the old model", 2, node.size());
            assertEquals(file.toString(), "minecraft:model", node.get("type").getAsString());
            assertEquals(file.toString(), "basepower:item/" + name.replace(".json", ""), node.get("model").getAsString());
            verifyReference(node.get("model").getAsString(), "models", ".json", file);
        }
        JsonObject atlas = read(ROOT.resolve("assets/minecraft/atlases/items.json"));
        assertEquals("The item atlas must not shadow block or material sprites", 1,
                atlas.getAsJsonArray("sources").size());
        JsonObject source = atlas.getAsJsonArray("sources").get(0).getAsJsonObject();
        assertEquals("directory", source.get("type").getAsString());
        assertEquals("items", source.get("source").getAsString());
        assertEquals("items/", source.get("prefix").getAsString());
        String build = Files.readString(Paths.get("build.gradle"), StandardCharsets.UTF_8);
        assertTrue("Combined Eclipse defaults must select the native 1.21.11 profile",
                build.contains("'test-rig/profiles/native12111/dev-core.json'"));
        assertFalse("A CLI override must not hide a stale 1.21.1 default",
                build.contains("'test-rig/profiles/native1211/dev-core.json'"));
        JsonObject profile = read(Paths.get("test-rig/profiles/native12111/dev-core.json"));
        assertEquals("development", profile.get("mode").getAsString());
        assertEquals("1.21.11", profile.get("minecraftVersion").getAsString());
        assertEquals("61.1.0", profile.get("forgeVersion").getAsString());
        java.util.Properties pins = new java.util.Properties();
        try (Reader reader = Files.newBufferedReader(Paths.get("gradle.properties"), StandardCharsets.UTF_8)) {
            pins.load(reader);
        }
        assertEquals("The default development profile must pin the current Power player bytes",
                pins.getProperty("release_player_sha256"), profile.get("powerDeobfSha256").getAsString());
        var expansions = profile.getAsJsonArray("mods").asList().stream().map(JsonElement::getAsJsonObject).toList();
        assertEquals(java.util.Set.of("basesteam", "baseelectric"),
                expansions.stream().map(mod -> mod.get("id").getAsString()).collect(Collectors.toSet()));
        assertEquals("No duplicate or test-only runtime candidate in the production profile", 2, expansions.size());
        for (JsonObject mod : expansions) {
            assertTrue("Missing exact expansion checksum", mod.get("sha256").getAsString().matches("[A-Fa-f0-9]{64}"));
            assertEquals(pins.getProperty(mod.get("id").getAsString() + "_version"), mod.get("version").getAsString());
        }
    }

    @Test public void everyLocaleRetainsLegacyNamesAndProvidesModernAliases() throws IOException {
        for (Path file : jsonFiles(ASSETS.resolve("lang"))) {
            JsonObject locale = read(file);
            for (Map.Entry<String, JsonElement> entry : locale.entrySet()) {
                String key = entry.getKey();
                if ((key.startsWith("tile.basepower.") || key.startsWith("item.basepower."))
                        && key.endsWith(".name")) {
                    String modern = key.substring(0, key.length() - ".name".length());
                    if (modern.startsWith("tile.")) modern = "block." + modern.substring("tile.".length());
                    assertEquals(file + " missing modern alias " + modern, entry.getValue(), locale.get(modern));
                }
            }
        }
    }

    @Test public void englishCoversEveryBlockAndOilBucket() throws IOException {
        JsonObject locale = read(ASSETS.resolve("lang/en_us.json"));
        for (Path file : jsonFiles(ASSETS.resolve("blockstates"))) {
            String id = file.getFileName().toString().replace(".json", "");
            assertTrue("Missing block name: " + id, locale.has("block.basepower." + id));
        }
        for (String id : new String[] {"crude_oil", "refined_oil"}) {
            assertEquals(locale.get("item.bucket." + id), locale.get("item.basepower." + id + "_bucket"));
        }
    }

    private static void verifyModelReferences(JsonElement node, Path file) {
        if (node.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("model")) verifyReference(entry.getValue().getAsString(), "models", ".json", file);
                else verifyModelReferences(entry.getValue(), file);
            }
        } else if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) verifyModelReferences(child, file);
        }
    }

    private static void verifyReference(String reference, String directory, String suffix, Path file) {
        if (reference.startsWith("#")) return;
        String[] parts = reference.split(":", 2);
        String namespace = parts.length == 2 ? parts[0] : "minecraft";
        String path = parts.length == 2 ? parts[1] : parts[0];
        if (directory.equals("models") && namespace.equals("minecraft") && path.startsWith("builtin/")) return;
        String resource = "assets/" + namespace + "/" + directory + "/" + path + suffix;
        boolean found = namespace.equals("basepower") ? Files.isRegularFile(ROOT.resolve(resource))
                : ClientResourceContractTest.class.getClassLoader().getResource(resource) != null;
        assertTrue(file + " references missing resource " + resource, found);
    }

    private static JsonObject read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static List<Path> jsonFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(path -> path.toString().endsWith(".json")).sorted().collect(Collectors.toList());
        }
    }
}
