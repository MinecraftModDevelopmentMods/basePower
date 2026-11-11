package zone.moddev.mc.basepower.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import org.junit.BeforeClass;
import org.junit.Test;
import zone.moddev.mc.basepower.resources.NativeTestTags;
import static org.junit.Assert.*;

public class MaterialTagsTest {
    @BeforeClass public static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test public void addsNativeAliasesWithoutReplacingUserEntries() throws Exception {
        MaterialPolicyPack pack = new MaterialPolicyPack(true, true);
        assertEquals(Set.of(Items.STICK, Items.APPLE),
                resolve(pack, "rubber", "[\"minecraft:stick\",\"basepower:bioplastic_ingot\"]"));
        assertTrue(resolve(pack, "ingots/rubber", "[]").contains(Items.APPLE));
        assertEquals(Set.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.REDSTONE),
                resolve(pack, "solder", "[\"minecraft:redstone\"]"));
    }

    @Test public void disabledOptionsDoNotAddAliases() throws Exception {
        MaterialPolicyPack pack = new MaterialPolicyPack(false, false);
        assertEquals(Set.of(Items.STICK), resolve(pack, "rubber", "[\"minecraft:stick\"]"));
        assertTrue(resolve(pack, "solder", "[]").isEmpty());
    }

    private static Set<Item> resolve(MaterialPolicyPack pack, String path, String userEntries) throws Exception {
        var resource = Identifier.fromNamespaceAndPath("forge", "tags/item/" + path + ".json");
        JsonObject policy;
        try (var reader = new InputStreamReader(pack.getResource(PackType.SERVER_DATA, resource).get(),
                StandardCharsets.UTF_8)) {
            policy = JsonParser.parseReader(reader).getAsJsonObject();
        }
        assertFalse(policy.get("replace").getAsBoolean());
        Map<String, Item> items = Map.of("minecraft:stick", Items.STICK, "minecraft:redstone", Items.REDSTONE,
                "basepower:bioplastic_ingot", Items.APPLE);
        Map<String, List<Item>> tags = Map.of(
                "forge:ingots/lead", List.of(Items.IRON_INGOT), "forge:ingots/tin", List.of(Items.GOLD_INGOT));
        return NativeTestTags.resolve(id -> items.get(id.toString()), id -> tags.get(id.toString()),
                policy, JsonParser.parseString("{\"values\":" + userEntries + "}"));
    }
}
