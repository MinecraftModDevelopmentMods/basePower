package zone.moddev.mc.advantageworks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native119TestFlatTest {
    private static RegistryAccess registries;

    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var base = RegistryLayer.createRegistryAccess();
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource()))) {
            var worldgen = RegistryDataLoader.load(resources, base.compositeAccess(),
                    RegistryDataLoader.WORLDGEN_REGISTRIES);
            registries = base.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();
        }
    }

    @Test public void dedicatedServerSettingsKeepExactLayersAndSaveAllThreeDimensions() throws Exception {
        JsonObject json;
        try (var input = getClass().getResourceAsStream("/assets/advantageworkstest/works/flat-generator.json")) {
            assertNotNull("Missing shared flat settings", input);
            json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Class<?> type = Class.forName("net.minecraft.server.dedicated.DedicatedServerProperties$WorldDimensionData");
        var constructor = type.getDeclaredConstructor(JsonObject.class, String.class);
        constructor.setAccessible(true);
        var create = type.getDeclaredMethod("create", RegistryAccess.class);
        create.setAccessible(true);
        var dimensions = (WorldDimensions) create.invoke(constructor.newInstance(json, "minecraft:flat"), registries);
        var generator = (FlatLevelSource) dimensions.overworld();
        var flat = generator.settings();
        assertEquals(3, flat.getLayersInfo().size());
        assertEquals(List.of(Blocks.BEDROCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState()), flat.getLayers());
        assertEquals(0, flat.structureOverrides().orElseThrow().size());

        JsonObject saved = WorldGenSettings.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries),
                new WorldGenSettings(new WorldOptions(42L, false, false), dimensions))
                .getOrThrow(false, message -> { throw new AssertionError(message); }).getAsJsonObject();
        assertEquals(42L, saved.get("seed").getAsLong());
        assertFalse(saved.get("generate_features").getAsBoolean());
        assertFalse(saved.get("bonus_chest").getAsBoolean());
        JsonObject dimensionsTag = saved.getAsJsonObject("dimensions");
        assertEquals(3, dimensionsTag.entrySet().size());
        assertTrue(dimensionsTag.has("minecraft:the_nether"));
        assertTrue(dimensionsTag.has("minecraft:the_end"));
        JsonObject generatorTag = dimensionsTag.getAsJsonObject("minecraft:overworld").getAsJsonObject("generator");
        assertEquals("minecraft:flat", generatorTag.get("type").getAsString());
        assertEquals("minecraft:plains", generatorTag.getAsJsonObject("settings").get("biome").getAsString());
    }
}
