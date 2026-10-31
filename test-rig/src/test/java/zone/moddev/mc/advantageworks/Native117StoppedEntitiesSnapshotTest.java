package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.fmlserverevents.FMLServerStoppingEvent;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Native constructors/writers/readers, without starting Minecraft or claiming stop-order runtime proof. */
public class Native117StoppedEntitiesSnapshotTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @BeforeClass public static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test public void requiresExactWorldAndAnActualAllowedMarker() throws Exception {
        Path runtime = temp.newFolder().toPath();
        assertFalse(Native117StoppedEntitiesSnapshot.marked(runtime, "AdvantageWorksMigration"));
        Files.createDirectory(runtime.resolve("advantage-migration.allow"));
        assertFalse(Native117StoppedEntitiesSnapshot.marked(runtime, "AdvantageWorksMigration"));
        Files.delete(runtime.resolve("advantage-migration.allow"));
        Files.createFile(runtime.resolve("advantage-migration.allow"));
        assertTrue(Native117StoppedEntitiesSnapshot.marked(runtime, "AdvantageWorksMigration"));
        assertFalse(Native117StoppedEntitiesSnapshot.marked(runtime, "New World"));
        assertFalse(Native117StoppedEntitiesSnapshot.marked(runtime, "advantageworksmigration"));
        Files.delete(runtime.resolve("advantage-migration.allow"));
        Files.createFile(runtime.resolve("advantage-machine-operation.allow"));
        assertTrue(Native117StoppedEntitiesSnapshot.marked(runtime, "AdvantageWorksMigration"));
    }

    @Test public void retainsNativeItemInventoryNbtAndDoesNotTickOrMutateEntity() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.NETHER);
        ItemEntity entity = new ItemEntity(world, 8.5, 80, 8.5, new ItemStack(Items.DIRT, 7));
        entity.getPersistentData().putString("Private", "keep exactly");
        CompoundTag before = Native117StoppedEntitiesSnapshot.serialized(entity).copy();
        int ticks = entity.tickCount;
        JsonObject row = Native117StoppedEntitiesSnapshot.entities(world, List.of(entity), new HashSet<>()).get(0).getAsJsonObject();
        CompoundTag captured = decode(row);
        assertEquals(before, captured);
        assertEquals(7, captured.getCompound("Item").getByte("Count"));
        assertEquals("keep exactly", captured.getCompound("ForgeData").getString("Private"));
        assertEquals("minecraft:the_nether", row.get("worldDimensionKey").getAsString());
        assertEquals(entity.getUUID().toString(), row.get("uuid").getAsString());
        assertEquals(ticks, entity.tickCount);
        assertEquals(before, Native117StoppedEntitiesSnapshot.serialized(entity));
        assertFalse(entity.isRemoved());
    }

    @Test public void capturesRootAndPassengerNativeNbtWithoutDismounting() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.END);
        Boat root = new Boat(EntityType.BOAT, world), passenger = new Boat(EntityType.BOAT, world);
        root.setPos(8.5, 80, 8.5);
        passenger.setPos(8.5, 81, 8.5);
        assertTrue(passenger.startRiding(root, true));
        CompoundTag before = Native117StoppedEntitiesSnapshot.serialized(root).copy();
        JsonArray rows = Native117StoppedEntitiesSnapshot.entities(world, List.of(root, passenger), new HashSet<>());
        assertEquals(2, rows.size());
        assertEquals(before, decode(rows.get(0).getAsJsonObject()));
        assertEquals(passenger.getUUID(), decode(rows.get(0).getAsJsonObject()).getList("Passengers", 10).getCompound(0).getUUID("UUID"));
        assertEquals(passenger.getUUID(), decode(rows.get(1).getAsJsonObject()).getUUID("UUID"));
        assertEquals(decode(rows.get(0).getAsJsonObject()).getList("Passengers", 10).getCompound(0),
                decode(rows.get(1).getAsJsonObject()));
        assertEquals(root.getUUID().toString(), rows.get(1).getAsJsonObject().get("vehicleUuid").getAsString());
        assertSame(root, passenger.getVehicle());
        assertEquals(before, Native117StoppedEntitiesSnapshot.serialized(root));
    }

    @Test public void nativeLoadAndSavePreserveCapturedItemNbt() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.OVERWORLD);
        ItemEntity entity = new ItemEntity(world, -0.25, 80, 8.5, new ItemStack(Items.DIRT, 7));
        entity.getPersistentData().putInt("Private", 42);
        CompoundTag saved = Native117StoppedEntitiesSnapshot.serialized(entity);
        Entity loaded = EntityType.loadEntityRecursive(saved.copy(), world, value -> value);
        assertNotNull(loaded);
        assertEquals(saved, Native117StoppedEntitiesSnapshot.serialized(loaded));
    }

    @Test public void skipsNativePlayerTypesBeforeTouchingTheirFields() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.OVERWORLD);
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        ServerPlayer player = (ServerPlayer) unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), ServerPlayer.class);
        assertEquals(0, Native117StoppedEntitiesSnapshot.entities(world, List.of(player), new HashSet<>()).size());
    }

    @Test public void rejectsWrongWorldAndDuplicateEnumeratedUuid() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.OVERWORLD);
        Boat root = new Boat(EntityType.BOAT, world), other = new Boat(EntityType.BOAT, world);
        other.setUUID(root.getUUID());
        try {
            Native117StoppedEntitiesSnapshot.entities(world, List.of(root, other), new HashSet<>());
            fail("Duplicate enumerated UUID accepted");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Duplicate")); }
        try {
            Native117StoppedEntitiesSnapshot.entities(Native117EntityContextTest.world(Level.NETHER), List.of(root), new HashSet<>());
            fail("Wrong dimension instance accepted");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("another world")); }
    }

    @Test public void nativeWriterRefusalIsAFailureNotASilentSkippedEntity() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.OVERWORLD);
        LightningBolt transientEntity = new LightningBolt(EntityType.LIGHTNING_BOLT, world);
        try {
            Native117StoppedEntitiesSnapshot.entities(world, List.of(transientEntity), new HashSet<>());
            fail("Native writer refusal was hidden");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("writer refused")); }
    }

    @Test public void replacesOnlyRuntimeSnapshotAndLeavesNoTemporaryFile() throws Exception {
        Path runtime = temp.newFolder().toPath();
        JsonObject first = new JsonObject(); first.addProperty("status", "CAPTURED");
        JsonObject next = new JsonObject(); next.addProperty("status", "PENDING_STOP");
        Native117StoppedEntitiesSnapshot.write(runtime, first);
        Native117StoppedEntitiesSnapshot.write(runtime, next);
        assertTrue(Files.readString(runtime.resolve(Native117StoppedEntitiesSnapshot.FILE)).contains("PENDING_STOP"));
        try (java.util.stream.Stream<Path> files = Files.list(runtime)) { assertEquals(1, files.count()); }
    }

    @Test public void entrypointRegistersNativeStoppingHandlerAndRealEnumeration() throws Exception {
        assertNotNull(Native117StoppedEntitiesSnapshot.class.getDeclaredMethod("stopping", FMLServerStoppingEvent.class));
        Path sources = Path.of("src/main/java/zone/moddev/mc/advantageworks");
        assertTrue(Files.readString(sources.resolve("AdvantageWorksMod.java")).contains("Native117StoppedEntitiesSnapshot.register();"));
        String source = Files.readString(sources.resolve("Native117StoppedEntitiesSnapshot.java"));
        assertTrue(source.contains("world.getAllEntities()"));
        assertFalse(source.contains("canUpdate("));
        assertFalse(source.contains(".tick("));
        assertFalse(source.contains(".load("));
    }

    private static CompoundTag decode(JsonObject row) throws Exception {
        return NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(row.get("nbtBase64").getAsString())));
    }
}
