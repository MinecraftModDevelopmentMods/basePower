package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTickList;
import org.junit.BeforeClass;
import org.junit.Test;

public class NativeEntityTickGateTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void actualNativeTickerIsHeldWithoutRemovingItsTrackingRegistration() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.OVERWORLD);
        java.lang.reflect.Field field = ServerLevel.class.getDeclaredField("entityTickList"); field.setAccessible(true);
        EntityTickList original = (EntityTickList) field.get(world);
        Boat first = new Boat(EntityType.BOAT, world), second = new Boat(EntityType.BOAT, world);
        original.add(first); original.add(second);
        NativeEntityHold hold = new NativeEntityHold();
        hold.hold(first);
        NativeEntityTickGate gate = NativeEntityTickGate.of(world);
        List<Entity> actual = new ArrayList<>(); gate.forEach(actual::add);
        assertEquals(Arrays.asList(second), actual);
        assertFalse(gate.contains(first));
        assertTrue("Native registration is retained", original.contains(first));
        hold.restore();
        actual.clear(); gate.forEach(actual::add);
        assertEquals(Arrays.asList(first, second), actual);
        assertTrue(gate.contains(first)); assertSame(world, first.level); assertFalse(first.isRemoved());
    }

    @Test public void joinBeforeNativeRegistrationIsStillHeldAndAllOtherOperationsDelegate() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.OVERWORLD);
        Boat first = new Boat(EntityType.BOAT, world), other = new Boat(EntityType.BOAT, world);
        NativeEntityHold hold = new NativeEntityHold(); hold.hold(first);
        NativeEntityTickGate gate = NativeEntityTickGate.of(world);
        gate.add(first); gate.add(other);
        List<Entity> actual = new ArrayList<>(); gate.forEach(actual::add);
        assertEquals(Arrays.asList(other), actual);
        gate.remove(other);
        actual.clear(); gate.forEach(actual::add); assertTrue(actual.isEmpty());
        hold.restore();
        gate.forEach(actual::add); assertEquals(Arrays.asList(first), actual);
    }

    @Test public void overlappingHoldsReleaseOnlyTheirOwnNativeGate() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.OVERWORLD);
        Boat boat = new Boat(EntityType.BOAT, world);
        NativeEntityTickGate gate = NativeEntityTickGate.of(world); gate.add(boat);
        NativeEntityHold first = new NativeEntityHold(), second = new NativeEntityHold();
        first.hold(boat); second.hold(boat);
        first.restore();
        assertFalse(boat.canUpdate()); assertFalse(gate.contains(boat));
        second.restore();
        assertTrue(boat.canUpdate()); assertTrue(gate.contains(boat));
    }
}
