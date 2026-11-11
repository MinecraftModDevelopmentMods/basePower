package zone.moddev.orderprobe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Writes new synthetic audit fixtures only, never jars or existing target files. */
public final class WovenNegativeControl {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        if (args[0].equals("fixture")) {
            if (!output.toString().endsWith(".class") || Files.exists(output)) throw new AssertionError("New fixture class required");
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "fixture/ProbeMixin", null, "java/lang/Object", null);
            if (!args[2].equals("no-annotation")) {
                AnnotationVisitor annotation = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
                AnnotationVisitor values = annotation.visitArray("value"); values.visit(null, Type.getType("Ljava/lang/Object;")); values.visitEnd();
                if (args[2].equals("explicit-priority")) annotation.visit("priority", 1234);
                annotation.visitEnd();
            }
            writer.visitEnd(); Files.createDirectories(output.getParent());
            Files.write(output, writer.toByteArray(), StandardOpenOption.CREATE_NEW); return;
        }
        if (!input.toString().endsWith(".class") || !output.toString().endsWith(".class") || Files.exists(output)) {
            throw new AssertionError("New .class fixture output required; jar/existing-file writes forbidden");
        }
        ClassNode node = ProbeGuard.node(Files.readAllBytes(input));
        String mutation = args[2];
        if (mutation.startsWith("worldgen-")) {
            MethodNode target = node.methods.stream().filter(row -> row.name.endsWith("basepower$protectTerrain")).findFirst().orElseThrow();
            if (mutation.equals("worldgen-body")) target.instructions.insert(new InsnNode(Opcodes.NOP));
            else if (mutation.equals("worldgen-true")) {
                AbstractInsnNode zero = Arrays.stream(target.instructions.toArray()).filter(row -> row.getOpcode() == Opcodes.ICONST_0).findFirst().orElseThrow();
                target.instructions.set(zero, new InsnNode(Opcodes.ICONST_1));
            } else if (mutation.equals("worldgen-extra-cast")) {
                AbstractInsnNode receiver = Arrays.stream(target.instructions.toArray()).filter(row -> row instanceof VarInsnNode var
                        && var.getOpcode() == Opcodes.ALOAD && var.var == 0).findFirst().orElseThrow();
                target.instructions.insert(receiver, new TypeInsnNode(Opcodes.CHECKCAST, node.name));
            } else throw new AssertionError("Unknown worldgen mutation");
            write(output, node); return;
        }
        MethodNode upgrade = node.methods.stream().filter(row -> row.name.equals("upgradeChunkTag")
                && row.desc.equals("(Lnet/minecraft/nbt/CompoundTag;ILnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;")).findFirst().orElseThrow();
        MethodNode head = node.methods.stream().filter(row -> row.name.endsWith("basepower$prepareChunk")).findFirst().orElseThrow();
        if (mutation.equals("body")) head.instructions.insert(new InsnNode(Opcodes.NOP));
        else if (mutation.equals("constant-body")) {
            MethodNode finish = node.methods.stream().filter(row -> row.name.endsWith("basepower$finishChunk")).findFirst().orElseThrow();
            InsnList extra = new InsnList(); extra.add(new InsnNode(Opcodes.ICONST_1)); extra.add(new InsnNode(Opcodes.POP));
            finish.instructions.insert(extra);
        } else if (mutation.equals("duplicate-handler")) {
            MethodNode duplicate = new MethodNode(head.access, head.name, head.desc, head.signature, null);
            head.accept(duplicate); node.methods.add(duplicate);
        } else if (mutation.equals("priority")) {
            AnnotationNode merged = ProbeGuard.annotation(head.visibleAnnotations, "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
            if (merged == null) merged = ProbeGuard.annotation(head.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
            merged.values.set(merged.values.indexOf("priority") + 1, 1234);
        } else if (mutation.equals("early-return")) {
            InsnList extra = new InsnList(); extra.add(new InsnNode(Opcodes.ACONST_NULL)); extra.add(new InsnNode(Opcodes.ARETURN));
            upgrade.instructions.insert(extra);
        } else {
            MethodInsnNode call = Arrays.stream(upgrade.instructions.toArray()).filter(row -> row instanceof MethodInsnNode method
                    && method.name.endsWith(mutation.equals("missing-return") ? "basepower$finishChunk" : "basepower$prepareChunk"))
                    .map(MethodInsnNode.class::cast).findFirst().orElseThrow();
            if (mutation.equals("missing-head") || mutation.equals("missing-return")) upgrade.instructions.remove(call);
            else if (mutation.equals("bypass-head")) {
                LabelNode after = new LabelNode(); upgrade.instructions.insert(call, after);
                upgrade.instructions.insert(new JumpInsnNode(Opcodes.GOTO, after));
            } else throw new AssertionError("Unknown mutation " + mutation);
        }
        write(output, node);
    }

    private static void write(Path output, ClassNode node) throws Exception {
        Files.createDirectories(output.getParent());
        ClassWriter writer = new ClassWriter(0); node.accept(writer);
        Files.write(output, writer.toByteArray(), StandardOpenOption.CREATE_NEW);
    }
}
