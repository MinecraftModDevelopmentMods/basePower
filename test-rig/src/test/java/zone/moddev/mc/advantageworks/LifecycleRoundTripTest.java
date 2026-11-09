package zone.moddev.mc.advantageworks;

import org.junit.Test;
import static org.junit.Assert.*;

public class LifecycleRoundTripTest {
    @Test public void oneSimultaneousStartAndMatchingEndComplete() {
        LifecycleRoundTrip window = new LifecycleRoundTrip(0);
        assertFalse(window.start(100, 1, false));
        assertEquals(0, window.mutations);
        assertTrue(window.start(101, 2, true));
        window.mutated();
        window.end(102, true);
        assertTrue(window.finished);
        assertNull(window.failure);
    }

    @Test public void noCallbackCannotBeReplacedWithClockAdvanceOrALaterTick() {
        for (long end : new long[]{100, 101, 108}) {
            LifecycleRoundTrip window = new LifecycleRoundTrip(0);
            window.start(100, 1, true); window.mutated();
            window.end(end, false);
            assertNotNull(window.failure);
            assertThrows(IllegalStateException.class, () -> window.end(109, true));
        }
    }

    @Test public void aLateSuccessfulCallbackCannotPassEvenWithValidDispatch() {
        LifecycleRoundTrip window = new LifecycleRoundTrip(0);
        window.start(100, 1, true); window.mutated(); window.end(108, true);
        assertNotNull(window.failure);
    }

    @Test public void duplicateStartMutationAndUnmatchedEndFail() {
        LifecycleRoundTrip window = new LifecycleRoundTrip(0);
        assertThrows(IllegalStateException.class, window::mutated);
        assertThrows(IllegalStateException.class, () -> window.end(101, true));
        window.start(100, 1, true); window.mutated();
        assertThrows(IllegalStateException.class, () -> window.start(101, 2, true));
        assertThrows(IllegalStateException.class, window::mutated);
    }

    @Test public void failedPrerequisitesAreBoundedWithoutAnyMutation() {
        LifecycleRoundTrip window = new LifecycleRoundTrip(0);
        for (int i = 0; i < LifecycleRoundTrip.MAX_STARTS; i++) assertFalse(window.start(i, i, false));
        assertFalse(window.start(200, 200, true));
        assertTrue(window.finished);
        assertNotNull(window.failure);
        assertEquals(0, window.mutations);
    }

    @Test public void elapsedLimitCannotBePassedByLateReadiness() {
        LifecycleRoundTrip window = new LifecycleRoundTrip(0);
        assertFalse(window.start(100, LifecycleRoundTrip.MAX_NANOS, true));
        assertTrue(window.finished);
        assertFalse(window.started);
        assertEquals(0, window.mutations);
    }
}
