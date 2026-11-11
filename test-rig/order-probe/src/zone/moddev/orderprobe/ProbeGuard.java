package zone.moddev.orderprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

public final class ProbeGuard {
    private ProbeGuard() {}

    public static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("ORDER_PROBE: " + message);
    }

    public static byte[] entry(Path jar, String name) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            require(zip.stream().filter(row -> row.getName().equals(name)).count() == 1, "missing/duplicate jar entry " + name);
            try (InputStream stream = zip.getInputStream(zip.getEntry(name))) { return stream.readAllBytes(); }
        }
    }

    public static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    public static AnnotationNode annotation(List<AnnotationNode> values, String descriptor) {
        return values == null ? null : values.stream().filter(value -> value.desc.equals(descriptor)).findFirst().orElse(null);
    }

    public static Object value(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        int index = annotation.values.indexOf(key);
        return index < 0 ? null : annotation.values.get(index + 1);
    }

    public static JsonObject read(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    public static void verifyProduction(JsonObject pins, Path mods) throws Exception {
        require(pins.get("format").getAsInt() == 1 && pins.get("mixinVersion").getAsString().equals("0.8.7"), "pin version drift");
        require(pins.getAsJsonObject("mods").keySet().equals(java.util.Set.of("basepower", "basemetals", "basesteam", "baseelectric", "orespawn", "advantageworkstest")), "six-jar pin scope changed");
        require(pins.getAsJsonObject("configs").keySet().equals(java.util.Set.of("basepower.mixins.json", "basemetals.mixins.json")), "config pin scope changed");
        for (var row : pins.getAsJsonObject("mods").entrySet()) {
            JsonObject pin = row.getValue().getAsJsonObject();
            Path jar = mods.resolve(pin.get("file").getAsString());
            require(sha(Files.readAllBytes(jar)).equals(pin.get("sha256").getAsString()), "production jar drift: " + row.getKey());
        }
        for (var row : pins.getAsJsonObject("configs").entrySet()) {
            JsonObject pin = row.getValue().getAsJsonObject();
            String mod = pin.get("mod").getAsString();
            int copies = 0;
            try (var candidates = Files.list(mods)) {
                for (Path candidate : candidates.filter(path -> path.getFileName().toString().endsWith(".jar")).toList()) {
                    try (ZipFile zip = new ZipFile(candidate.toFile())) {
                        copies += (int) zip.stream().filter(item -> item.getName().equals(row.getKey())).count();
                    }
                }
            }
            require(copies == 1, "missing/duplicate child config resource " + row.getKey());
            Path jar = mods.resolve(pins.getAsJsonObject("mods").getAsJsonObject(mod).get("file").getAsString());
            byte[] config = entry(jar, row.getKey());
            require(sha(config).equals(pin.get("sha256").getAsString()), "config drift: " + row.getKey());
            JsonObject document = JsonParser.parseString(new String(config, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            require(!document.has("plugin") && !document.has("priority") && !document.has("mixinPriority"), "unexpected child config priority/plugin");
            require(document.get("required").getAsBoolean(), "child config not required");
            for (var type : pin.getAsJsonObject("classes").entrySet()) {
                byte[] bytes = entry(jar, type.getKey().replace('.', '/') + ".class");
                require(sha(bytes).equals(type.getValue().getAsString()), "mixin body drift: " + type.getKey());
                ClassNode node = node(bytes);
                AnnotationNode mixin = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
                if (mixin == null) mixin = annotation(node.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
                require(mixin != null && value(mixin, "priority") == null, "explicit class priority or missing @Mixin: " + type.getKey());
            }
        }
    }

    public static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.getAsString()));
        return result;
    }
}
