package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import org.junit.BeforeClass;
import org.junit.Test;

public class Native117JoiningHoldTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    }

    @Test public void joiningActorIsHeldBeforeItCanEnterTheNativeTickList() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.OVERWORLD);
        Boat boat = new Boat(EntityType.BOAT, world);
        boat.tickCount = 17;
        UUID identity = boat.getUUID();
        MachineOperationalProbe probe = new MachineOperationalProbe();
        Method joining = MachineOperationalProbe.class.getDeclaredMethod("entityJoining", EntityJoinLevelEvent.class);
        joining.setAccessible(true);
        joining.invoke(probe, new EntityJoinLevelEvent(boat, world));
        assertFalse(boat.canUpdate());
        // Repeated START enumeration must not replace the pre-join state.
        Field field = MachineOperationalProbe.class.getDeclaredField("heldEntities"); field.setAccessible(true);
        NativeEntityHold hold = (NativeEntityHold) field.get(probe);
        hold.hold(boat);
        hold.restore();
        assertTrue(boat.canUpdate());
        assertEquals(17, boat.tickCount);
        assertEquals(identity, boat.getUUID());
        assertSame(world, boat.level());
        assertFalse(boat.isRemoved());
    }

    @Test public void nativeJoinWhileMeasuringIsNotFrozen() throws Exception {
        ServerLevel world = Native117EntityContextTest.world(Level.OVERWORLD);
        Boat boat = new Boat(EntityType.BOAT, world);
        MachineOperationalProbe probe = new MachineOperationalProbe();
        Field paused = MachineOperationalProbe.class.getDeclaredField("paused"); paused.setAccessible(true);
        paused.setBoolean(probe, false);
        Method joining = MachineOperationalProbe.class.getDeclaredMethod("entityJoining", EntityJoinLevelEvent.class);
        joining.setAccessible(true);
        joining.invoke(probe, new EntityJoinLevelEvent(boat, world));
        assertTrue(boat.canUpdate());
    }

    @Test public void previouslyDisabledActorRemainsDisabledAfterHold() throws Exception {
        Boat boat = new Boat(EntityType.BOAT, Native117EntityContextTest.world(Level.OVERWORLD));
        boat.canUpdate(false);
        NativeEntityHold hold = new NativeEntityHold();
        hold.hold(boat); hold.hold(boat); hold.restore();
        assertFalse(boat.canUpdate());
    }

    @Test public void nativeTickDriftCannotBeHiddenByRestoringTheCounter() throws Exception {
        Boat boat = new Boat(EntityType.BOAT, Native117EntityContextTest.world(Level.OVERWORLD));
        NativeEntityHold hold = new NativeEntityHold();
        hold.hold(boat);
        boat.tickCount++;
        try { hold.restore(); fail("Native tick drift was erased"); }
        catch (AssertionError expected) { assertTrue(expected.getMessage().contains("ticked while held")); }
        assertEquals(1, boat.tickCount);
    }
}
