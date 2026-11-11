package zone.moddev.mc.basepower.migration;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.fixes.References;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;
import static zone.moddev.mc.basepower.migration.NativeMigrationMixinContract.*;

/** Native redirect contracts and real handler execution; packaged tests prove live Mixin application. */
public class LegacyPlayerTransformerTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private static Binding loose() throws Exception {
        return binding("basepower", "PlayerDataStorageMixin", "preservePlayer");
    }

    private static Binding embedded() throws Exception {
        return binding("basepower", "LevelStorageSourceMixin", "preserveEmbeddedPlayer");
    }

    private static List<Binding> targets() throws Exception { return List.of(loose(), embedded()); }

    @Test public void exactNativeLoosePlayerLoadAndEmbeddedUpdateAreRedirected() throws Exception {
        for (Binding target : targets()) {
            assertEquals("*", target.selector());
            assertTrue(target.injector().desc.endsWith("/Redirect;"));
            target.assertAt("INVOKE");
            String payload = target.target().name.endsWith("PlayerDataStorage") ? TAG : DYNAMIC;
            assertEquals("L" + FIX_TYPES + ";updateToCurrentVersion(" + FIXER + payload + "I)" + payload,
                    target.invocation());
            assertEquals("(L" + FIX_TYPES + ";" + FIXER + payload + "I)" + payload, target.handler().desc);
            target.assertOnePlayerSite(target.target());
            String wrapper = payload.equals(TAG) ? "updateNativePlayer" : "updateNativeEmbeddedPlayer";
            assertEquals(1, calls(target.handler()).stream().filter(call -> call.owner.equals(
                    "zone/moddev/mc/basepower/migration/LegacyPlayerDataFixes") && call.name.equals(wrapper)).count());
        }
        assertEquals(1, value(loose().injector(), "require", -1));
        assertEquals(1, value(loose().injector(), "allow", -1));
        // Other embedded data-fix types are deliberately redirected too, then forwarded unchanged.
        assertEquals(4, embedded().invokeSites(embedded().target()));
        assertEquals(1, loose().invokeSites(loose().target()));
    }

    @Test public void nativeEmbeddedRedirectExecutesAndDoesNotReconvertCurrentPlayer() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RecordingFixer fixer = new RecordingFixer(DataFixers.getDataFixer());
        Method redirect = redirect();
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        for (int old : new int[] {512, 1343}) {
            Path file = temp.newFolder("legacy-" + old).toPath().resolve("level.dat");
            CompoundTag original = levelData(old, legacyPlayer());
            NbtIo.writeCompressed(original, file);
            CompoundTag read = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            CompoundTag migrated = invoke(redirect, DataFixTypes.PLAYER, fixer,
                    read.getCompoundOrEmpty("Data").getCompoundOrEmpty("Player"), old);
            assertPlayerContents(migrated);
            int boundary = 3465;
            assertEquals(old < 704 ? List.of(old + "->704", "704->" + boundary, boundary + "->" + current)
                    : List.of(old + "->" + boundary, boundary + "->" + current), fixer.playerCalls);
            assertEquals(original, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
            CompoundTag saved = levelData(current, migrated);
            NbtIo.writeCompressed(saved, file);
            fixer.playerCalls.clear();
            assertEquals(migrated, invoke(redirect, DataFixTypes.PLAYER, fixer, migrated, current));
            assertTrue("Current player must not re-enter legacy DFU", fixer.playerCalls.isEmpty());
            assertEquals(saved, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
        }
        CompoundTag unrelated = new CompoundTag();
        unrelated.putString("witness", "native-forwarding");
        fixer.playerCalls.clear();
        CompoundTag expected = DataFixTypes.LEVEL.updateToCurrentVersion(DataFixers.getDataFixer(),
                unrelated.copy(), current);
        assertEquals(expected, invoke(redirect, DataFixTypes.LEVEL, fixer, unrelated, current));
        assertTrue("Unrelated types cannot enter the player wrapper", fixer.playerCalls.isEmpty());
    }

    @Test public void renamedNativeLambdaNamesDoNotAffectWildcardMatching() throws Exception {
        for (Binding target : targets()) {
            ClassNode nativeClass = read(target.target().name);
            target.assertOnePlayerSite(nativeClass).name = "renamedPlayerLambda";
            assertEquals("renamedPlayerLambda", target.assertOnePlayerSite(nativeClass).name);
        }
    }

    @Test public void absentCallFailsClosedWithoutAcceptingOtherOwnersOrNames() throws Exception {
        for (Binding target : targets()) for (boolean owner : new boolean[] {true, false}) {
            ClassNode nativeClass = read(target.target().name);
            MethodInsnNode call = playerCall(target, nativeClass);
            if (owner) call.owner = "other/UnrelatedNBTUtil";
            else call.name = "renamedNativePlayerUpdate";
            rejectedUnchanged(target, nativeClass);
        }
    }

    @Test public void duplicateCallsInOneReaderFailClosed() throws Exception {
        for (Binding target : targets()) {
            ClassNode nativeClass = read(target.target().name);
            MethodNode method = target.assertOnePlayerSite(nativeClass);
            MethodNode copy = copy(method, method.name);
            for (var insn : method.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.ARETURN) method.instructions.set(insn, new InsnNode(Opcodes.POP));
            }
            method.instructions.add(copy.instructions);
            assertEquals(2, target.playerMethods(nativeClass).size());
            rejectedUnchanged(target, nativeClass);
        }
    }

    @Test public void duplicateEmbeddedPlayerReadersFailClosed() throws Exception {
        Binding target = embedded();
        ClassNode nativeClass = read(target.target().name);
        nativeClass.methods.add(copy(target.assertOnePlayerSite(nativeClass), "anotherPlayerReader"));
        rejectedUnchanged(target, nativeClass);
    }

    @Test public void wrongOverloadIsNotMistakenForTheThreeArgumentCall() throws Exception {
        for (Binding target : targets()) {
            ClassNode nativeClass = read(target.target().name);
            MethodInsnNode call = playerCall(target, nativeClass);
            call.desc = call.desc.replace(";I)", ";II)");
            rejectedUnchanged(target, nativeClass);
        }
    }

    @Test public void embeddedReceiverCannotBorrowANearbyPlayerConstant() throws Exception {
        Binding target = embedded();
        ClassNode nativeClass = read(target.target().name);
        MethodNode method = target.assertOnePlayerSite(nativeClass);
        var player = Arrays.stream(method.instructions.toArray()).filter(insn -> insn instanceof FieldInsnNode f
                && f.owner.equals(FIX_TYPES) && f.name.equals("PLAYER")).findFirst().orElseThrow();
        InsnList change = new InsnList();
        change.add(new InsnNode(Opcodes.POP));
        change.add(new FieldInsnNode(Opcodes.GETSTATIC, FIX_TYPES, "LEVEL", "L" + FIX_TYPES + ";"));
        method.instructions.insert(player, change);
        rejectedUnchanged(target, nativeClass);
    }

    @Test public void publicEmbeddedReadStillDelegatesToTheRedirectedNativeReader() throws Exception {
        Binding target = embedded();
        ClassNode access = read(STORAGE + "LevelStorageSource$LevelStorageAccess");
        assertEquals(1, calls(access).stream().filter(call -> call.owner.equals(target.target().name)
                && call.name.equals("readLevelDataTagFixed")
                && call.desc.equals("(Ljava/nio/file/Path;" + FIXER + ")" + DYNAMIC)).count());
        MethodNode nativeReader = method(target.target(), "readLevelDataTagFixed");
        var updates = calls(nativeReader).stream().filter(call -> call.owner.equals("com/mojang/serialization/Dynamic")
                && call.name.equals("update") && call.desc.equals(
                        "(Ljava/lang/String;Ljava/util/function/Function;)Lcom/mojang/serialization/Dynamic;")).toList();
        assertEquals("Player and WorldGenSettings are separate native updates", 2, updates.size());
        var frames = new org.objectweb.asm.tree.analysis.Analyzer<>(
                new org.objectweb.asm.tree.analysis.SourceInterpreter()).analyze(target.target().name, nativeReader);
        var keys = new java.util.HashSet<String>();
        for (var call : updates) {
            var frame = frames[nativeReader.instructions.indexOf(call)];
            var key = frame.getStack(frame.getStackSize() - 2);
            assertEquals(1, key.insns.size());
            String name = (String) ((org.objectweb.asm.tree.LdcInsnNode) key.insns.iterator().next()).cst;
            keys.add(name);
            if (!name.equals("Player")) continue;
            var function = frame.getStack(frame.getStackSize() - 1);
            assertEquals(1, function.insns.size());
            var lambda = (org.objectweb.asm.tree.InvokeDynamicInsnNode) function.insns.iterator().next();
            MethodNode reader = target.assertOnePlayerSite(target.target());
            assertTrue("The Player key must use the exact PLAYER lambda", Arrays.stream(lambda.bsmArgs)
                    .anyMatch(arg -> arg instanceof org.objectweb.asm.Handle handle
                            && handle.getOwner().equals(target.target().name) && handle.getName().equals(reader.name)
                            && handle.getDesc().equals(reader.desc)));
        }
        assertEquals(Set.of("Player", "WorldGenSettings"), keys);
        assertTrue(target.assertOnePlayerSite(target.target()).name.startsWith("lambda$readLevelDataTagFixed$"));
    }

    private static Method redirect() throws Exception {
        Class<?> mixin = Class.forName("zone.moddev.mc.basepower.mixin.LevelStorageSourceMixin");
        Method method = mixin.getDeclaredMethod("basepower$preserveEmbeddedPlayer",
                DataFixTypes.class, DataFixer.class, Dynamic.class, int.class);
        method.setAccessible(true);
        return method;
    }

    private static CompoundTag invoke(Method redirect, DataFixTypes type, DataFixer fixer,
            CompoundTag input, int version) throws Exception {
        CompoundTag before = input.copy();
        Dynamic<?> result = (Dynamic<?>) redirect.invoke(null, type, fixer, new Dynamic<>(NbtOps.INSTANCE, input), version);
        assertEquals("Native boundary cannot mutate its caller", before, input);
        return (CompoundTag) result.getValue();
    }

    private static MethodInsnNode playerCall(Binding binding, ClassNode nativeClass) throws Exception {
        return calls(binding.assertOnePlayerSite(nativeClass)).stream().filter(binding::matches).findFirst().orElseThrow();
    }

    private static MethodNode copy(MethodNode source, String name) {
        MethodNode result = new MethodNode(source.access, name, source.desc, source.signature, null);
        source.accept(result);
        return result;
    }

    private static void rejectedUnchanged(Binding target, ClassNode nativeClass) throws Exception {
        byte[] before = bytes(nativeClass);
        assertThrows("Unsupported native PLAYER ABI must fail the contract", AssertionError.class,
                () -> target.assertOnePlayerSite(nativeClass));
        assertArrayEquals("Contract checks cannot mutate native classes", before, bytes(nativeClass));
    }

    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static CompoundTag levelData(int version, CompoundTag player) {
        CompoundTag data = new CompoundTag();
        data.putInt("DataVersion", version);
        data.put("Player", player.copy());
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        return root;
    }

    private static CompoundTag legacyPlayer() {
        CompoundTag book = stack("minecraft:enchanted_book", 4, 1, 0);
        CompoundTag enchantment = new CompoundTag();
        enchantment.putString("id", "steamadvantage:steam_capacity");
        enchantment.putShort("lvl", (short) 3);
        ListTag enchantments = new ListTag();
        enchantments.add(enchantment);
        CompoundTag tag = new CompoundTag();
        tag.put("StoredEnchantments", enchantments);
        book.put("tag", tag);
        ListTag inventory = new ListTag();
        inventory.add(book);
        inventory.add(stack("minecraft:wool", 7, 5, 14));
        CompoundTag player = new CompoundTag();
        player.put("Inventory", inventory);
        return player;
    }

    private static CompoundTag stack(String id, int slot, int count, int damage) {
        CompoundTag stack = new CompoundTag();
        stack.putString("id", id);
        stack.putByte("Slot", (byte) slot);
        stack.putByte("Count", (byte) count);
        stack.putShort("Damage", (short) damage);
        return stack;
    }

    private static void assertPlayerContents(CompoundTag player) {
        ListTag items = player.getListOrEmpty("Inventory");
        assertEquals(2, items.size());
        CompoundTag book = items.getCompoundOrEmpty(0);
        assertEquals("minecraft:enchanted_book", book.getStringOr("id", ""));
        assertEquals(4, book.getByteOr("Slot", (byte) 0));
        assertEquals(1, book.getIntOr("count", 0));
        CompoundTag levels = book.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:stored_enchantments");
        assertEquals(Set.of("steamadvantage:steam_capacity"), levels.keySet());
        assertEquals(3, levels.getIntOr("steamadvantage:steam_capacity", 0));
        CompoundTag wool = items.getCompoundOrEmpty(1);
        assertEquals("minecraft:red_wool", wool.getStringOr("id", ""));
        assertEquals(7, wool.getByteOr("Slot", (byte) 0));
        assertEquals(5, wool.getIntOr("count", 0));
    }

    private static final class RecordingFixer implements DataFixer {
        private final DataFixer nativeFixer;
        private final List<String> playerCalls = new ArrayList<>();
        RecordingFixer(DataFixer nativeFixer) { this.nativeFixer = nativeFixer; }
        @Override public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int from, int to) {
            if (type == References.PLAYER) playerCalls.add(from + "->" + to);
            return nativeFixer.update(type, input, from, to);
        }
        @Override public Schema getSchema(int version) { return nativeFixer.getSchema(version); }
    }
}
