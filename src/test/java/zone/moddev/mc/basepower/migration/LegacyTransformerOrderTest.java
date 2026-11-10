package zone.moddev.mc.basepower.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

/** Applies both real migration scripts to the exact native Forge 50 classes. */
public class LegacyTransformerOrderTest {
    private static final String WORLD = "net/minecraft/world/level/storage/LevelStorageSource";
    private static final String CHUNK = "net/minecraft/world/level/chunk/storage/ChunkStorage";
    private static final String ENTITIES = "net/minecraft/world/level/chunk/storage/EntityStorage";
    private static final String REGION = "net/minecraft/world/level/chunk/storage/SimpleRegionStorage";
    private static final String WORLDGEN = "net/minecraft/server/level/WorldGenRegion";
    private static final String POWER = "zone/moddev/mc/basepower/migration/LegacyWorldDataHook";
    private static final String METALS = "zone/moddev/mc/basemetals/migration/LegacyWorldDataHook";
    private static final String METADATA = "zone/moddev/mc/basepower/migration/LegacyWorldMetadata";
    private static final String PLAYER = "zone/moddev/mc/basepower/migration/LegacyPlayerDataFixes";
    private static final String NBT = "Lnet/minecraft/nbt/CompoundTag;";
    private static final String DYNAMIC = "Lcom/mojang/serialization/Dynamic;";
    private static final String FIXER = "Lcom/mojang/datafixers/DataFixer;";
    private static final String WORLD_READ = "(Ljava/nio/file/Path;" + FIXER + ")" + DYNAMIC;
    private static final String CHUNK_UPGRADE = "(Lnet/minecraft/resources/ResourceKey;"
            + "Ljava/util/function/Supplier;" + NBT + "Ljava/util/Optional;)" + NBT;

    @Test public void advantageThenBaseMetalsPreservesBothHooks() throws Exception { check(false, false); }
    @Test public void baseMetalsThenAdvantagePreservesBothHooks() throws Exception { check(true, false); }

    @Test public void descriptorBasedChunkHooksDoNotDependOnMethodNamesInEitherOrder() throws Exception {
        check(false, true);
        check(true, true);
    }

    @Test public void separateEntityStorageStillUsesTheNativeRegionConverterThroughThePowerHook() throws Exception {
        ClassNode node = nativeClass(ENTITIES);
        String desc = "(" + NBT + "I)" + NBT;
        assertEquals(1, nativeEntityCalls(node, desc).size());
        transform(node, "separate_entity_storage", false);
        assertEquals(0, nativeEntityCalls(node, desc).size());
        assertEquals(1, calls(node, POWER, "upgradeEntityChunk", "(L" + REGION + ";" + desc.substring(1)));

        ClassNode missing = nativeClass(ENTITIES);
        nativeEntityCalls(missing, desc).getFirst().desc = "(" + NBT + "J)" + NBT;
        expectRejection(() -> transform(missing, "separate_entity_storage", false), "exactly one native entity-chunk");
        assertEquals(0, calls(missing, POWER, "upgradeEntityChunk", "(L" + REGION + ";" + desc.substring(1)));
    }

    private static void check(boolean baseFirst, boolean renamedChunk) throws Exception {
        ClassNode world = nativeClass(WORLD);
        applyBoth(world, "legacy_world_info", baseFirst);
        MethodNode fixed = method(world, "readLevelDataTagFixed", WORLD_READ);
        List<MethodInsnNode> readers = methods(fixed);
        assertEquals(List.of("java/nio/file/Path", lastApplied(baseFirst), "java/nio/file/Path",
                firstApplied(baseFirst), WORLD), readers.subList(0, 5).stream().map(call -> call.owner).toList());
        assertEquals("readLevelDataTagRaw", readers.get(4).name);
        assertBoth(world, "prepareLegacyWorld", "(Ljava/io/File;)V");
        assertEquals(1, calls(method(world, "readLevelDataTagRaw", "(Ljava/nio/file/Path;)" + NBT),
                METADATA, "remapRaw", "(" + NBT + ")" + NBT));
        String tag = "Lnet/minecraft/nbt/Tag;";
        assertEquals(1, calls(method(world, "readLightweightData", "(Ljava/nio/file/Path;)" + tag),
                METADATA, "remapLightweight", "(" + tag + ")" + tag));
        assertEquals(1, calls(world, PLAYER, "updateNativeEmbeddedPlayer",
                "(Lnet/minecraft/util/datafix/DataFixTypes;" + FIXER + DYNAMIC + "I)" + DYNAMIC));

        ClassNode chunk = nativeClass(CHUNK);
        MethodNode upgrade = method(chunk, "upgradeChunkTag", CHUNK_UPGRADE);
        MethodNode read = method(chunk, "read", "(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;");
        if (renamedChunk) {
            renameChunkMethod(chunk, upgrade);
            renameChunkMethod(chunk, read);
            renameChunkMethod(chunk, method(chunk, "getVersion", "(" + NBT + ")I"));
        }
        applyBoth(chunk, "legacy_chunk_status", baseFirst);
        assertBoth(chunk, "prepareLegacyChunk", "(" + NBT + ")V");
        assertBoth(chunk, "finalizeLegacyChunk", "(" + NBT + ")" + NBT);
        assertEquals(1, calls(upgrade, POWER, "prepareLegacyEntities", "(" + NBT + ")V"));
        assertEquals(1, calls(read, POWER, "copyChunkReadForMigration",
                "(Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;"));
        assertChunkOrder(upgrade, baseFirst);

        ClassNode worldgen = nativeClass(WORLDGEN);
        applyBoth(worldgen, "legacy_worldgen_guard", baseFirst);
        String guardDesc = "(L" + WORLDGEN + ";Lnet/minecraft/core/BlockPos;)Z";
        assertBoth(worldgen, "shouldBlockWorldgenWrite", guardDesc);
        MethodNode guard = method(worldgen, "ensureCanWrite", "(Lnet/minecraft/core/BlockPos;)Z");
        List<MethodInsnNode> guardCalls = methods(guard);
        assertGuard(guardCalls.get(0), lastApplied(baseFirst));
        assertGuard(guardCalls.get(1), firstApplied(baseFirst));
    }

    private static void assertChunkOrder(MethodNode upgrade, boolean baseFirst) {
        List<MethodInsnNode> entries = methods(upgrade).subList(0, 4);
        assertEquals(List.of("prepareLegacyChunk", "prepareLegacyChunk", "prepareLegacyEntities", "getVersion"),
                List.of(entries.get(0).name, entries.get(1).name, entries.get(2).name,
                        entries.get(3).name.replace("renamed_", "")));
        assertEquals(List.of(lastApplied(baseFirst), firstApplied(baseFirst), POWER, CHUNK),
                entries.stream().map(call -> call.owner).toList());
        assertEquals("(" + NBT + ")I", entries.get(3).desc);
        for (AbstractInsnNode instruction : upgrade.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
            assertTrue(instruction.getPrevious() instanceof MethodInsnNode);
            MethodInsnNode last = (MethodInsnNode) instruction.getPrevious();
            assertTrue(last.getPrevious() instanceof MethodInsnNode);
            MethodInsnNode before = (MethodInsnNode) last.getPrevious();
            assertEquals("finalizeLegacyChunk", before.name);
            assertEquals("finalizeLegacyChunk", last.name);
            assertEquals(firstApplied(baseFirst), before.owner);
            assertEquals(lastApplied(baseFirst), last.owner);
        }
    }

    private static void assertGuard(MethodInsnNode call, String owner) {
        assertEquals(owner, call.owner);
        assertEquals("shouldBlockWorldgenWrite", call.name);
        assertEquals(Opcodes.IFEQ, call.getNext().getOpcode());
        assertEquals(Opcodes.ICONST_0, call.getNext().getNext().getOpcode());
        AbstractInsnNode denied = call.getNext().getNext().getNext();
        assertEquals(Opcodes.IRETURN, denied.getOpcode());
        assertSame(((JumpInsnNode) call.getNext()).label, denied.getNext());
    }

    private static String firstApplied(boolean baseFirst) { return baseFirst ? METALS : POWER; }
    private static String lastApplied(boolean baseFirst) { return baseFirst ? POWER : METALS; }

    private static List<MethodInsnNode> nativeEntityCalls(ClassNode node, String desc) {
        return methods(node).stream().filter(call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && call.owner.equals(REGION) && call.name.equals("upgradeChunkTag") && call.desc.equals(desc)).toList();
    }

    private static void assertBoth(ClassNode node, String name, String desc) {
        assertEquals(node.name, 1, calls(node, POWER, name, desc));
        assertEquals(node.name, 1, calls(node, METALS, name, desc));
    }

    private static void renameChunkMethod(ClassNode node, MethodNode target) {
        for (MethodInsnNode call : methods(node)) {
            if (call.owner.equals(node.name) && call.name.equals(target.name) && call.desc.equals(target.desc)) {
                call.name = "renamed_" + call.name;
            }
        }
        target.name = "renamed_" + target.name;
    }

    private static void applyBoth(ClassNode node, String target, boolean baseFirst) throws Exception {
        transform(node, target, baseFirst);
        transform(node, target, !baseFirst);
    }

    static void transform(ClassNode node, String target, boolean base) throws Exception {
        String mod = base ? "basemetals" : "poweradvantage";
        String path = base ? "/coremods/basemetals_118_compatibility.js" : "/coremods/poweradvantage_118_migration.js";
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull("Transformer tests require Forge's bundled Nashorn engine", engine);
        try (InputStreamReader reader = new InputStreamReader(resource(path), StandardCharsets.UTF_8)) { engine.eval(reader); }
        engine.put("targetClass", node);
        String entry = "initializeCoreMod()." + mod + "_" + target;
        assertEquals("CLASS", engine.eval(entry + ".target.type"));
        assertEquals(node.name.replace('/', '.'), engine.eval(entry + ".target.name"));
        assertSame(node, engine.eval(entry + ".transformer(targetClass)"));
    }

    static ClassNode nativeClass(String internalName) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream input = resource("/" + internalName + ".class")) {
            new ClassReader(input).accept(node, 0);
        }
        return node;
    }

    static InputStream resource(String name) {
        InputStream input = LegacyTransformerOrderTest.class.getResourceAsStream(name);
        assertNotNull("Missing pinned native dependency resource " + name, input);
        return input;
    }

    static void expectRejection(TransformerCall call, String message) throws Exception {
        try {
            call.run();
            fail("An unsupported native reader must abort transformation");
        } catch (ScriptException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }

    @FunctionalInterface interface TransformerCall { void run() throws Exception; }

    static int calls(ClassNode node, String owner, String name, String desc) {
        return node.methods.stream().mapToInt(method -> calls(method, owner, name, desc)).sum();
    }

    static int calls(MethodNode method, String owner, String name, String desc) {
        return (int) methods(method).stream().filter(call -> {
            if (!call.owner.equals(owner) || !call.name.equals(name) || !call.desc.equals(desc)) return false;
            assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
            return true;
        }).count();
    }

    private static List<MethodInsnNode> methods(ClassNode node) {
        List<MethodInsnNode> all = new ArrayList<>();
        node.methods.forEach(method -> all.addAll(methods(method)));
        return all;
    }

    private static List<MethodInsnNode> methods(MethodNode method) {
        return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).toList();
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(desc))
                .findFirst().orElseThrow(() -> new AssertionError("Missing native method " + node.name + "." + name + desc));
    }
}
