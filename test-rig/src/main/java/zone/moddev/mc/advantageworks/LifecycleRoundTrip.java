package zone.moddev.mc.advantageworks;

/** One mutation window, bounded by native phases rather than machine outcomes. */
final class LifecycleRoundTrip {
    static final String PROTOCOL = "native117-lifecycle-first-callback-v1";
    static final long MAX_NANOS = 20_000_000_000L;
    static final int MAX_STARTS = 160;
    final long requestedNanos;
    int attempts;
    int mutations;
    long startWorldTime;
    boolean started, finished;
    String failure;

    LifecycleRoundTrip(long now) { requestedNanos = now; }

    boolean start(long worldTime, long now, boolean ready) {
        if (started || finished) throw new IllegalStateException("Round trip already started or finished");
        attempts++;
        if (now - requestedNanos >= MAX_NANOS || attempts > MAX_STARTS) {
            fail("Simultaneous native START prerequisites timed out");
            return false;
        }
        if (!ready) return false;
        started = true;
        startWorldTime = worldTime;
        return true;
    }

    void mutated() {
        if (!started || finished || mutations != 0) throw new IllegalStateException("Duplicate or unstarted mutation");
        mutations++;
    }

    void end(long worldTime, boolean firstCallbacksComplete) {
        if (!started || finished) throw new IllegalStateException("Unmatched END");
        if (worldTime != startWorldTime + 1 || mutations != 1 || !firstCallbacksComplete) {
            fail("Matching first natural tile callbacks were not completed");
        } else {
            finished = true;
        }
    }

    void fail(String reason) { failure = reason; finished = true; }
}
