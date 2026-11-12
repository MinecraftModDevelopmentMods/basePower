package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class NativeEntityReadSelectorsTest {
    @Parameterized.Parameters(name = "{0}-{1}") public static Collection<Object[]> cases() {
        List<Object[]> cases = new ArrayList<>();
        for (String selector : new String[]{"actor", "dimension", "station"}) {
            for (String data : new String[]{"absent", "empty", "unrelated", "wrongType", "tagged", "otherValue"}) {
                cases.add(new Object[]{selector, data});
            }
        }
        return cases;
    }

    @BeforeClass public static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private final String selector;
    private final String scenario;

    public NativeEntityReadSelectorsTest(String selector, String scenario) {
        this.selector = selector;
        this.scenario = scenario;
    }

    @Test public void selectionLeavesExactLiveAndSavedStateUntouched() throws Exception {
        ObservedBoat entity = new ObservedBoat(Native117EntityContextTest.world(Level.OVERWORLD));
        Field field = Entity.class.getDeclaredField("persistentData");
        field.setAccessible(true);
        String key = selector.equals("actor") ? "AdvWorksActor"
                : selector.equals("dimension") ? "AdvWorksDimensionEntity" : "AdvantageWorksStation";
        CompoundTag data = scenario.equals("absent") ? null : new CompoundTag();
        if (scenario.equals("unrelated") || scenario.equals("tagged") || scenario.equals("otherValue")) {
            data.putLong("PrivateLong", 987654321L);
            CompoundTag nested = new CompoundTag();
            nested.putIntArray("Array", new int[]{2, 3, 5});
            data.put("PrivateNested", nested);
        }
        if (scenario.equals("wrongType")) data.putInt(key, 42);
        if (scenario.equals("tagged")) data.putString(key, "station");
        if (scenario.equals("otherValue")) data.putString(key, "another station");
        field.set(entity, data);
        entity.persistentReads = 0;
        CompoundTag before = NativeActorIo.saveWithoutId(entity).copy();
        int ticks = entity.tickCount;
        for (int observation = 0; observation < 2; observation++) {
            int serializations = entity.serializations;
            boolean selected;
            if (selector.equals("actor")) selected = ActorProbe.witnessed(entity);
            else if (selector.equals("station")) selected = WorksController.StationOwnership.matches(entity, "station");
            else {
                Method method = DimensionEntityProbe.class.getDeclaredMethod("witnessed", Entity.class);
                method.setAccessible(true);
                selected = (boolean) method.invoke(null, entity);
            }
            boolean expected = scenario.equals("tagged") || (scenario.equals("otherValue") && !selector.equals("station"));
            assertEquals(expected, selected);
            assertSame("Rejected and accepted entities must retain the exact persistent-data field", data, field.get(entity));
            assertEquals("Read selectors must never call the allocating getter", 0, entity.persistentReads);
            assertEquals("Selection must not serialize an entity or invoke its save callbacks", serializations, entity.serializations);
            assertEquals(before, NativeActorIo.saveWithoutId(entity));
            assertEquals(data != null, before.contains("ForgeData"));
            assertEquals(ticks, entity.tickCount);
            assertFalse(entity.isRemoved());
        }
    }

    private static final class ObservedBoat extends Boat {
        int persistentReads;
        int serializations;
        ObservedBoat(Level world) { super(net.minecraft.world.entity.EntityTypes.OAK_BOAT, world, () -> net.minecraft.world.item.Items.OAK_BOAT); }
        @Override public CompoundTag getPersistentData() { persistentReads++; return super.getPersistentData(); }
        @Override public void saveWithoutId(ValueOutput output) { serializations++; super.saveWithoutId(output); }
    }
}
