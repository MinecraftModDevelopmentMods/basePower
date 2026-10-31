package zone.moddev.mc.advantageworks;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryWriteOps;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native117TestFlatTest {
    @BeforeClass public static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test public void constructorHasExactLayersAndNoStructures() {
        RegistryAccess registries = RegistryAccess.builtin();
        FlatLevelSource generator = (FlatLevelSource) Native117TestFlat.createType().createChunkGenerator(
                registries.registryOrThrow(Registry.BIOME_REGISTRY),
                registries.registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY), 42L, "");
        FlatLevelGeneratorSettings flat = generator.settings();
        assertEquals(3, flat.getLayersInfo().size());
        java.util.List<BlockState> layers = flat.getLayers();
        assertEquals(4, layers.size());
        assertEquals(Blocks.BEDROCK.defaultBlockState(), layers.get(0));
        assertEquals(Blocks.DIRT.defaultBlockState(), layers.get(1));
        assertEquals(Blocks.DIRT.defaultBlockState(), layers.get(2));
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), layers.get(3));
        assertTrue(flat.structureSettings().structureConfig().isEmpty());
        assertNull(flat.structureSettings().stronghold());
    }

    @Test public void fullSaveCodecRetainsVanillaFlatIdentityAndAllDimensions() {
        RegistryAccess registries = RegistryAccess.builtin();
        WorldGenSettings settings = Native117TestFlat.createType().createSettings(registries, 42L, false, false, "");
        JsonObject saved = WorldGenSettings.CODEC.encodeStart(RegistryWriteOps.create(JsonOps.INSTANCE, registries), settings)
                .result().orElseThrow(() -> new AssertionError("Native settings codec did not encode")).getAsJsonObject();
        assertEquals(42L, saved.get("seed").getAsLong());
        assertFalse(saved.get("generate_features").getAsBoolean());
        assertFalse(saved.get("bonus_chest").getAsBoolean());
        JsonObject dimensions = saved.getAsJsonObject("dimensions");
        assertEquals(3, dimensions.entrySet().size());
        assertTrue(dimensions.has("minecraft:the_nether"));
        assertTrue(dimensions.has("minecraft:the_end"));
        JsonObject generator = dimensions.getAsJsonObject("minecraft:overworld").getAsJsonObject("generator");
        assertEquals("minecraft:flat", generator.get("type").getAsString());
        JsonObject flat = generator.getAsJsonObject("settings");
        assertEquals("minecraft:plains", flat.get("biome").getAsString());
        assertEquals(3, flat.getAsJsonArray("layers").size());
        assertEquals(0, flat.getAsJsonObject("structures").getAsJsonObject("structures").entrySet().size());
        assertFalse(flat.getAsJsonObject("structures").has("stronghold"));
        assertFalse(saved.toString().contains(Native117TestFlat.ID));
    }

    @Test public void flatJsonCreationSettingsAreRefused() {
        RegistryAccess registries = RegistryAccess.builtin();
        try {
            Native117TestFlat.createType().createChunkGenerator(registries.registryOrThrow(Registry.BIOME_REGISTRY),
                    registries.registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY), 42L, "{}");
            fail("Flat JSON must not enter the vanilla JsonOps path");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must be empty"));
        }
    }
}
