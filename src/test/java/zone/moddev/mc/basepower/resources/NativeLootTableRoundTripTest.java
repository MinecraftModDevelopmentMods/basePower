package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.storage.loot.LootTable;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Whole-table codecs with bijective vanilla item stand-ins; real mod IDs and drops are server gates. */
public class NativeLootTableRoundTripTest {
    private static final Path TABLES = Path.of("src/main/resources/data/basepower/loot_table");
    private static final Map<String, String> standIns = new LinkedHashMap<>();
    private static final Map<String, String> originals = new LinkedHashMap<>();

    @BeforeClass public static void bootstrap() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries.registerHolderSetTypes();
        var names = new java.util.TreeSet<String>();
        try (var files = Files.walk(TABLES)) {
            for (var file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                collectItems(JsonParser.parseString(Files.readString(file)), names);
            }
        }
        var vanilla = BuiltInRegistries.ITEM.keySet().stream().map(Object::toString)
                .filter(id -> !names.contains(id) && !id.equals("minecraft:air")).sorted().iterator();
        for (String name : names) {
            if (name.startsWith("minecraft:")) continue;
            String replacement = vanilla.next();
            standIns.put(name, replacement);
            assertNull(originals.put(replacement, name));
        }
        assertEquals(standIns.size(), originals.size());
    }

    @Test public void everyWholeTableRetainsItsItemsWeightsCountsConditionsAndModifiers() throws Exception {
        var baseline = JsonParser.parseString(Files.readString(Path.of("src/test/resources/native262-loot-semantics.json"))).getAsJsonObject();
        assertEquals("7E456DE1A45140E86415A701D186FEDE08C4AB63D53C6B7848935DB70CBC68B1", baseline.get("sourceJarSha256").getAsString());
        int count = 0;
        try (var files = Files.walk(TABLES)) {
            for (var file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                var source = JsonParser.parseString(Files.readString(file));
                String path = TABLES.relativize(file).toString().replace('\\', '/');
                assertEquals(path + ": accepted 26.2 loot semantics changed", project(baseline.getAsJsonObject("tables").get(path)), project(source));
                assertPreserved(source);
                count++;
            }
        }
        assertEquals(28, count);
    }

    @Test public void ignoredLegacyParentFieldsCannotPassEvenWhenTheirLeafFunctionsDecode() throws Exception {
        var portable = JsonParser.parseString(Files.readString(TABLES.resolve("blocks/fluid_storage_tank.json"))).getAsJsonObject();
        assertPreserved(portable);
        var changed = portable.deepCopy();
        var entry = changed.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject();
        entry.add("functions", entry.remove("modifier"));
        var obsoleteFunctions = changed;
        assertThrows(AssertionError.class, () -> assertPreserved(obsoleteFunctions));
        changed = portable.deepCopy();
        var pool = changed.getAsJsonArray("pools").get(0).getAsJsonObject();
        var conditions = new com.google.gson.JsonArray();
        conditions.add(pool.remove("condition"));
        pool.add("conditions", conditions);
        var obsolete = changed;
        assertThrows(AssertionError.class, () -> assertPreserved(obsolete));
    }

    @Test public void nativeEncodingActuallyKeepsBothPortableFluidCopyPaths() throws Exception {
        var portable = JsonParser.parseString(Files.readString(TABLES.resolve("blocks/fluid_storage_tank.json")));
        var table = LootTable.DIRECT_CODEC.parse(JsonOps.INSTANCE, references(portable)).getOrThrow();
        var encoded = LootTable.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, table).getOrThrow().toString();
        assertTrue(encoded, encoded.contains("Tank.FluidName"));
        assertTrue(encoded, encoded.contains("Tank.Amount"));
        assertTrue(encoded, encoded.contains("FluidID"));
        assertTrue(encoded, encoded.contains("Volume"));
        assertFalse(encoded, encoded.contains("\"functions\""));
    }

    @Test public void changedDropCountsItemsAndCopyPathsCannotPassTheHistoricalBaseline() throws Exception {
        String path = "blocks/fluid_storage_tank.json";
        var source = JsonParser.parseString(Files.readString(TABLES.resolve(path))).getAsJsonObject();
        var changed = source.deepCopy();
        changed.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0)
                .getAsJsonObject().addProperty("name", "minecraft:dirt");
        var item = changed;
        assertThrows(AssertionError.class, () -> assertHistorical(path, item));
        changed = source.deepCopy();
        changed.getAsJsonArray("pools").get(0).getAsJsonObject().addProperty("rolls", 2);
        var count = changed;
        assertThrows(AssertionError.class, () -> assertHistorical(path, count));
        changed = source.deepCopy();
        changed.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0)
                .getAsJsonObject().getAsJsonArray("modifier").get(0).getAsJsonObject().getAsJsonArray("ops")
                .get(0).getAsJsonObject().addProperty("source", "WrongTank.FluidName");
        var copyPath = changed;
        assertThrows(AssertionError.class, () -> assertHistorical(path, copyPath));
    }

    private static void assertHistorical(String path, JsonElement actual) throws Exception {
        var baseline = JsonParser.parseString(Files.readString(Path.of("src/test/resources/native262-loot-semantics.json")))
                .getAsJsonObject().getAsJsonObject("tables");
        assertEquals(project(baseline.get(path)), project(actual));
    }

    private static void assertPreserved(JsonElement source) {
        var value = LootTable.DIRECT_CODEC.parse(JsonOps.INSTANCE, references(source)).getOrThrow();
        var encoded = LootTable.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, value).getOrThrow();
        assertEquals(project(source), project(encoded));
    }

    private static JsonElement project(JsonElement value) {
        if (value.isJsonArray()) {
            var result = new com.google.gson.JsonArray();
            for (var entry : value.getAsJsonArray()) result.add(project(entry));
            return result;
        }
        if (!value.isJsonObject()) return value.deepCopy();
        var result = new JsonObject();
        for (var entry : value.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            assertFalse("Obsolete loot parent field: " + key, key.equals("functions") || key.equals("conditions") || key.equals("function"));
            var field = entry.getValue();
            if (key.equals("__comment") || key.equals("weight") && field.getAsInt() == 1
                    || key.equals("quality") && field.getAsInt() == 0 || key.equals("bonus_rolls") && field.getAsFloat() == 0F
                    || key.equals("pools") && field.isJsonArray() && field.getAsJsonArray().isEmpty()
                    || key.equals("add") && !field.getAsBoolean()) continue;
            if (key.equals("type") && field.isJsonPrimitive() && !field.getAsString().contains(":"))
                result.addProperty(key, "minecraft:" + field.getAsString());
            else if (key.equals("name") && field.isJsonPrimitive() && originals.containsKey(field.getAsString()))
                result.addProperty(key, originals.get(field.getAsString()));
            else if (key.equals("modifier") && field.isJsonArray() && field.getAsJsonArray().size() == 1)
                result.add(key, project(field.getAsJsonArray().get(0)));
            else result.add(key, project(field));
        }
        return result;
    }

    private static JsonElement references(JsonElement value) {
        var copy = value.deepCopy();
        replaceItems(copy);
        return copy;
    }

    private static void replaceItems(JsonElement value) {
        if (value.isJsonArray()) {
            for (var entry : value.getAsJsonArray()) replaceItems(entry);
        } else if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (isItem(object)) {
                String id = object.get("name").getAsString();
                if (standIns.containsKey(id)) object.addProperty("name", standIns.get(id));
            }
            object.entrySet().forEach(entry -> replaceItems(entry.getValue()));
        }
    }

    private static boolean isItem(JsonObject object) {
        return object.has("type") && (object.get("type").getAsString().equals("minecraft:item") || object.get("type").getAsString().equals("item"));
    }

    private static void collectItems(JsonElement value, java.util.Set<String> names) {
        if (value.isJsonArray()) {
            for (var entry : value.getAsJsonArray()) collectItems(entry, names);
        } else if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (isItem(object)) names.add(object.get("name").getAsString());
            object.entrySet().forEach(entry -> collectItems(entry.getValue(), names));
        }
    }
}
