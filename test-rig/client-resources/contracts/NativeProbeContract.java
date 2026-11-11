import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Static native API-adapter drift checks, separate from the real client pixel tests. */
public final class NativeProbeContract {
    private static final String HISTORICAL = "c310d0ca63ad9a8408dd4bba75172afafa5c71b7";
    private static final String INHERITED = "398439074a60bf9b8f8872f92485cffc4aa96be9";
    private static final String PREFIX = "test-rig/client-resources/";
    private static final String JAVA = "java/zone/moddev/mc/advantageworks/client/";
    private static final Map<String, String> HISTORICAL_HASHES = Map.of(
            "ClientResourceProbe", "014E12FFFD8B1E52FF32FA55ABCD6A1708094AF00D85AFB2F1424D448E349453",
            "TankVisualProbe", "666F6D8FC5F1B925665E019E4F38DAB90766A04CB64C9C256AD3BAC9BE6CF327",
            "OilRenderProbe", "E99F2D29D58F5388406FB29F419D68FF02C3C9EB51A44018C9A3440E83F37777",
            "RecipeBookGroupingProbe", "E30466B80CB0496E637B3C6AAC9D490EF55C8E0FE0EBC5ACAEF7EF90D8E9C1B1",
            "MachineGuiLayoutProbe", "8D89215AEFFDDC96A7175AE84F315DFF009D6E0F0FC1942C986E626B5806AFFF",
            "ChildRendererGeometryProbe", "B56A5F683FCC1312D3B749BEC11C57099ECB73E5C87C953EF55050EAE3320650");
    private static final Map<String, String> INHERITED_HASHES = Map.of(
            "ClientResourceProbe", "C8FDC4A003FE4749C8DC8D882CF1BD5B1E56F2BD235D83531F2B6D651037E4ED",
            "TankVisualProbe", "2725D39B037F73347C9CFBAB5A0621A004754A3BDB1B6B66CD7CF9DEB2F66A75",
            "OilRenderProbe", "7D444AD5B6A2B47BAB208807AB57BE2B1740E8014D1A0D299497002782316AA1",
            "RecipeBookGroupingProbe", "A4ED7890B461612FDECD415DB53C45FF2557B379CFFF0BFCB02A7C4022618AFF",
            "MachineGuiLayoutProbe", "5A034F4A09AE1FC5CFD6BBB6AB27088BE2317CE64C6783D5B409A130E3EB6096",
            "ChildRendererGeometryProbe", "389DF44FFDF8AE897849D5492A1818C2710E7EDB9F1C5ACA47518E7D420E1C1C");
    private static final Map<String, List<String>> UNCHANGED = Map.of(
            "ClientResourceProbe", List.of("included", "verifyNetworkBootstrap", "require"),
            "TankVisualProbe", List.of("verify", "registeredDefinition", "syncTank", "compare", "require"),
            "OilRenderProbe", List.of("requireBucket", "blockAt", "require"),
            "MachineGuiLayoutProbe", List.of("verifySlots", "populate", "labelError", "verifyInventoryRows",
                    "verifyNoTextOverArtwork", "verifyMissingLabelControls", "verifyHelperLimits", "require"),
            "ChildRendererGeometryProbe", List.of("steam", "drill", "turret", "mesh", "beam", "valueOfType",
                    "set", "different", "ranges", "min", "max", "run", "close", "require"));
    private static final Pattern TOKENS = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*|\\s+");
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("repository target rules inventory|verify");
        require(canonical("alpha /* comment */ beta \"space kept\" ' ' // tail\n gamma")
                .equals("alphabeta\"space kept\"' 'gamma"), "Canonical source lexer changed literal/comment boundaries");
        require(!canonical("\"a b\"").equals(canonical("\"ab\"")), "Canonical source lexer erased a string literal space");
        Path repository = Path.of(args[0]), target = Path.of(args[1]);
        Properties rules = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[2]), StandardCharsets.UTF_8)) { rules.load(reader); }
        boolean inventory = args[3].equals("inventory");
        Map<String, String> inherited = new LinkedHashMap<>();
        for (String name : INHERITED_HASHES.keySet().stream().sorted().toList()) {
            reference(repository, HISTORICAL, JAVA + name + ".java", HISTORICAL_HASHES.get(name));
            inherited.put(name, reference(repository, INHERITED, JAVA + name + ".java", INHERITED_HASHES.get(name)));
        }
        int methods = 0;
        for (String name : UNCHANGED.keySet().stream().sorted().toList()) {
            Map<String, String> before = methods(name, inherited.get(name));
            Map<String, String> after = methods(name, Files.readString(target.resolve(JAVA + name + ".java")));
            for (String method : UNCHANGED.get(name)) {
                String key = name + "." + method;
                require(before.containsKey(key) && after.containsKey(key), "Missing unchanged oracle method: " + key);
                require(canonical(before.get(key)).equals(canonical(after.get(key))), "Changed inherited oracle method: " + key);
                methods++;
            }
        }
        for (String name : List.of("ClientResourceProbe", "TankVisualProbe", "OilRenderProbe", "RecipeBookGroupingProbe",
                "MachineGuiLayoutProbe", "ChildRendererGeometryProbe", "NativeGuiProjection")) {
            String source = Files.readString(target.resolve(JAVA + name + ".java"));
            String fingerprint = hash(canonical(source));
            if (inventory) System.out.println(name + "=" + fingerprint);
            else require(fingerprint.equals(rules.getProperty(name)), name + " changed beyond explicit native adapters");
        }
        String labels = reference(repository, HISTORICAL, "resources/gui-label-expectations.json",
                "EC0B66B926053E90C216D856B5F33C19D5B7E92DBC4C9E2E25812B401AEBAC0E");
        labels = labels.replace("poweradvantage:", "basepower:").replace("steamadvantage:", "basesteam:")
                .replace("electricadvantage:", "baseelectric:");
        require(labels.replace("\r\n", "\n").equals(Files.readString(target.resolve("resources/gui-label-expectations.json")).replace("\r\n", "\n")),
                "Independent human-facing caption expectations changed");
        System.out.println("PASS: " + checks + " static native API-adapter checks; " + methods
                + " complete oracle methods unchanged from SHA-bound inherited source. Historical hashes and captions retained. No Minecraft client was run.");
    }

    private static String reference(Path root, String commit, String path, String expected) throws Exception {
        Process process = new ProcessBuilder("git", "-C", root.toString(), "show", commit + ":" + PREFIX + path).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor() == 0, "Cannot read immutable source " + commit + ":" + path + ": " + error);
        require(hash(text).equals(expected), "Immutable source SHA-256 mismatch: " + commit + ":" + path);
        return text;
    }

    private static Map<String, String> methods(String name, String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "The native source contract requires a full JDK");
        var file = new SimpleJavaFileObject(URI.create("string:///" + name + ".java"), javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source; }
        };
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none", "--release", "21"), null, List.of(file));
            var positions = Trees.instance(task).getSourcePositions();
            Map<String, String> result = new LinkedHashMap<>();
            for (CompilationUnitTree unit : task.parse()) new TreePathScanner<Void, Void>() {
                private final List<String> owners = new ArrayList<>();
                @Override public Void visitClass(ClassTree tree, Void unused) {
                    owners.add(tree.getSimpleName().toString());
                    super.visitClass(tree, unused);
                    owners.removeLast();
                    return null;
                }
                @Override public Void visitMethod(MethodTree tree, Void unused) {
                    if (tree.getBody() != null) {
                        String key = String.join(".", owners) + "." + tree.getName();
                        int start = (int) positions.getStartPosition(unit, tree.getBody());
                        int end = (int) positions.getEndPosition(unit, tree.getBody());
                        if (result.putIfAbsent(key, source.substring(start, end)) != null) result.put(key, "AMBIGUOUS_OVERLOAD");
                    }
                    return super.visitMethod(tree, unused);
                }
            }.scan(unit, null);
            return result;
        }
    }

    private static String canonical(String source) {
        var matcher = TOKENS.matcher(source);
        StringBuilder result = new StringBuilder();
        int previous = 0;
        while (matcher.find()) {
            result.append(source, previous, matcher.start());
            String token = matcher.group();
            if (token.startsWith("\"") || token.startsWith("'")) result.append(token);
            previous = matcher.end();
        }
        return result.append(source, previous, source.length()).toString();
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
        checks++;
    }
}
