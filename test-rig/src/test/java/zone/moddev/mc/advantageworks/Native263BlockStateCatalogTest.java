package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.BeforeClass;
import org.junit.Test;

public class Native263BlockStateCatalogTest {
    @BeforeClass public static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test public void nativeChestCompactDefaultHasAllThreeActualProperties() {
        var entry = Native263BlockStateCatalog.entry(Blocks.CHEST);
        assertEquals("minecraft:chest", entry.get("defaultEncoded").getAsString());
        var defaults = entry.getAsJsonObject("defaults");
        assertEquals(3, defaults.size());
        assertEquals("north", defaults.get("facing").getAsString());
        assertEquals("single", defaults.get("type").getAsString());
        assertEquals("false", defaults.get("waterlogged").getAsString());
        assertEquals(defaults, entry.getAsJsonObject("defaultDecoded").get("properties"));
        assertEquals(24, entry.get("stateCount").getAsInt());
        assertEquals(4, entry.getAsJsonObject("legalValues").getAsJsonArray("facing").size());
    }

    @Test public void nativeCompoundWithPartialPropertiesRetainsDefaultsIndependently() {
        var input = new com.google.gson.JsonObject();
        input.addProperty("id", "minecraft:chest");
        var properties = new com.google.gson.JsonObject();
        properties.addProperty("facing", "east");
        input.add("properties", properties);
        BlockState decoded = BlockState.CODEC.parse(JsonOps.INSTANCE, input).getOrThrow();
        var actual = Native263BlockStateCatalog.properties(decoded);
        assertEquals("east", actual.get("facing").getAsString());
        assertEquals("single", actual.get("type").getAsString());
        assertEquals("false", actual.get("waterlogged").getAsString());
        assertEquals(Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.EAST), decoded);
    }

    @Test public void singletonAndAxisDefaultUseActualNativeCodec() {
        var stone = Native263BlockStateCatalog.entry(Blocks.STONE);
        assertEquals(0, stone.getAsJsonObject("defaults").size());
        assertEquals(1, stone.get("stateCount").getAsInt());
        var oak = Native263BlockStateCatalog.entry(Blocks.OAK_LOG);
        assertEquals("y", oak.getAsJsonObject("defaults").get("axis").getAsString());
        assertEquals(3, oak.getAsJsonObject("legalValues").getAsJsonArray("axis").size());
    }

    @Test public void retainActualNativeVanillaCatalogEntriesForPythonContracts() throws Exception {
        var entries = new com.google.gson.JsonObject();
        for (var block : java.util.List.of(Blocks.AIR, Blocks.STONE, Blocks.OAK_LOG, Blocks.CHEST, Blocks.FURNACE))
            entries.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString(), Native263BlockStateCatalog.entry(block));
        var result = new com.google.gson.JsonObject();
        result.addProperty("scope", "ACTUAL_NATIVE_VANILLA_CODEC_ENTRIES_NOT_LIVE_FAMILY_CATALOG");
        result.addProperty("dataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        assertEquals(5023, result.get("dataVersion").getAsInt());
        result.add("blocks", entries);
        java.nio.file.Path output = java.nio.file.Path.of("build/native263-block-state-test-entries.json");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
        assertEquals(5, entries.size());
    }

    @Test public void restartVerificationNeverRewritesTheFirstNativeCatalog() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("native263-catalog-");
        try {
            var path = directory.resolve("catalog.json");
            var original = Native263BlockStateCatalog.entry(Blocks.CHEST);
            Native263BlockStateCatalog.retain(path, original);
            byte[] before = java.nio.file.Files.readAllBytes(path);
            Native263BlockStateCatalog.retain(path, original);
            var changed = original.deepCopy();
            changed.getAsJsonObject("defaults").addProperty("facing", "south");
            assertThrows(IllegalStateException.class, () -> Native263BlockStateCatalog.retain(path, changed));
            assertArrayEquals(before, java.nio.file.Files.readAllBytes(path));
            java.nio.file.Files.delete(path);
        } finally { java.nio.file.Files.delete(directory); }
    }
}
