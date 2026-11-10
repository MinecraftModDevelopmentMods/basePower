package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.Base64;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

public class ConnectedProbeObservationTest {
    private static final BlockPos POS = new BlockPos(4, 80, 7);

    @BeforeClass public static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test public void snapshotDoesNotAllocateAbsentPersistentData() throws Exception {
        observe(null);
    }

    @Test public void snapshotRetainsExistingEmptyPersistentDataWithoutNormalization() throws Exception {
        observe(new CompoundTag());
    }

    @Test public void snapshotReadsWitnessWithoutChangingAnyPersistentData() throws Exception {
        CompoundTag data = new CompoundTag();
        data.putString("AdvConnectedWitness", "keep witness");
        data.putLong("PrivateLong", 987654321L);
        data.putIntArray("PrivateArray", new int[]{3, 5, 8});
        observe(data);
    }

    private static void observe(CompoundTag data) throws Exception {
        ObservedChest tile = new ObservedChest();
        Field persistent = BlockEntity.class.getDeclaredField("customPersistentData");
        persistent.setAccessible(true);
        persistent.set(tile, data);
        CompoundTag before = tile.saveWithFullMetadata().copy();
        ObservationWorld world = allocate(ObservationWorld.class);
        world.tile = tile;
        ConnectedProbe probe = new ConnectedProbe();
        Field context = ConnectedProbe.class.getDeclaredField("world");
        context.setAccessible(true);
        context.set(probe, world);
        JsonObject definition = new JsonObject(), block = new JsonObject();
        block.addProperty("x", POS.getX());
        block.addProperty("y", POS.getY());
        block.addProperty("z", POS.getZ());
        JsonArray blocks = new JsonArray();
        blocks.add(block);
        definition.add("blocks", blocks);
        for (int observation = 0; observation < 2; observation++) {
            JsonObject row = probe.snapshot(definition).get(0).getAsJsonObject();
            assertSame("Observation must not allocate or replace live persistent data", data, persistent.get(tile));
            assertEquals("Observation must not call the allocating getter", 0, tile.persistentReads);
            assertEquals(before, tile.saveWithFullMetadata());
            assertEquals(before.toString(), row.get("nbt").getAsString());
            assertEquals(before, NbtIo.readCompressed(new ByteArrayInputStream(
                    Base64.getDecoder().decode(row.get("nbtBase64").getAsString()))));
            assertEquals(data == null ? "" : data.getString("AdvConnectedWitness"), row.get("witness").getAsString());
            assertEquals(data != null, before.contains("ForgeData"));
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static final class ObservedChest extends ChestBlockEntity {
        int persistentReads;
        ObservedChest() { super(POS, Blocks.CHEST.defaultBlockState()); }
        @Override public CompoundTag getPersistentData() { persistentReads++; return super.getPersistentData(); }
    }

    private static final class ObservationWorld extends ServerLevel {
        BlockEntity tile;
        private ObservationWorld() { super(null, null, null, null, null, null, null, false, 0L, List.of(), false, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.CHEST.defaultBlockState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return tile; }
    }
}
