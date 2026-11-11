package zone.moddev.mc.basepower.migration;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import static org.junit.Assert.*;

/** Checks the real compiled mixin selectors against the pinned native bytecode, without simulating weaving. */
final class NativeMigrationMixinContract {
    static final String STORAGE = "net/minecraft/world/level/storage/";
    static final String REGION = "net/minecraft/world/level/chunk/storage/SimpleRegionStorage";
    static final String FIX_TYPES = "net/minecraft/util/datafix/DataFixTypes";
    static final String TAG = "Lnet/minecraft/nbt/CompoundTag;";
    static final String DYNAMIC = "Lcom/mojang/serialization/Dynamic;";
    static final String FIXER = "Lcom/mojang/datafixers/DataFixer;";
    static final String UPGRADE = "upgradeChunkTag(" + TAG + "I" + TAG + ")" + TAG;

    private NativeMigrationMixinContract() {}

    static ClassNode read(String name) throws IOException {
        ClassNode result = new ClassNode();
        try (InputStream input = resource("/" + name + ".class")) {
            new ClassReader(input).accept(result, 0);
        }
        return result;
    }

    static InputStream resource(String name) {
        InputStream input = NativeMigrationMixinContract.class.getResourceAsStream(name);
        assertNotNull("Missing pinned resource " + name, input);
        return input;
    }

    static List<MethodInsnNode> calls(MethodNode method) {
        return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).toList();
    }

    static List<MethodInsnNode> calls(ClassNode type) {
        return type.methods.stream().flatMap(method -> calls(method).stream()).toList();
    }

    static MethodNode method(ClassNode owner, String selector) {
        List<MethodNode> found = owner.methods.stream().filter(method -> selector.equals(method.name)
                || selector.equals(method.name + method.desc)).toList();
        assertEquals("Exact native selector " + owner.name + "." + selector, 1, found.size());
        return found.getFirst();
    }

    static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        assertNotNull("Missing annotations for " + descriptor, annotations);
        return annotations.stream().filter(value -> value.desc.equals(descriptor)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing annotation " + descriptor));
    }

    static Object value(AnnotationNode annotation, String name, Object fallback) {
        if (annotation.values != null) for (int index = 0; index < annotation.values.size(); index += 2) {
            if (name.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    static Binding binding(String mod, String mixin, String handler) throws IOException {
        ClassNode owner = read("zone/moddev/mc/" + mod + "/mixin/" + mixin);
        var target = annotation(owner.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        List<Type> targets = (List<Type>) value(target, "value", List.of());
        assertEquals("One native mixin target", 1, targets.size());
        MethodNode callback = method(owner, mod + "$" + handler);
        List<AnnotationNode> injectors = callback.visibleAnnotations.stream().filter(ann ->
                ann.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
                || ann.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")).toList();
        assertEquals("One native injector", 1, injectors.size());
        AnnotationNode injector = injectors.getFirst();
        List<String> selectors = (List<String>) value(injector, "method", List.of());
        assertEquals("One explicit selector", 1, selectors.size());
        Object atValue = value(injector, "at", null);
        AnnotationNode at;
        if (atValue instanceof List<?> list) {
            assertEquals("One injection point", 1, list.size());
            at = (AnnotationNode) list.getFirst();
        } else at = (AnnotationNode) atValue;
        assertNotNull("Missing native injection point", at);
        return new Binding(owner, callback, injector, at, read(targets.getFirst().getInternalName()), selectors.getFirst());
    }

    record Binding(ClassNode mixin, MethodNode handler, AnnotationNode injector, AnnotationNode at,
            ClassNode target, String selector) {
        String point() { return (String) value(at, "value", ""); }
        String invocation() { return (String) value(at, "target", ""); }
        boolean cancellable() { return Boolean.TRUE.equals(value(injector, "cancellable", false)); }

        MethodNode nativeMethod() {
            assertNotEquals("Wildcard redirects have several native methods", "*", selector);
            return method(target, selector);
        }

        void assertAt(String expected) {
            assertEquals(expected, point());
            assertFalse("No ordinal may silently omit an injection", at.values.contains("ordinal"));
            assertFalse("No shifted native injection", at.values.contains("shift"));
            assertFalse("No slices may omit return paths", injector.values.contains("slice"));
            assertTrue("Required injector cannot be disabled", ((Number) value(injector, "require", 1)).intValue() >= 1);
            if (!selector.equals("*")) {
                MethodNode nativeMethod = nativeMethod();
                assertEquals("Handler/native static ABI", nativeMethod.access & Opcodes.ACC_STATIC,
                        handler.access & Opcodes.ACC_STATIC);
                String nativeArgs = nativeMethod.desc.substring(1, nativeMethod.desc.indexOf(')'));
                String callback = nativeMethod.desc.endsWith("V") ? "CallbackInfo" : "CallbackInfoReturnable";
                assertEquals("Exact native callback ABI", "(" + nativeArgs
                        + "Lorg/spongepowered/asm/mixin/injection/callback/" + callback + ";)V", handler.desc);
            }
        }

        int invokeSites(ClassNode nativeClass) {
            return (int) calls(nativeClass).stream().filter(this::matches).count();
        }

        boolean matches(MethodInsnNode call) {
            return invocation().equals("L" + call.owner + ";" + call.name + call.desc);
        }

        List<MethodNode> playerMethods(ClassNode nativeClass) throws Exception {
            List<MethodNode> result = new ArrayList<>();
            for (MethodNode method : nativeClass.methods) {
                if (calls(method).stream().noneMatch(this::matches)) continue;
                // Track the actual receiver through locals; a nearby PLAYER constant is not sufficient.
                var analyzer = new Analyzer<SourceValue>(new SourceInterpreter(Opcodes.ASM9) {
                    @Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue input) { return input; }
                });
                var frames = analyzer.analyze(nativeClass.name, method);
                for (int index = 0; index < method.instructions.size(); index++) {
                    if (!(method.instructions.get(index) instanceof MethodInsnNode call) || !matches(call)) continue;
                    var frame = frames[index];
                    var receiver = frame.getStack(frame.getStackSize() - Type.getArgumentTypes(call.desc).length - 1);
                    if (receiver.insns.size() == 1 && receiver.insns.iterator().next() instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETSTATIC && field.owner.equals(FIX_TYPES)
                            && field.name.equals("PLAYER")) result.add(method);
                }
            }
            return result;
        }

        MethodNode assertOnePlayerSite(ClassNode nativeClass) throws Exception {
            List<MethodNode> methods = playerMethods(nativeClass);
            assertEquals("Exactly one native PLAYER call: " + nativeClass.name, 1, methods.size());
            return methods.getFirst();
        }
    }
}
