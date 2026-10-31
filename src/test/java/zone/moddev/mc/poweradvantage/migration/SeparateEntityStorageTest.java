package zone.moddev.mc.poweradvantage.migration;

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
        assertEquals(0, staged.getList("Entities", 10).size());
        LegacyWorldDataHook.finalizeLegacyChunk(staged);
        CompoundTag entity = staged.getList("Entities", 10).getCompound(0);
        assertEquals("foreign:machine_witness", entity.getString("id"));
        assertEquals("keep this payload", entity.getString("ForeignState"));
        assertEquals(new UUID(0, 17), entity.getUUID("UUID"));
        assertEquals(new UUID(9, 0), entity.getList("Passengers", 10).getCompound(0).getUUID("UUID"));
        assertArrayEquals(new int[] {7, -8}, staged.getIntArray("Position"));
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
