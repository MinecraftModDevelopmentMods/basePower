package zone.moddev.mc.basepower.recipe;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.tags.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaterialTagsTest {
    @BeforeClass public static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test public void addsNativeAliasesWithoutReplacingUserEntries() throws Exception {
        MaterialPolicyPack pack = new MaterialPolicyPack(true, true);
        Tag<Item> rubber = resolve(pack, "rubber", "[\"minecraft:stick\",\"basepower:bioplastic_ingot\"]");
        assertTrue(rubber.getValues().contains(Items.STICK));
        assertTrue(rubber.getValues().contains(Items.APPLE));
        assertEquals(2, rubber.getValues().size());
        assertTrue(resolve(pack, "ingots/rubber", "[]").getValues().contains(Items.APPLE));
        Tag<Item> solder = resolve(pack, "solder", "[\"minecraft:redstone\"]");
        assertTrue(solder.getValues().contains(Items.IRON_INGOT));
        assertTrue(solder.getValues().contains(Items.GOLD_INGOT));
        assertTrue(solder.getValues().contains(Items.REDSTONE));
        assertEquals(3, solder.getValues().size());
    }

    @Test public void disabledOptionsDoNotAddAliases() throws Exception {
        MaterialPolicyPack pack = new MaterialPolicyPack(false, false);
        Tag<Item> rubber = resolve(pack, "rubber", "[\"minecraft:stick\"]");
        assertTrue(rubber.getValues().contains(Items.STICK));
        assertFalse(rubber.getValues().contains(Items.APPLE));
        assertTrue(resolve(pack, "solder", "[]").getValues().isEmpty());
    }

    private static Tag<Item> resolve(MaterialPolicyPack pack, String path, String userEntries) throws Exception {
        var resource = new ResourceLocation("forge", "tags/items/" + path + ".json");
        var parser = new JsonParser();
        var builder = Tag.Builder.tag();
        try (var reader = new InputStreamReader(pack.getResource(PackType.SERVER_DATA, resource),
                StandardCharsets.UTF_8)) {
            var json = parser.parse(reader).getAsJsonObject();
            assertFalse(json.get("replace").getAsBoolean());
            builder.addFromJson(json, pack.getName());
        }
        builder.addFromJson(parser.parse("{\"values\":" + userEntries + "}").getAsJsonObject(), "user");
        Map<String, Item> items = Map.of("minecraft:stick", Items.STICK, "minecraft:redstone", Items.REDSTONE,
                "basepower:bioplastic_ingot", Items.APPLE);
        Map<String, Tag<Item>> tags = Map.of(
                "forge:ingots/lead", new Tag<>(java.util.List.of(Items.IRON_INGOT)),
                "forge:ingots/tin", new Tag<>(java.util.List.of(Items.GOLD_INGOT)));
        return builder.<Item>build(id -> tags.get(id.toString()), id -> items.get(id.toString())).right().get();
    }
}
