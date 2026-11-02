package zone.moddev.mc.basepower.init;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OreSpawnWorldGenTest {
    @Test
    public void desertOilProviderUsesStableIdentityAndCoveredLakeGeometry() {
        JsonObject root = OreSpawnWorldGen.createProvider().toJson();
        assertEquals(4, root.get("schema_version").getAsInt());
        assertEquals("basepower", root.get("provider_modid").getAsString());
        JsonObject deposits = root.getAsJsonObject("fluid_deposits");
        assertEquals(1, deposits.entrySet().size());
        JsonObject deposit = deposits.getAsJsonObject(
                "basepower:fluid_deposit/desert_crude_oil");
        assertEquals("basepower:crude_oil", deposit.get("block").getAsString());
        assertTrue(deposit.get("enabled").getAsBoolean());
        JsonObject overworld = deposit.getAsJsonObject("dimensions")
                .getAsJsonObject("minecraft:overworld");
        assertEquals(0.08D, overworld.get("frequency").getAsDouble(), 0.0D);
        assertEquals(5, overworld.get("min_radius").getAsInt());
        assertEquals(12, overworld.get("max_radius").getAsInt());
        assertEquals(2, overworld.get("min_solid_cover").getAsInt());
        assertEquals(1, overworld.get("min_solid_shell").getAsInt());
        assertEquals("SANDY", overworld.getAsJsonArray("biome_dictionary").get(0).getAsString());
        assertEquals("BEACH", overworld.getAsJsonArray("excluded_biome_dictionary").get(0).getAsString());
        assertEquals("MESA", overworld.getAsJsonArray("excluded_biome_dictionary").get(1).getAsString());
    }
}
