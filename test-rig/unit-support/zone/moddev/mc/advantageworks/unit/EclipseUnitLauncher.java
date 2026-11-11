package zone.moddev.mc.advantageworks.unit;

import java.util.ArrayList;
import java.util.List;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/** Independent compiler qualification must not mistake assumptions for successful tests. */
public final class EclipseUnitLauncher {
    private EclipseUnitLauncher() {}

    public static void main(String[] args) throws ClassNotFoundException {
        int expected = Integer.parseInt(args[0]);
        List<Class<?>> classes = new ArrayList<>();
        for (int index = 1; index < args.length; index++) classes.add(Class.forName(args[index]));
        List<Failure> assumptions = new ArrayList<>();
        JUnitCore core = new JUnitCore();
        core.addListener(new RunListener() {
            @Override public void testAssumptionFailure(Failure failure) { assumptions.add(failure); }
        });
        Result result = core.run(classes.toArray(Class<?>[]::new));
        result.getFailures().forEach(failure -> System.err.println(failure.getTrace()));
        assumptions.forEach(failure -> System.err.println("Unexpected assumption: " + failure));
        if (expected < 1 || result.getRunCount() != expected || !result.wasSuccessful()
                || result.getIgnoreCount() != 0 || !assumptions.isEmpty()) {
            throw new AssertionError("Incomplete Eclipse-output suite: " + result.getRunCount()
                    + " tests, " + result.getFailureCount() + " failures, " + result.getIgnoreCount()
                    + " ignored, " + assumptions.size() + " assumptions; expected " + expected);
        }
        System.out.println("PASS: " + expected + " tests; zero failures, ignored tests or assumptions");
    }
}
