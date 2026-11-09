package zone.moddev.mc.poweradvantage.verification.native117;

import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.junit.internal.TextListener;
import org.junit.runner.JUnitCore;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/** Reports skipped and assumed tests separately from JUnit's success exit code. */
public final class AuditedPowerJUnit117 {
    private AuditedPowerJUnit117() {}

    public static void main(String[] args) throws Exception {
        int expected = Integer.parseInt(args[1]);
        Class<?>[] suites = new Class<?>[args.length - 2];
        for (int i = 0; i < suites.length; i++) suites[i] = Class.forName(args[i + 2]);
        final int[] assumptions = {0};
        final int[] duplicateStarts = {0};
        final Map<String, Description> started = new LinkedHashMap<>();
        final Map<String, Description> finished = new LinkedHashMap<>();
        JUnitCore core = new JUnitCore();
        core.addListener(new TextListener(System.out));
        core.addListener(new RunListener() {
            @Override public void testStarted(Description test) {
                if (started.put(test.getDisplayName(), test) != null) duplicateStarts[0]++;
            }
            @Override public void testFinished(Description test) {
                finished.put(test.getDisplayName(), test);
            }
            @Override public void testAssumptionFailure(Failure failure) { assumptions[0]++; }
        });
        Result result = core.run(suites);
        boolean passed = result.wasSuccessful() && result.getRunCount() == expected
                && result.getIgnoreCount() == 0 && assumptions[0] == 0 && duplicateStarts[0] == 0
                && started.size() == expected && started.keySet().equals(finished.keySet());
        try (FileOutputStream output = new FileOutputStream(args[0])) {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("testsuite");
            xml.writeAttribute("name", "PowerAdvantage-1.17.1-ECJ");
            xml.writeAttribute("tests", Integer.toString(result.getRunCount()));
            xml.writeAttribute("failures", Integer.toString(result.getFailureCount()));
            xml.writeAttribute("ignored", Integer.toString(result.getIgnoreCount()));
            xml.writeAttribute("assumptions", Integer.toString(assumptions[0]));
            xml.writeAttribute("expected", Integer.toString(expected));
            xml.writeAttribute("passed", Boolean.toString(passed));
            xml.writeAttribute("duplicateStarts", Integer.toString(duplicateStarts[0]));
            for (Map.Entry<String, Description> entry : started.entrySet()) {
                Description test = entry.getValue();
                xml.writeStartElement("testcase");
                xml.writeAttribute("classname", test.getClassName());
                xml.writeAttribute("name", test.getMethodName());
                xml.writeAttribute("finished", Boolean.toString(finished.containsKey(entry.getKey())));
                xml.writeEndElement();
            }
            for (Failure failure : result.getFailures()) {
                xml.writeStartElement("failure");
                xml.writeAttribute("test", failure.getDescription().getDisplayName());
                xml.writeCharacters(failure.getTrace());
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        }
        System.out.println("AUDITED_JUNIT tests=" + result.getRunCount() + " expected=" + expected
                + " failures=" + result.getFailureCount() + " ignored=" + result.getIgnoreCount()
                + " assumptions=" + assumptions[0] + " passed=" + passed);
        if (!passed) System.exit(1);
    }
}
