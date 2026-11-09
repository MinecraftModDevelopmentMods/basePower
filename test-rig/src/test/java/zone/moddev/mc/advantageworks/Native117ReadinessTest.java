package zone.moddev.mc.advantageworks;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native117ReadinessTest {
    private Native117Readiness.Observation observation() {
        return new Native117Readiness.Observation(new Native117Readiness.Bounds(74, 63, 75, 64), 10, 0);
    }

    private List<Native117Readiness.State> states(boolean loaded, boolean ticking) {
        return Arrays.asList(new Native117Readiness.State(74, 63, loaded, ticking),
                new Native117Readiness.State(74, 64, loaded, ticking),
                new Native117Readiness.State(75, 63, loaded, ticking),
                new Native117Readiness.State(75, 64, loaded, ticking));
    }

    @Test public void bothNativeReadinessFactsAreRequiredBeforeCompletion() {
        Native117Readiness.Observation check = observation();
        assertFalse(check.observe(11, 1, states(true, false)));
        assertFalse(check.observe(12, 2, states(false, true)));
        assertThrows(IllegalStateException.class, check::report);
        assertTrue(check.observe(13, 3, states(true, true)));
        assertTrue(check.passed);
        assertEquals("PASS", check.report().get("status").getAsString());
        assertEquals(3, check.report().get("checks").getAsInt());
    }

    @Test public void oneUnreadyBoundaryChunkPreventsAReadyRectangle() {
        Native117Readiness.Observation check = observation();
        List<Native117Readiness.State> rows = states(true, true);
        rows.set(3, new Native117Readiness.State(75, 64, true, false));
        assertFalse(check.observe(11, 1, rows));
        assertTrue(check.observe(12, 2, states(true, true)));
        assertEquals(2, check.report().getAsJsonArray("transitions").size());
        assertEquals(1, check.report().getAsJsonArray("transitions").get(1).getAsJsonObject()
                .getAsJsonArray("changes").size());
    }

    @Test public void unchangedObservationsRetainOnlyActualTransitions() {
        Native117Readiness.Observation check = observation();
        assertFalse(check.observe(11, 1, states(false, false)));
        assertFalse(check.observe(12, 2, states(false, false)));
        assertTrue(check.observe(13, 3, states(true, true)));
        assertEquals(2, check.report().getAsJsonArray("transitions").size());
        assertEquals(3, check.report().getAsJsonArray("transitions").get(1).getAsJsonObject().get("check").getAsInt());
    }

    @Test public void alreadyReadyNativeObservationCanFinishOnTheFirstEndCheck() {
        Native117Readiness.Observation check = observation();
        assertTrue(check.observe(11, 1, states(true, true)));
        assertEquals(1, check.report().get("checks").getAsInt());
        assertEquals(4, check.report().getAsJsonArray("chunks").size());
        assertThrows(IllegalStateException.class, () -> check.observe(12, 2, states(true, true)));
    }

    @Test public void naturalCheckLimitReportsFailureNotFalseCompletion() {
        Native117Readiness.Observation check = observation();
        for (int tick = 1; tick < Native117Readiness.MAX_CHECKS; tick++) {
            assertFalse(check.observe(10 + tick, tick, states(false, false)));
        }
        assertTrue(check.observe(1210, 1200, states(false, false)));
        assertFalse(check.passed);
        assertEquals("FAIL", check.report().get("status").getAsString());
        assertEquals(1200, check.report().get("checks").getAsInt());
    }

    @Test public void wallClockLimitRefusesLateReadySamplesAsWellAsUnreadySamples() {
        for (boolean ready : new boolean[]{false, true}) {
            Native117Readiness.Observation check = observation();
            assertTrue(check.observe(11, Native117Readiness.MAX_WAIT_NANOS + 1, states(ready, ready)));
            assertFalse(check.passed);
            assertEquals("FAIL", check.report().get("status").getAsString());
        }
    }

    @Test public void missingDuplicateAndOutOfOrderChunksCannotSatisfyTheGate() {
        Native117Readiness.Observation check = observation();
        assertThrows(IllegalArgumentException.class, () -> check.observe(11, 1, Collections.emptyList()));
        List<Native117Readiness.State> rows = states(true, true);
        rows.set(3, rows.get(0));
        assertThrows(IllegalArgumentException.class, () -> check.observe(11, 1, rows));
        assertEquals(0, check.checks);
    }

    @Test public void boundsEnforceWorldCoordinatesSizeAndOrder() {
        assertEquals(256, new Native117Readiness.Bounds(0, 0, 15, 15).size());
        assertEquals(4, new Native117Readiness.Bounds(74, 63, 75, 64).size());
        for (int[] bounds : new int[][]{{0, 0, 16, 15}, {0, 0, 64, 0}, {1, 0, 0, 0},
                {-1875001, 0, -1875001, 0}, {1875000, 0, 1875000, 0}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Native117Readiness.Bounds(bounds[0], bounds[1], bounds[2], bounds[3]));
        }
    }
}
