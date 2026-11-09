package zone.moddev.mc.basepower.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaterialPolicyPackTest {
    @Test public void enabledPolicyAddsOnlyTheHistoricalAliases() throws Exception {
        MaterialPolicyPack pack = new MaterialPolicyPack(true, true);
        for (String path : new String[] {"rubber", "ingots/rubber"}) {
            JsonObject tag = read(pack, path);
            assertFalse(tag.get("replace").getAsBoolean());
            assertEquals(1, tag.getAsJsonArray("values").size());
            assertEquals("basepower:bioplastic_ingot", tag.getAsJsonArray("values").get(0).getAsString());
        }
        JsonObject solder = read(pack, "solder");
        assertEquals(3, solder.getAsJsonArray("values").size());
        int index = 0;
        for (String metal : new String[] {"lead", "tin", "silver"}) {
            JsonObject entry = solder.getAsJsonArray("values").get(index++).getAsJsonObject();
            assertEquals("#forge:ingots/" + metal, entry.get("id").getAsString());
            assertFalse(entry.get("required").getAsBoolean());
        }
    }

    @Test public void disabledPolicyDoesNotReplacePlayerTagsOrSupplyAliases() throws Exception {
        MaterialPolicyPack pack = new MaterialPolicyPack(false, false);
        for (String path : new String[] {"rubber", "ingots/rubber", "solder"}) {
            JsonObject tag = read(pack, path);
            assertFalse(tag.get("replace").getAsBoolean());
            assertEquals(0, tag.getAsJsonArray("values").size());
        }
    }

    @Test public void policyOnlySuppliesServerDataAndHonoursResourceDiscovery() {
        MaterialPolicyPack pack = new MaterialPolicyPack(true, false);
        ResourceLocation rubber = new ResourceLocation("forge", "tags/items/rubber.json");
        assertNotNull(pack.getResource(PackType.SERVER_DATA, rubber));
        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, rubber));
        assertEquals(Set.of(rubber, new ResourceLocation("forge:tags/items/ingots/rubber.json"),
                new ResourceLocation("forge:tags/items/solder.json")), list(pack, PackType.SERVER_DATA, "forge"));
        assertTrue(list(pack, PackType.SERVER_DATA, "basepower").isEmpty());
        assertTrue(list(pack, PackType.CLIENT_RESOURCES, "forge").isEmpty());
    }

    private static Set<ResourceLocation> list(MaterialPolicyPack pack, PackType type, String namespace) {
        Set<ResourceLocation> entries = new HashSet<>();
        pack.listResources(type, namespace, "tags/items", (id, supplier) -> entries.add(id));
        return entries;
    }

    private static JsonObject read(MaterialPolicyPack pack, String path) throws Exception {
        try (InputStream stream = pack.getResource(PackType.SERVER_DATA,
                new ResourceLocation("forge", "tags/items/" + path + ".json")).get()) {
            return new JsonParser().parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
