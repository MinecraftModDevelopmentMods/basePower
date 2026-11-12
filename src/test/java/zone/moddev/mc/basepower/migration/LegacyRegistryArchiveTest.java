package zone.moddev.mc.basepower.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LegacyRegistryArchiveTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void restartRetainsOriginalRegistryAfterForgeReplacesBothLevelFiles() throws Exception {
        AdvantageLegacyMigrations.registerPowerAdvantage();
        Path world = temporary.newFolder().toPath();
        Path level = world.resolve("level.dat");
        CompoundTag source = registrySnapshot();
        NbtIo.writeCompressed(source, level);
        assertEquals(level.toFile(), LegacyWorldDataHook.legacyRegistrySource(level.toFile()));
        byte[] before = Files.readAllBytes(level);
        LegacyWorldDataHook.preserveLegacyRegistry(level.toFile(), source);
        assertArrayEquals("Archiving must not change level.dat", before, Files.readAllBytes(level));

        CompoundTag nativeSave = new CompoundTag();
        nativeSave.put("fml", new CompoundTag());
        NbtIo.writeCompressed(nativeSave, level);
        NbtIo.writeCompressed(nativeSave, world.resolve("level.dat_old"));
        var retained = LegacyWorldDataHook.legacyRegistrySource(level.toFile());
        assertNotNull(retained);
        assertNotEquals(level.toFile(), retained);
        var archive = NbtIo.readCompressed(retained.toPath(), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        assertEquals(source.getCompoundOrEmpty("FML"), archive.getCompoundOrEmpty("FML"));
        assertFalse(archive.contains("Data"));
        LegacyWorldDataHook.preserveLegacyRegistry(level.toFile(), nativeSave);
        assertEquals(archive, NbtIo.readCompressed(retained.toPath(), net.minecraft.nbt.NbtAccounter.unlimitedHeap()));
    }

    @Test public void terrainGuardProtectsOnlySavedDimensionsAndOldHeights() throws Exception {
        Path world = temporary.newFolder().toPath();
        Path region = Files.createDirectories(world.resolve("DIM-1/region")).resolve("r.0.0.mca");
        byte[] header = new byte[4096];
        header[2] = 2;
        header[3] = 1;
        Files.write(region, header);
        try {
            assertEquals(1, LegacyWorldDataHook.indexLegacyChunks(world.toFile()));
            var nether = Identifier.parse("the_nether");
            assertTrue(LegacyWorldDataHook.isProtectedChunk(nether, new BlockPos(0, 0, 0)));
            assertTrue(LegacyWorldDataHook.isProtectedChunk(nether, new BlockPos(0, 255, 0)));
            assertFalse(LegacyWorldDataHook.isProtectedChunk(nether, new BlockPos(0, -1, 0)));
            assertFalse(LegacyWorldDataHook.isProtectedChunk(nether, new BlockPos(0, 256, 0)));
            assertFalse(LegacyWorldDataHook.isProtectedChunk(nether, new BlockPos(16, 64, 0)));
            assertFalse(LegacyWorldDataHook.isProtectedChunk(Identifier.parse("overworld"), new BlockPos(0, 64, 0)));
        } finally {
            LegacyWorldDataHook.indexLegacyChunks(temporary.newFolder());
        }
    }

    @Test public void relocatedAndCustomDimensionChunksRetainExactTerrainGuards() throws Exception {
        Path world = temporary.newFolder().toPath();
        writeChunkIndex(world, "dimensions/minecraft/overworld/region/r.-1.0.mca", 31, 0);
        writeChunkIndex(world, "dimensions/minecraft/the_nether/region/r.0.-1.mca", 0, 31);
        writeChunkIndex(world, "dimensions/other/deep/cavern/region/r.1.-1.mca", 2, 3);
        // A retained old directory may overlap a native directory during recovery.
        writeChunkIndex(world, "region/r.-1.0.mca", 31, 0);
        try {
            assertEquals("Duplicate paths must not invent an extra saved chunk", 3,
                    LegacyWorldDataHook.indexLegacyChunks(world.toFile()));
            assertTrue(LegacyWorldDataHook.isProtectedChunk(Identifier.parse("minecraft:overworld"),
                    new BlockPos(-16, 64, 0)));
            assertTrue(LegacyWorldDataHook.isProtectedChunk(Identifier.parse("minecraft:the_nether"),
                    new BlockPos(0, 64, -16)));
            assertTrue(LegacyWorldDataHook.isProtectedChunk(Identifier.parse("other:deep/cavern"),
                    new BlockPos(34 * 16, 64, -29 * 16)));
            assertFalse(LegacyWorldDataHook.isProtectedChunk(Identifier.parse("minecraft:the_end"),
                    new BlockPos(-16, 64, 0)));
            assertFalse(LegacyWorldDataHook.isProtectedChunk(Identifier.parse("other:deep/cavern"),
                    new BlockPos(35 * 16, 64, -29 * 16)));
            assertEquals("Repeated indexing must remain idempotent", 3,
                    LegacyWorldDataHook.indexLegacyChunks(world.toFile()));
        } finally {
            LegacyWorldDataHook.indexLegacyChunks(temporary.newFolder());
        }
    }

    private static void writeChunkIndex(Path world, String relative, int localX, int localZ) throws Exception {
        Path region = world.resolve(relative);
        Files.createDirectories(region.getParent());
        byte[] header = new byte[4096];
        int offset = (localX + localZ * 32) * 4;
        header[offset + 2] = 2;
        header[offset + 3] = 1;
        Files.write(region, header);
    }

    private static CompoundTag registrySnapshot() {
        CompoundTag source = new CompoundTag();
        CompoundTag fml = new CompoundTag();
        CompoundTag registries = new CompoundTag();
        CompoundTag blocks = new CompoundTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("K", "poweradvantage:item_conveyor");
        entry.putInt("V", 1420);
        ListTag ids = new ListTag();
        ids.add(entry);
        blocks.put("ids", ids);
        registries.put("minecraft:blocks", blocks);
        fml.put("Registries", registries);
        source.put("FML", fml);
        source.put("Data", new CompoundTag());
        return source;
    }
}
