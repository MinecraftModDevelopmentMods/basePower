package zone.moddev.mc.basepower.migration;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

public class LateOreSpawnProfileMigrationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private Path registerBeforeImport(String name) throws Exception {
        AdvantageLegacyMigrations.registerPowerAdvantage();
        Path config = temporary.newFolder(name).toPath();
        OreSpawnProfileMigration.registerPowerProvider(config);
        try (var files = Files.list(config)) { assertEquals(0, files.count()); }
        return config;
    }

    private static JsonObject quartzProfile(int schema) {
        return JsonParser.parseString("""
                {"schema_version":%d,"manage_vanilla_ores":false,"place_fluid_deposits":false,
                 "ores":{"minecraft:quartz_ore":{"enabled":true,"suppress_vanilla":false,
                   "dimensions":{"minecraft:the_nether":{"enabled":true,"frequency":0.375,
                     "min_y":12,"max_y":117,"node_size":9,"private":{"keep":42}}}}},
                 "providers":{"retained":{"known_ores":["minecraft:quartz_ore"]}},
                 "history":"minecraft:quartz_ore is a literal note"}
                """.formatted(schema)).getAsJsonObject();
    }

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    @Test public void lateCreatedGlobalAndOwnedOverrideAreBothMigrated() throws Exception {
        Path config = registerBeforeImport("late-import");
        Path global = config.resolve("orespawn-worldgen.json");
        JsonObject before = quartzProfile(8), expected = before.deepCopy();
        expected.getAsJsonObject("ores").getAsJsonObject("minecraft:quartz_ore")
                .addProperty("block", "minecraft:nether_quartz_ore");
        Files.writeString(global, before.toString());
        Path override = config.resolve("basepower-orespawn.json");
        JsonObject provider = JsonParser.parseString("""
                {"schema_version":4,"provider_modid":"basepower","provider_revision":3,
                 "ores":{"basepower:legacy/saved_oil":{"block":"poweradvantage:crude_oil",
                   "enabled":false,"dimensions":{"minecraft:overworld":{"enabled":false,
                     "frequency":0.625,"min_y":19,"max_y":47,"private":{"keep":7}}}}},
                 "fluid_deposits":{},"user":{"setting":"poweradvantage:crude_oil"}}
                """).getAsJsonObject();
        Files.writeString(override, provider.toString());
        assertTrue(OreSpawnProfileMigration.migrateLateProfiles(config));
        assertEquals(expected, read(global));
        JsonObject migrated = read(override), retained = provider.getAsJsonObject("ores")
                .getAsJsonObject("basepower:legacy/saved_oil").deepCopy();
        retained.addProperty("block", "basepower:crude_oil");
        assertEquals(retained, migrated.getAsJsonObject("ores").get("basepower:legacy/saved_oil"));
        assertEquals(4, migrated.get("schema_version").getAsInt());
        assertEquals(provider.get("provider_revision"), migrated.get("provider_revision"));
        assertEquals(provider.get("user"), migrated.get("user"));
        assertFalse(migrated.getAsJsonObject("fluid_deposits")
                .getAsJsonObject("basepower:fluid_deposit/desert_crude_oil").get("enabled").getAsBoolean());
    }

    @Test public void lateRepairPreservesRootSevenEightAndEveryNonOutputValue() throws Exception {
        for (int schema : new int[] {7, 8}) {
            Path config = registerBeforeImport("schema-" + schema);
            Path file = config.resolve("orespawn-worldgen.json");
            JsonObject source = quartzProfile(schema), expected = source.deepCopy();
            expected.getAsJsonObject("ores").getAsJsonObject("minecraft:quartz_ore")
                    .addProperty("block", "minecraft:nether_quartz_ore");
            Files.writeString(file, source.toString());
            assertTrue(OreSpawnProfileMigration.migrateLateProfiles(config));
            assertEquals(expected, read(file));
        }
    }

    @Test public void absentAndAlreadyRepairedLateFilesAreIdempotentIncludingEvidence() throws Exception {
        Path config = registerBeforeImport("idempotent");
        assertFalse(OreSpawnProfileMigration.migrateLateProfiles(config));
        try (var files = Files.list(config)) { assertEquals(0, files.count()); }
        Path file = config.resolve("orespawn-worldgen.json");
        byte[] original = ("\n" + quartzProfile(8) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, original);
        assertTrue(OreSpawnProfileMigration.migrateLateProfiles(config));
        Map<Path, byte[]> first = new LinkedHashMap<>();
        try (var files = Files.list(config)) {
            for (Path path : files.toList()) first.put(path, Files.readAllBytes(path));
        }
        assertEquals(3, first.size());
        Path backup = first.keySet().stream().filter(path -> path.toString().endsWith(".bak")).findFirst().orElseThrow();
        assertArrayEquals(original, first.get(backup));
        assertFalse(OreSpawnProfileMigration.migrateLateProfiles(config));
        try (var files = Files.list(config)) { assertEquals(first.keySet(), new java.util.HashSet<>(files.toList())); }
        for (var entry : first.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
    }

    @Test public void lateActiveCollisionRefusesWithoutChangingTheImportedFile() throws Exception {
        Path config = registerBeforeImport("refused");
        JsonObject source = quartzProfile(8);
        source.addProperty("manage_vanilla_ores", true);
        JsonObject current = source.getAsJsonObject("ores").getAsJsonObject("minecraft:quartz_ore").deepCopy();
        current.addProperty("block", "minecraft:nether_quartz_ore");
        source.getAsJsonObject("ores").add("minecraft:nether_quartz_ore", current);
        Path file = config.resolve("orespawn-worldgen.json");
        Files.writeString(file, source.toString());
        byte[] before = Files.readAllBytes(file);
        try {
            OreSpawnProfileMigration.migrateLateProfiles(config);
            fail("Ambiguous active imported rules must not be silently accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("needs review"));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test public void loadCompleteEnqueuesRepairThenConditionallyRebakesAndRefreshes() throws Exception {
        var power = NativeMigrationMixinContract.read("zone/moddev/mc/basepower/PowerAdvantage");
        var listener = NativeMigrationMixinContract.method(power, "finishOreSpawnMigration");
        var enqueues = NativeMigrationMixinContract.calls(listener).stream().filter(call ->
                call.owner.equals("net/minecraftforge/fml/event/lifecycle/FMLLoadCompleteEvent")
                        && call.name.equals("enqueueWork")
                        && call.desc.equals("(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;")).toList();
        assertEquals("One actual load-complete work submission", 1, enqueues.size());
        var enqueueFrame = sources(power.name, listener)[listener.instructions.indexOf(enqueues.getFirst())];
        var runnable = enqueueFrame.getStack(enqueueFrame.getStackSize() - 1);
        assertEquals("The submitted Runnable has one producer", 1, runnable.insns.size());
        assertTrue("The submitted Runnable is a lambda", runnable.insns.iterator().next() instanceof InvokeDynamicInsnNode);
        var lambda = (InvokeDynamicInsnNode) runnable.insns.iterator().next();
        assertEquals("java/lang/invoke/LambdaMetafactory", lambda.bsm.getOwner());
        assertTrue(Set.of("metafactory", "altMetafactory").contains(lambda.bsm.getName()));
        var implementations = Arrays.stream(lambda.bsmArgs).filter(Handle.class::isInstance).map(Handle.class::cast)
                .filter(handle -> handle.getOwner().equals(power.name)).toList();
        assertEquals("One exact Power callback implementation", 1, implementations.size());
        var implementation = implementations.getFirst();
        var callback = NativeMigrationMixinContract.method(power, implementation.getName() + implementation.getDesc());
        var calls = NativeMigrationMixinContract.calls(callback);
        var repairs = calls.stream().filter(call -> call.owner.equals("zone/moddev/mc/basepower/migration/OreSpawnProfileMigration")
                && call.name.equals("migrateLateProfiles")).toList();
        assertEquals("One repair in the submitted callback", 1, repairs.size());
        var repair = repairs.getFirst();
        assertEquals("(Ljava/nio/file/Path;)Z", repair.desc);
        var frames = sources(power.name, callback);
        var gates = new ArrayList<JumpInsnNode>();
        for (var insn : callback.instructions) {
            if (!(insn instanceof JumpInsnNode jump) || (jump.getOpcode() != Opcodes.IFEQ && jump.getOpcode() != Opcodes.IFNE)) continue;
            var frame = frames[callback.instructions.indexOf(jump)];
            if (frame != null && frame.getStack(frame.getStackSize() - 1).insns.equals(Set.of(repair))) gates.add(jump);
        }
        assertEquals("One gate consumes the actual repair result", 1, gates.size());
        var gate = gates.getFirst();
        var changedCalls = normalCalls(gate.getOpcode() == Opcodes.IFEQ ? gate.getNext() : gate.label);
        var unchangedCalls = normalCalls(gate.getOpcode() == Opcodes.IFEQ ? gate.label : gate.getNext());
        var expected = new ArrayList<MethodInsnNode>();
        int previous = callback.instructions.indexOf(repair);
        for (String owner : List.of("GeomeConfig", "OreSpawnOreGeneration", "FluidDepositFeature")) {
            String methodName = owner.equals("GeomeConfig") ? "bake" : "refreshWorldConfig";
            var matches = calls.stream().filter(value -> value.owner.equals("zone/moddev/mc/orespawn/worldgen/" + owner)
                    && value.name.equals(methodName)).toList();
            assertEquals("One " + owner + " refresh", 1, matches.size());
            var call = matches.getFirst();
            int index = callback.instructions.indexOf(call);
            assertTrue(owner + " follows repair in the required order", index > previous);
            expected.add(call);
            previous = index;
        }
        assertEquals("Changed files execute all three refresh calls in order", expected, changedCalls);
        assertTrue("Unchanged files execute no refresh calls", unchangedCalls.isEmpty());
    }

    private static Frame<SourceValue>[] sources(String owner, MethodNode method) throws Exception {
        return new Analyzer<>(new SourceInterpreter(Opcodes.ASM9) {
            @Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) { return value; }
        }).analyze(owner, method);
    }

    private static List<MethodInsnNode> normalCalls(AbstractInsnNode start) {
        var calls = new ArrayList<MethodInsnNode>();
        var visited = new HashSet<AbstractInsnNode>();
        for (var insn = start; insn != null; insn = insn.getNext()) {
            assertTrue("The callback's normal path terminates", visited.add(insn));
            if (insn instanceof MethodInsnNode call) calls.add(call);
            if (insn.getOpcode() == Opcodes.RETURN) return calls;
            if (insn instanceof JumpInsnNode jump) {
                assertEquals("No unreviewed conditional inside the refresh path", Opcodes.GOTO, jump.getOpcode());
                insn = jump.label;
            }
            assertNotEquals("The normal path must not throw", Opcodes.ATHROW, insn.getOpcode());
        }
        throw new AssertionError("The callback's normal path has no return");
    }
}
