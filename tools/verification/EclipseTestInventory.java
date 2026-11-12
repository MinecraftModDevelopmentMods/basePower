import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/** Rejects a filtered Gradle result before calling it a complete independent compiler test. */
public final class EclipseTestInventory {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("classes, inventory file, expected test count");
        Set<String> declared = new TreeSet<>(Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8));
        if (declared.isEmpty() || declared.contains("")) throw new AssertionError("Empty Gradle test inventory");
        Set<String> compiled = new TreeSet<>();
        int tests = 0;
        try (var paths = Files.walk(Path.of(args[0]))) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".class")).toList()) {
                ClassNode type = new ClassNode();
                new ClassReader(Files.readAllBytes(file)).accept(type,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                if (has(type.visibleAnnotations, "Lorg/junit/runner/RunWith;")
                        || "junit/framework/TestCase".equals(type.superName)) {
                    throw new AssertionError("Review custom JUnit discovery before qualification: " + type.name);
                }
                int methods = (int) type.methods.stream()
                        .filter(method -> has(method.visibleAnnotations, "Lorg/junit/Test;")).count();
                if (methods == 0) continue;
                if (type.name.contains("$") || (type.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0
                        || !"java/lang/Object".equals(type.superName)) {
                    throw new AssertionError("Review nested or inherited JUnit discovery: " + type.name);
                }
                compiled.add(type.name.replace('/', '.'));
                tests += methods;
            }
        }
        if (!compiled.equals(declared) || tests != Integer.parseInt(args[2])) {
            Set<String> omitted = new TreeSet<>(compiled);
            omitted.removeAll(declared);
            Set<String> stale = new TreeSet<>(declared);
            stale.removeAll(compiled);
            throw new AssertionError("Incomplete or stale Gradle inventory: omitted=" + omitted + ", stale="
                    + stale + ", compiled tests=" + tests + ", declared tests=" + args[2]);
        }
        System.out.println("PASS: complete compiled JUnit inventory; " + compiled.size() + " suites, " + tests + " tests");
    }

    private static boolean has(java.util.List<AnnotationNode> annotations, String descriptor) {
        return annotations != null && annotations.stream().anyMatch(annotation -> descriptor.equals(annotation.desc));
    }
}
