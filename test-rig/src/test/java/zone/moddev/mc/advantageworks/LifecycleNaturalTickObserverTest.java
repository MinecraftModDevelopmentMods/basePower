package zone.moddev.mc.advantageworks;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.Test;
import static org.junit.Assert.*;

public class LifecycleNaturalTickObserverTest {
    private Map<String, Object> state(int progress) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("progress", progress); result.put("steam", 0);
        return result;
    }

    @Test public void firstFailedMachineStateIsNeverReplacedByLaterRepair() {
        LifecycleNaturalTickObserver.Capture capture = new LifecycleNaturalTickObserver.Capture();
        boolean first = capture.enter(101, true, true, Arrays.asList("native"), state(227));
        Map<String, Object> failed = state(227);
        capture.exit(first, failed);
        assertTrue(capture.complete());
        boolean later = capture.enter(108, true, true, Arrays.asList("native"), state(0));
        capture.exit(later, state(0));
        assertSame(failed, capture.after);
        assertEquals(101, capture.worldTime);
        assertFalse(capture.complete());
    }

    @Test public void outerWrapperOrWrongEligibilityCannotClaimInnerDispatch() {
        for (boolean[] facts : new boolean[][]{{false, false}, {true, false}, {false, true}}) {
            LifecycleNaturalTickObserver.Capture capture = new LifecycleNaturalTickObserver.Capture();
            capture.exit(capture.enter(101, facts[0], facts[1], Arrays.asList("outer"), state(227)), state(227));
            assertFalse(capture.complete());
        }
    }

    @Test public void enteringWithoutCompletingDoesNotProveACallback() {
        LifecycleNaturalTickObserver.Capture capture = new LifecycleNaturalTickObserver.Capture();
        capture.enter(101, true, true, Arrays.asList("native"), state(227));
        assertFalse(capture.complete());
        assertNull(capture.after);
    }

    @Test public void zeroEnergyAndUnchangedProgressAreNotTransportSelectionCriteria() {
        LifecycleNaturalTickObserver.Capture capture = new LifecycleNaturalTickObserver.Capture();
        capture.exit(capture.enter(101, true, true, Arrays.asList("native"), state(227)), state(227));
        assertTrue(capture.complete());
        assertEquals(227, capture.after.get("progress"));
        assertEquals(0, capture.after.get("steam"));
    }

    @Test public void exactNativeInnerBindingLayoutIsPresent() {
        boolean wrapper = false, bound = false;
        for (Class<?> type : LevelChunk.class.getDeclaredClasses()) {
            if (!TickingBlockEntity.class.isAssignableFrom(type)) continue;
            int registrations = 0, delegates = 0, tiles = 0;
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() == TickingBlockEntity.class) registrations++;
                if (field.getType() == BlockEntityTicker.class) delegates++;
                if (field.getType() == BlockEntity.class) tiles++;
            }
            wrapper |= registrations == 1;
            bound |= delegates == 1 && tiles == 1;
        }
        assertTrue(wrapper);
        assertTrue(bound);
    }

    @Test public void sourceConstructorAndOtherLevelFramesDoNotProveNativeTickDispatch() {
        String level = "net.minecraft.world.level.Level";
        for (String method : Arrays.asList("<init>", "setBlock", "getBlockEntity", "tick")) {
            assertFalse(LifecycleNaturalTickObserver.nativeLoopFrame(new StackTraceElement(level, method, "Level.java", 1)));
        }
        assertTrue(LifecycleNaturalTickObserver.nativeLoopFrame(new StackTraceElement(level, "tickBlockEntities", "Level.java", 1)));
        assertTrue(LifecycleNaturalTickObserver.nativeLoopFrame(new StackTraceElement(level, "m_46463_", "Level.java", 1)));
        assertFalse(LifecycleNaturalTickObserver.nativeLoopFrame(new StackTraceElement("Harness", "tickBlockEntities", "Test.java", 1)));
    }

    @Test public void observersCannotConstructProductionTilesOrForceADispatch() throws Exception {
        String probe = Files.readString(Paths.get("src/main/java/zone/moddev/mc/advantageworks/LifecycleProbe.java"));
        String observer = Files.readString(Paths.get("src/main/java/zone/moddev/mc/advantageworks/LifecycleNaturalTickObserver.java"));
        assertTrue(probe.contains("queueRoundTrip"));
        assertTrue(probe.contains("LevelChunk.EntityCreationType.CHECK"));
        assertFalse(probe.contains("tile.tick("));
        assertFalse(probe.contains("new SteamDrill"));
        assertFalse(probe.contains("new ElectricDrill"));
        assertFalse(observer.contains("new SteamDrill"));
        assertEquals(1, observer.split("original\\.tick\\(", -1).length - 1);
        assertTrue(observer.contains("frame.getClassName().equals(bound.getClass().getName())"));
        assertTrue(observer.contains("frame.getClassName().equals(Level.class.getName())"));
    }
}
