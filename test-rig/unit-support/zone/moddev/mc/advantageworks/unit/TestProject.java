package zone.moddev.mc.advantageworks.unit;

import java.nio.file.Path;

/** Native test runners may use a separate game directory; source fixtures stay in the checkout. */
public final class TestProject {
    private TestProject() { }
    public static Path path(String first, String... rest) {
        Path relative = Path.of(first, rest);
        if (relative.isAbsolute()) return relative;
        String root = System.getProperty("advantage.test.projectDir");
        if (root == null) throw new IllegalStateException("Missing explicit source-fixture root");
        return Path.of(root).resolve(relative);
    }
}
