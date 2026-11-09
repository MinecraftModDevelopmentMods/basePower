package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import org.junit.BeforeClass;
import org.junit.Test;

public class Native117EntityContextTest {
    @BeforeClass public static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test public void observesActualRegistryKeyAndWorldIdentity() throws Exception {
        ServerLevel world = world(Level.NETHER);
        Boat boat = new Boat(EntityType.BOAT, world);
        JsonObject actual = Native117EntityContext.observe(boat, world);
        assertEquals("minecraft:the_nether", actual.get("worldDimensionKey").getAsString());
        assertTrue(actual.get("sameWorld").getAsBoolean());
        assertEquals("minecraft:the_nether", actual.getAsJsonObject("entityWorlds").get(boat.getUUID().toString()).getAsString());
    }

    @Test public void observesEndWithoutAnyExpectedFixtureDimension() throws Exception {
        ServerLevel world = world(Level.END);
        Boat boat = new Boat(EntityType.BOAT, world);
        assertEquals("minecraft:the_end", Native117EntityContext.observe(boat, world).get("worldDimensionKey").getAsString());
    }

    @Test public void recordsActualPassengerWorldAndUuid() throws Exception {
        ServerLevel world = world(Level.NETHER);
        Boat vehicle = new Boat(EntityType.BOAT, world), passenger = new Boat(EntityType.BOAT, world);
        assertTrue(passenger.startRiding(vehicle, true));
        JsonObject actual = Native117EntityContext.observe(vehicle, world).getAsJsonObject("entityWorlds");
        assertEquals(2, actual.entrySet().size());
        assertEquals("minecraft:the_nether", actual.get(passenger.getUUID().toString()).getAsString());
    }

    @Test public void rejectsDifferentWorldInstanceEvenWithSameRegistryKey() throws Exception {
        ServerLevel actual = world(Level.NETHER), other = world(Level.NETHER);
        reject(new Boat(EntityType.BOAT, actual), other);
    }

    @Test public void rejectsPassengerWithDifferentActualWorld() throws Exception {
        ServerLevel world = world(Level.NETHER);
        Boat vehicle = new Boat(EntityType.BOAT, world), passenger = new Boat(EntityType.BOAT, world);
        assertTrue(passenger.startRiding(vehicle, true));
        passenger.level = world(Level.END);
        reject(vehicle, world);
    }

    @Test public void rejectsDuplicatePassengerUuid() throws Exception {
        ServerLevel world = world(Level.NETHER);
        Boat vehicle = new Boat(EntityType.BOAT, world), passenger = new Boat(EntityType.BOAT, world);
        passenger.setUUID(vehicle.getUUID());
        assertTrue(passenger.startRiding(vehicle, true));
        reject(vehicle, world);
    }

    @Test public void observationDoesNotChangeEntityIdentityOrPassengers() throws Exception {
        ServerLevel world = world(Level.NETHER);
        Boat boat = new Boat(EntityType.BOAT, world);
        UUID before = boat.getUUID();
        Native117EntityContext.observe(boat, world);
        assertEquals(before, boat.getUUID());
        assertTrue(boat.getPassengers().isEmpty());
    }

    @Test public void rejectsMissingContext() throws Exception {
        reject(null, world(Level.NETHER));
    }

    private static void reject(Boat entity, ServerLevel world) {
        try { Native117EntityContext.observe(entity, world); fail("Invalid native context accepted"); }
        catch (IllegalStateException expected) { }
    }

    static ServerLevel world(ResourceKey<Level> key) throws Exception {
        zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries.registerVanillaFluidTypes();
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        ServerLevel world = (ServerLevel) unsafe.getMethod("allocateInstance", Class.class)
                .invoke(field.get(null), EntityFixtureWorld.class);
        set(world, "dimension", key);
        set(world, "random", net.minecraft.util.RandomSource.create(0));
        set(world, "profiler", (Supplier<ProfilerFiller>) () -> InactiveProfiler.INSTANCE);
        Field ticks = ServerLevel.class.getDeclaredField("entityTickList"); ticks.setAccessible(true);
        ticks.set(world, new net.minecraft.world.level.entity.EntityTickList());
        return world;
    }

    private static void set(ServerLevel world, String name, Object value) throws Exception {
        Field field = Level.class.getDeclaredField(name); field.setAccessible(true); field.set(world, value);
    }

    private static final class EntityFixtureWorld extends ServerLevel {
        private EntityFixtureWorld() { super(null, null, null, null, null, null, null, false, 0L, java.util.List.of(), false); }

        @Override public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() {
            return net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
        }

        @Override public void gameEvent(net.minecraft.world.level.gameevent.GameEvent event,
                net.minecraft.world.phys.Vec3 pos, net.minecraft.world.level.gameevent.GameEvent.Context context) {
            // This isolated entity fixture has no chunks or game-event listeners.
        }
    }
}
