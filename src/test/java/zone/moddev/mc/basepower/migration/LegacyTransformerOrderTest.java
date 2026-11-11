package zone.moddev.mc.basepower.migration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;
import static zone.moddev.mc.basepower.migration.NativeMigrationMixinContract.*;

/** Strict native Mixin contracts. No hand-written transformer is substituted for the production injectors. */
public class LegacyTransformerOrderTest {
    private static final String POWER = "zone/moddev/mc/basepower/migration/LegacyWorldDataHook";
    private static final String METALS = "zone/moddev/mc/basemetals/migration/LegacyWorldDataHook";

    @Test public void advantageThenBaseMetalsPreservesBothHookContracts() throws Exception {
        check(List.of("basepower", "basemetals"));
    }

    @Test public void baseMetalsThenAdvantagePreservesBothHookContracts() throws Exception {
        check(List.of("basemetals", "basepower"));
    }

    @Test public void nativeChunkDescriptorsAndInvocationNamesFailClosedOnDrift() throws Exception {
        Binding staging = binding("basepower", "SimpleRegionStorageMixin", "prepareInventories");
        assertEquals(UPGRADE, staging.selector());
        staging.assertAt("INVOKE");
        assertEquals("Lnet/minecraft/nbt/NbtUtils;getDataVersion(" + TAG + "I)I", staging.invocation());
        assertEquals(1, staging.invokeSites(staging.target()));
        MethodNode nativeMethod = staging.nativeMethod();
        nativeMethod.name = "renamedUpgrade";
        assertThrows(AssertionError.class, staging::nativeMethod);
        Binding fresh = binding("basepower", "SimpleRegionStorageMixin", "prepareInventories");
        calls(fresh.nativeMethod()).stream().filter(fresh::matches).findFirst().orElseThrow().name = "renamedGetVersion";
        assertEquals("Mixin selectors must not accept renamed native invocation names", 0, fresh.invokeSites(fresh.target()));
        fresh.at().values.set(fresh.at().values.indexOf("value") + 1, "HEAD");
        assertThrows("Inventory staging at HEAD would race the other mod", AssertionError.class,
                () -> fresh.assertAt("INVOKE"));
    }

    @Test public void separateEntityStorageStillUsesTheNativeRegionConverterThroughThePowerHook() throws Exception {
        ClassNode entities = read("net/minecraft/world/level/chunk/storage/EntityStorage");
        String twoArg = "(" + TAG + "I)" + TAG;
        var nativeCalls = calls(entities).stream().filter(call -> call.owner.equals(REGION)
                && call.name.equals("upgradeChunkTag") && call.desc.equals(twoArg)).toList();
        assertEquals(1, nativeCalls.size());
        ClassNode region = read(REGION);
        MethodNode delegate = method(region, "upgradeChunkTag" + twoArg);
        assertEquals(1, calls(delegate).stream().filter(call -> call.owner.equals(REGION)
                && (call.name + call.desc).equals(UPGRADE)).count());
        binding("basepower", "SimpleRegionStorageMixin", "prepareChunk").assertAt("HEAD");
        binding("basepower", "SimpleRegionStorageMixin", "prepareInventories").assertAt("INVOKE");
        binding("basepower", "SimpleRegionStorageMixin", "finishChunk").assertAt("RETURN");
        nativeCalls.getFirst().desc = "(" + TAG + "J)" + TAG;
        assertFalse("Wrong native overload cannot satisfy the entity-storage contract", calls(entities).stream()
                .anyMatch(call -> call.owner.equals(REGION) && call.name.equals("upgradeChunkTag") && call.desc.equals(twoArg)));
    }

    @Test public void bothRequiredConfigsAndAllNativePowerMixinsArePresent() throws Exception {
        for (String mod : List.of("basepower", "basemetals")) {
            JsonObject config;
            try (var reader = new InputStreamReader(resource("/" + mod + ".mixins.json"), StandardCharsets.UTF_8)) {
                config = JsonParser.parseReader(reader).getAsJsonObject();
            }
            assertTrue(config.get("required").getAsBoolean());
            assertEquals("JAVA_21", config.get("compatibilityLevel").getAsString());
            assertEquals(1, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
            assertTrue(config.getAsJsonArray("mixins").asList().stream().map(value -> value.getAsString())
                    .toList().containsAll(List.of("BlockStateDataMixin", "LevelStorageSourceMixin",
                            "SimpleRegionStorageMixin", "WorldGenRegionMixin")));
            if (mod.equals("basepower")) assertEquals(Set.of("BlockStateDataMixin", "LevelStorageSourceMixin",
                    "PlayerDataStorageMixin", "SimpleRegionStorageMixin", "WorldGenRegionMixin"),
                    config.getAsJsonArray("mixins").asList().stream().map(value -> value.getAsString())
                            .collect(java.util.stream.Collectors.toSet()));
        }
        assertNull(getClass().getResource("/coremods/poweradvantage_118_migration.js"));
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of("src/main/resources/META-INF/coremods.json")));
    }

    @Test public void numericIdExpansionIsRequiredAtNativeClassInitializationTail() throws Exception {
        Binding expansion = binding("basepower", "BlockStateDataMixin", "expandLegacyIds");
        assertEquals("<clinit>", expansion.selector());
        expansion.assertAt("TAIL");
        assertEquals("net/minecraft/util/datafix/fixes/BlockStateData", expansion.target().name);
        for (String name : List.of("MAP", "BLOCK_DEFAULTS")) {
            var field = expansion.mixin().fields.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
            assertEquals("[Lcom/mojang/serialization/Dynamic;", field.desc);
            assertNotNull(expansion.target().fields.stream().filter(value -> value.name.equals(name)
                    && value.desc.equals(field.desc) && (value.access & Opcodes.ACC_STATIC) != 0).findFirst().orElseThrow());
            for (String annotation : List.of("Shadow", "Final", "Mutable")) {
                annotation(field.visibleAnnotations, "Lorg/spongepowered/asm/mixin/" + annotation + ";");
            }
        }
        assertTrue(Arrays.stream(expansion.handler().instructions.toArray())
                .anyMatch(insn -> insn instanceof LdcInsnNode constant && Integer.valueOf(65536).equals(constant.cst)));
        assertTrue(Arrays.stream(expansion.handler().instructions.toArray())
                .anyMatch(insn -> insn instanceof org.objectweb.asm.tree.IntInsnNode constant && constant.operand == 4096));
    }

    private static void check(List<String> mods) throws Exception {
        for (String mod : mods) {
            String hook = mod.equals("basepower") ? POWER : METALS;
            Binding world = binding(mod, "LevelStorageSourceMixin", "prepareWorld");
            world.assertAt("HEAD");
            assertEquals(STORAGE + "LevelStorageSource", world.target().name);
            assertEquals("readLevelDataTagFixed", world.selector());
            assertFalse(world.cancellable());
            assertCalls(world.handler(), hook, "prepareLegacyWorld", "(Ljava/io/File;)V");
            assertTrue("Only primary level.dat may initialize a migration snapshot",
                    Arrays.stream(world.handler().instructions.toArray()).anyMatch(insn ->
                            insn instanceof LdcInsnNode constant && "level.dat".equals(constant.cst)));
            var prepare = calls(world.handler()).stream().filter(call -> call.name.equals("prepareLegacyWorld")).findFirst().orElseThrow();
            assertTrue("Primary-file guard must precede snapshot preparation", Arrays.stream(world.handler().instructions.toArray())
                    .takeWhile(insn -> insn != prepare).anyMatch(JumpInsnNode.class::isInstance));

            Binding before = binding(mod, "SimpleRegionStorageMixin", "prepareChunk");
            before.assertAt("HEAD");
            assertEquals(REGION, before.target().name);
            assertEquals(UPGRADE, before.selector());
            assertFalse("Both HEAD hooks must be able to run", before.cancellable());
            assertCalls(before.handler(), hook, "prepareLegacyChunk", "(" + TAG + ")V");
            Binding after = binding(mod, "SimpleRegionStorageMixin", "finishChunk");
            after.assertAt("RETURN");
            assertEquals(UPGRADE, after.selector());
            assertFalse("Finalization must not short-circuit companion return hooks", after.cancellable());
            assertEquals("All native returns, including current-version early return, must be covered", 2,
                    Arrays.stream(after.nativeMethod().instructions.toArray()).filter(insn -> insn.getOpcode() == Opcodes.ARETURN).count());
            assertCalls(after.handler(), hook, "finalizeLegacyChunk", "(" + TAG + ")" + TAG);
            assertFalse("In-place finalization must not cancel the return callback",
                    calls(after.handler()).stream().anyMatch(call -> call.name.equals("setReturnValue")
                            || call.name.equals("cancel")));

            Binding worldgen = binding(mod, "WorldGenRegionMixin", "protectTerrain");
            worldgen.assertAt("HEAD");
            assertEquals("net/minecraft/server/level/WorldGenRegion", worldgen.target().name);
            assertEquals("ensureCanWrite", worldgen.selector());
            assertTrue(worldgen.cancellable());
            assertCalls(worldgen.handler(), hook, "shouldBlockWorldgenWrite",
                    "(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/core/BlockPos;)Z");
            assertCallbackResult(worldgen.handler());
            assertTrue("A blocked write must return false, never true", Arrays.stream(worldgen.handler().instructions.toArray())
                    .anyMatch(insn -> insn.getOpcode() == Opcodes.ICONST_0));
            assertFalse(Arrays.stream(worldgen.handler().instructions.toArray())
                    .anyMatch(insn -> insn.getOpcode() == Opcodes.ICONST_1));
        }
        Binding staging = binding("basepower", "SimpleRegionStorageMixin", "prepareInventories");
        staging.assertAt("INVOKE");
        assertEquals(1, calls(staging.nativeMethod()).stream().filter(staging::matches).count());
        assertCalls(staging.handler(), POWER, "prepareLegacyEntities", "(" + TAG + ")V");
        // At(INVOKE), with no shift/slice/ordinal, follows every non-cancellable HEAD injection in either priority order.
        assertFalse(staging.cancellable());
        Binding read = binding("basepower", "SimpleRegionStorageMixin", "detachRead");
        read.assertAt("RETURN");
        assertTrue(read.cancellable());
        assertCalls(read.handler(), POWER, "copyChunkReadForMigration",
                "(Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;");
        assertCallbackResult(read.handler());
        for (String name : List.of("remapRaw", "remapSummary")) {
            Binding metadata = binding("basepower", "LevelStorageSourceMixin", name);
            metadata.assertAt("RETURN");
            assertTrue(metadata.cancellable());
            assertCalls(metadata.handler(), "zone/moddev/mc/basepower/migration/LegacyWorldMetadata",
                    name.equals("remapRaw") ? "remapRaw" : "remapLightweight",
                    name.equals("remapRaw") ? "(" + TAG + ")" + TAG : "(Lnet/minecraft/nbt/Tag;)Lnet/minecraft/nbt/Tag;");
            assertCallbackResult(metadata.handler());
        }
    }

    private static void assertCallbackResult(MethodNode method) {
        assertEquals(1, calls(method).stream().filter(call -> call.owner.equals(
                "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable")
                && call.name.equals("setReturnValue") && call.desc.equals("(Ljava/lang/Object;)V")).count());
    }

    private static void assertCalls(MethodNode method, String owner, String name, String descriptor) {
        assertEquals(method.name, 1, calls(method).stream().filter(call -> call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)).count());
    }
}
