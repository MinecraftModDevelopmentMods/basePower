package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;

import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.BeforeClass;
import org.junit.Test;

public class NativeActorIoTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void nativeValueIoRoundTripRetainsIdentityInventoryAndForgeData() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.NETHER);
        ItemEntity original = new ItemEntity(world, 8.5, 80, 8.5, new ItemStack(Items.DIRT, 7));
        original.getPersistentData().putString("witness", "keep exactly");
        CompoundTag saved = NativeActorIo.save(original);
        assertEquals("minecraft:item", saved.getString("id").orElseThrow());
        assertEquals(original.getUUID(), saved.read("UUID", UUIDUtil.CODEC).orElseThrow());
        assertFalse(NativeActorIo.saveWithoutId(original).contains("id"));
        ItemEntity loaded = new ItemEntity(net.minecraft.world.entity.EntityTypes.ITEM, world);
        NativeActorIo.load(loaded, saved);
        assertEquals(saved, NativeActorIo.save(loaded));
        assertEquals(7, loaded.getItem().getCount());
    }

    @Test public void rootRefusalDoesNotBecomeAPartialWitness() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.END);
        var root = Native117EntityContextTest.boat(world);
        var passenger = Native117EntityContextTest.boat(world);
        assertTrue(passenger.startRiding(root, true, false));
        assertThrows(IllegalStateException.class, () -> NativeActorIo.save(passenger));
        assertEquals(passenger.getUUID(), NativeActorIo.saveAsPassenger(passenger).read("UUID", UUIDUtil.CODEC).orElseThrow());
        assertSame(root, passenger.getVehicle());
    }

    @Test public void codecEncodeProblemThrowsWithItsNestedPath() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.OVERWORLD);
        ItemEntity broken = new ItemEntity(net.minecraft.world.entity.EntityTypes.ITEM, world) {
            @Override public void saveWithoutId(ValueOutput output) {
                output.child("outer").store("broken", Codec.intRange(0, 1), 2);
            }
        };
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> NativeActorIo.saveWithoutId(broken));
        assertTrue(error.getMessage(), error.getMessage().contains(".outer"));
        assertTrue(error.getMessage(), error.getMessage().contains("broken"));
    }

    @Test public void codecDecodeProblemCannotFallBackToAnEmptyPersistentCompound() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.OVERWORLD);
        ItemEntity item = new ItemEntity(world, 8.5, 80, 8.5, new ItemStack(Items.DIRT, 7));
        CompoundTag malformed = NativeActorIo.save(item);
        malformed.putString("ForgeData", "not a compound");
        RuntimeException error = assertThrows(RuntimeException.class, () -> NativeActorIo.load(item, malformed));
        assertTrue(error.toString(), causes(error).contains("Native actor persistence problem"));
        assertTrue(error.toString(), causes(error).contains("ForgeData"));
    }

    @Test public void reporterRejectsAPartialOutputInsteadOfReturningIt() {
        TagValueOutput output = TagValueOutput.createWithoutContext(NativeActorIo.reporter("control"));
        assertThrows(IllegalStateException.class, () -> output.store("bad", Codec.intRange(0, 1), 7));
        assertFalse(output.buildResult().contains("bad"));
    }

    private static String causes(Throwable error) {
        StringBuilder result = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) result.append(cause).append('\n');
        return result.toString();
    }
}
