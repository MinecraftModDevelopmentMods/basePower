package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.BeforeClass;
import org.junit.Test;

public class Native117StoppedEntitiesSnapshotV2Test {
    @BeforeClass public static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test public void partitionsExactNativeNoSaveHelperWithoutMutation() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.NETHER);
        LeashFenceKnotEntity knot = new LeashFenceKnotEntity(world, new BlockPos(154, 100, 8));
        ItemEntity item = new ItemEntity(world, 154, 100, 8, new ItemStack(Items.DIRT, 7));
        CompoundTag before = knot.saveWithoutId(new CompoundTag()).copy();
        assertFalse(before.contains("TileX"));
        assertFalse(before.contains("TileY"));
        assertFalse(before.contains("TileZ"));
        assertEquals(154.5, before.getList("Pos", 6).getDouble(0), 0.0);
        assertEquals(100.375, before.getList("Pos", 6).getDouble(1), 0.0);
        assertEquals(8.5, before.getList("Pos", 6).getDouble(2), 0.0);
        CompoundTag persistent = Native117StoppedEntitiesSnapshot.serialized(item).copy();
        JsonObject record = Native117StoppedEntitiesSnapshotV2.partition(world, List.of(knot, item), new HashSet<>());
        assertEquals(1, record.get("entityCount").getAsInt());
        assertEquals(1, record.get("nonpersistentHelperCount").getAsInt());
        assertEquals(2, record.get("observedEntityCount").getAsInt());
        assertEquals("minecraft:leash_knot", record.getAsJsonArray("nonpersistentHelpers").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(before, knot.saveWithoutId(new CompoundTag()));
        assertEquals(persistent, Native117StoppedEntitiesSnapshot.serialized(item));
        assertEquals(0, knot.tickCount);
        assertFalse(knot.isRemoved());
    }

    @Test public void unexpectedNoSaveTypeFailsWithActualNativeIdentity() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.END);
        LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, world);
        try {
            Native117StoppedEntitiesSnapshotV2.partition(world, List.of(bolt), new HashSet<>());
            fail("Unproven no-save type was skipped");
        } catch (IllegalStateException error) {
            assertTrue(error.getMessage().contains(bolt.getUUID().toString()));
            assertTrue(error.getMessage().contains("class=net.minecraft.world.entity.LightningBolt"));
            assertTrue(error.getMessage().contains("id=minecraft:lightning_bolt"));
            assertTrue(error.getMessage().contains("canSerialize=false"));
        }
    }

    @Test public void helperCannotReplacePersistentUuidOrAnotherWorld() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.NETHER);
        LeashFenceKnotEntity knot = new LeashFenceKnotEntity(world, new BlockPos(154, 100, 8));
        ItemEntity item = new ItemEntity(world, 154, 100, 8, new ItemStack(Items.DIRT, 7));
        item.setUUID(knot.getUUID());
        try {
            Native117StoppedEntitiesSnapshotV2.partition(world, List.of(knot, item), new HashSet<>());
            fail("Duplicate classification UUID accepted");
        } catch (IllegalStateException expected) { assertTrue(expected.toString().contains("Persistent writer")); }
        try {
            Native117StoppedEntitiesSnapshotV2.partition(Native117EntityContextTest.world(Level.END), List.of(knot), new HashSet<>());
            fail("Wrong-world helper accepted");
        } catch (IllegalStateException expected) { assertTrue(expected.toString().contains("another world")); }
    }

    @Test public void resourceIsExactIndependentNativeControl() throws Exception {
        assertEquals("59B45FFB7D3CA1F726EFDE111D1716FB0583B092D4F7EAA1A2AE09FA71C7F73F",
                Native117StoppedEntitiesSnapshotV2.sha(Native117StoppedEntitiesSnapshotV2.contract()));
    }
}
