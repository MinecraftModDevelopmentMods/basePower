package zone.moddev.mc.poweradvantage.verification.native117;

import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;

/** Negative controls for the verification reporter, never production tests. */
public final class AuditedPowerJUnit117Control {
    public static final class Assumed {
        @Test public void mustNotBeReportedAsPassing() {
            Assume.assumeTrue(false);
        }
    }

    @Ignore("The verifier must reject ignored tests")
    public static final class Ignored {
        @Test public void mustNotRun() {
            throw new AssertionError("Ignored fixture executed");
        }
    }
}
