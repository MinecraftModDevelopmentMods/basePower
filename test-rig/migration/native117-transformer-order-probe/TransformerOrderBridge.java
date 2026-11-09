package qualification.order;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Disposable Forge 37 observer. Delegates are real coremods; their functions are never replaced. */
public final class TransformerOrderBridge {
    private static final String OWNER = "qualification/order/TransformerOrderBridge";
    private static final String ENGINE = "net/minecraftforge/coremod/CoreModEngine.class";
    private static final String[] PAIRED = {"world_info", "chunk_status", "worldgen_guard"};
    private static final String ENTITY = "poweradvantage_separate_entity_storage";
    private static final Gson JSON = new Gson();
    private static int sequence;

    private TransformerOrderBridge() {}

    private static String name(ITransformer<?> transformer) {
        String[] labels = transformer.labels();
        if (labels.length < 2) throw new IllegalStateException("Missing real coremod labels");
        return labels[labels.length - 1];
    }

    public static List<ITransformer<?>> configure(List<ITransformer<?>> input) throws IOException {
        if (!Files.isRegularFile(Paths.get("advantage-migration.allow"))) {
            throw new IllegalStateException("Unmarked order qualification runtime");
        }
        String mode = new String(Files.readAllBytes(Paths.get("transformer-order.mode")),
                StandardCharsets.UTF_8).trim();
        if (!Arrays.asList("natural", "bm-first", "pa-first").contains(mode)) {
            throw new IllegalArgumentException("Unknown order: " + mode);
        }
        List<ITransformer<?>> output = new ArrayList<>(input);
        List<String> natural = names(input);
        for (String suffix : PAIRED) {
            int bm = find(output, "basemetals_legacy_" + suffix);
            int pa = find(output, "poweradvantage_legacy_" + suffix);
            if (!mode.equals("natural") && ((bm < pa) != mode.equals("bm-first"))) {
                ITransformer<?> saved = output.get(bm);
                output.set(bm, output.get(pa));
                output.set(pa, saved);
            }
        }
        // There is no Base Metals entity-storage partner. Observe it without inventing an order pair.
        find(output, ENTITY);
        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put("event", "registration");
        registration.put("bridge", "native117-v1");
        registration.put("mode", mode);
        registration.put("natural", natural);
        registration.put("forced", names(output));
        record(registration);
        for (int i = 0; i < output.size(); i++) {
            ITransformer<?> transformer = output.get(i);
            if (selected(name(transformer))) {
                @SuppressWarnings("unchecked")
                ITransformer<ClassNode> delegate = (ITransformer<ClassNode>) transformer;
                output.set(i, new Observed(delegate));
            }
        }
        return output;
    }

    private static int find(List<ITransformer<?>> values, String expected) {
        int found = -1;
        for (int i = 0; i < values.size(); i++) {
            if (!name(values.get(i)).equals(expected)) continue;
            if (found != -1) throw new IllegalStateException("Duplicate transformer: " + expected);
            found = i;
        }
        if (found == -1) throw new IllegalStateException("Missing transformer: " + expected);
        return found;
    }

    private static List<String> names(List<ITransformer<?>> values) {
        List<String> result = new ArrayList<>();
        for (ITransformer<?> value : values) result.add(name(value));
        return result;
    }

    private static boolean selected(String name) {
        if (name.equals(ENTITY)) return true;
        for (String suffix : PAIRED) {
            if (name.equals("basemetals_legacy_" + suffix)
                    || name.equals("poweradvantage_legacy_" + suffix)) return true;
        }
        return false;
    }

    private static synchronized void record(Map<String, Object> event) throws IOException {
        event.put("sequence", ++sequence);
        Files.write(Paths.get("transformer-order-events.jsonl"),
                (JSON.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static List<Map<String, Object>> hooks(ClassNode node) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MethodNode method : node.methods) {
            int index = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() < 0) continue;
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.owner.endsWith("/migration/LegacyWorldDataHook")) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("method", method.name + method.desc);
                        entry.put("index", index);
                        entry.put("owner", call.owner);
                        entry.put("name", call.name);
                        entry.put("descriptor", call.desc);
                        result.add(entry);
                    }
                }
                index++;
            }
        }
        return result;
    }

    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void dump(Map<String, Object> event, String phase, ClassNode node, String transformer)
            throws Exception {
        byte[] bytes = bytes(node);
        Path path = Paths.get("transformer-order-bytecode", node.name + "-" + transformer + "-" + phase + ".class");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        event.put(phase, hooks(node));
        event.put(phase + "Dump", path.toString().replace('\\', '/'));
        event.put(phase + "Sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private static final class Observed implements ITransformer<ClassNode> {
        private final ITransformer<ClassNode> delegate;

        Observed(ITransformer<ClassNode> delegate) { this.delegate = delegate; }

        @Override public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
            try {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("event", "application");
                event.put("transformer", name(delegate));
                event.put("delegate", delegate.getClass().getName());
                event.put("labels", Arrays.asList(delegate.labels()));
                event.put("class", input.name);
                dump(event, "before", input, name(delegate));
                ClassNode result = delegate.transform(input, context);
                if (!result.name.equals(input.name)) throw new IllegalStateException("Coremod changed class identity");
                dump(event, "after", result, name(delegate));
                record(event);
                return result;
            } catch (Exception ex) {
                throw new IllegalStateException("Order qualification observation failed", ex);
            }
        }

        @Override public TransformerVoteResult castVote(ITransformerVotingContext context) {
            return delegate.castVote(context);
        }
        @Override public Set<Target> targets() { return delegate.targets(); }
        @Override public String[] labels() { return delegate.labels(); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02X", value & 255));
        return result.toString();
    }

    private static void inspect(Path file) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(file)).accept(node, 0);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("class", node.name);
        value.put("hooks", hooks(node));
        System.out.println(JSON.toJson(value));
    }

    /** Offline patcher: one call at the real CoreModEngine registration return. */
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--inspect")) {
            inspect(Paths.get(args[1]));
            return;
        }
        if (args.length != 3) throw new IllegalArgumentException("Expected original, output and classes");
        Path source = Paths.get(args[0]);
        Path target = Paths.get(args[1]);
        Path classes = Paths.get(args[2]);
        try (ZipFile archive = new ZipFile(source.toFile());
                ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))) {
            int changed = 0;
            java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] bytes = read(archive.getInputStream(entry));
                if (entry.getName().equals(ENGINE)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(bytes).accept(node, 0);
                    for (MethodNode method : node.methods) {
                        if (!method.name.equals("initializeCoreMods") || !method.desc.equals("()Ljava/util/List;")) continue;
                        for (AbstractInsnNode instruction : method.instructions.toArray()) {
                            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
                            method.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                    OWNER, "configure", "(Ljava/util/List;)Ljava/util/List;", false));
                            changed++;
                        }
                    }
                    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    node.accept(writer);
                    bytes = writer.toByteArray();
                }
                ZipEntry copied = new ZipEntry(entry.getName());
                copied.setTime(entry.getTime());
                out.putNextEntry(copied);
                out.write(bytes);
                out.closeEntry();
            }
            if (changed != 1) throw new IllegalStateException("Expected one registration return site, got " + changed);
            for (String file : Arrays.asList("TransformerOrderBridge.class", "TransformerOrderBridge$Observed.class")) {
                ZipEntry entry = new ZipEntry("qualification/order/" + file);
                entry.setTime(0);
                out.putNextEntry(entry);
                out.write(Files.readAllBytes(classes.resolve(entry.getName())));
                out.closeEntry();
            }
        }
        System.out.println("Patched only CoreModEngine.initializeCoreMods registration return");
    }

    private static byte[] read(java.io.InputStream input) throws IOException {
        try (java.io.InputStream stream = input; java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
