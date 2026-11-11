package zone.moddev.mc.basepower.migration;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class SeparateEntityStorageTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        AdvantageLegacyMigrations.registerPowerAdvantage();
    }

    @Test public void separateEntityStoragePreservesForeignPayloadsAndPasseningTreesOnACopy() {
        CompoundTag source = fixture();
        CompoundTag before = source.copy();
        CompoundTag staged = LegacyWorldDataHook.prepareEntityChunkForMigration(source);
        assertEquals(before, source);
        assertNotSame(source, staged);
        assertEquals(0, staged.getListOrEmpty("Entities").size());
        LegacyWorldDataHook.finalizeLegacyChunk(staged);
        CompoundTag entity = staged.getListOrEmpty("Entities").getCompoundOrEmpty(0);
        assertEquals("foreign:machine_witness", entity.getStringOr("id", ""));
        assertEquals("keep this payload", entity.getStringOr("ForeignState", ""));
        assertEquals(new UUID(0, 17), entity.read("UUID", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        assertEquals(new UUID(9, 0), entity.getListOrEmpty("Passengers").getCompoundOrEmpty(0).read("UUID", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        assertArrayEquals(new int[] {7, -8}, staged.getIntArray("Position").orElseThrow());
        assertFalse(staged.contains("AdvantageLegacyEntityDataFix"));
        assertEquals(before, source);
    }

    @Test(expected = IllegalStateException.class) public void missingChunkCoordinatesFailClosed() {
        CompoundTag source = fixture();
        source.remove("Position");
        LegacyWorldDataHook.prepareEntityChunkForMigration(source);
    }

    @Test(expected = IllegalStateException.class) public void malformedChunkCoordinatesFailClosed() {
        CompoundTag source = fixture();
        source.putIntArray("Position", new int[] {7});
        LegacyWorldDataHook.prepareEntityChunkForMigration(source);
    }

    private static CompoundTag fixture() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 2586);
        root.putIntArray("Position", new int[] {7, -8});
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "foreign:machine_witness");
        entity.putString("ForeignState", "keep this payload");
        entity.putLong("UUIDMost", 0);
        entity.putLong("UUIDLeast", 17);
        CompoundTag passenger = new CompoundTag();
        passenger.putString("id", "foreign:passenger");
        passenger.putLong("UUIDMost", 9);
        passenger.putLong("UUIDLeast", 0);
        ListTag riders = new ListTag();
        riders.add(passenger);
        entity.put("Passengers", riders);
        ListTag entities = new ListTag();
        entities.add(entity);
        root.put("Entities", entities);
        return root;
    }
}
