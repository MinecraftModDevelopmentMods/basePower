package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeLootResourceTest {
    @BeforeClass public static void bootstrap() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries.registerHolderSetTypes();
    }

    @Test public void everyLootFunctionAndPredicateUsesTheCurrentNativeCodec() throws Exception {
        try (var files = Files.walk(Path.of("src/main/resources/data/basepower/loot_table"))) {
            int checked = 0;
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                checked += check(JsonParser.parseString(Files.readString(file)), file.toString());
            }
            assertTrue("The codec scan must cover real block drops and chest counts", checked > 25);
        }
    }

    private static int check(JsonElement value, String source) {
        int checked = 0;
        if (value.isJsonArray()) {
            for (var element : value.getAsJsonArray()) checked += check(element, source);
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            assertFalse(source + ": obsolete function field", object.has("function"));
            assertFalse(source + ": obsolete condition field", object.has("condition"));
            for (var entry : object.entrySet()) {
                if (entry.getKey().equals("functions") || entry.getKey().equals("conditions")) {
                    for (var element : entry.getValue().getAsJsonArray()) {
                        var result = entry.getKey().equals("functions")
                                ? LootItemFunctions.DIRECT_CODEC.parse(JsonOps.INSTANCE, element)
                                : LootItemCondition.DIRECT_CODEC.parse(JsonOps.INSTANCE, element);
                        assertTrue(source + ": " + result, result.isSuccess());
                        checked++;
                    }
                }
                checked += check(entry.getValue(), source);
            }
        }
        return checked;
    }
}
