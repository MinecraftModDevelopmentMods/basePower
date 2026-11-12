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

/** Native redirect/callback contracts and real player DFU; packaged tests prove live Mixin application. */
public class LegacyPlayerTransformerTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private static Binding loose() throws Exception {
        return binding("basepower", "PlayerDataStorageMixin", "preservePlayer");
    }

    private static PlayerBoundary embedded() throws Exception {
        return new PlayerBoundary(read("zone/moddev/mc/basemetals/migration/Legacy112WorldMigrator"),
                "L" + FIX_TYPES + ";updateToCurrentVersion(" + FIXER + TAG + "I)" + TAG);
    }

    private static List<PlayerBoundary> targets() throws Exception {
        Binding loose = loose();
        return List.of(new PlayerBoundary(loose.target(), loose.invocation()), embedded());
    }

    @Test public void nativeLooseRedirectAndSharedEmbeddedCallbackBindExactTargets() throws Exception {
        for (Binding target : List.of(loose())) {
            assertEquals("*", target.selector());
            assertTrue(target.injector().desc.endsWith("/Redirect;"));
            target.assertAt("INVOKE");
            String payload = TAG;
            assertEquals("L" + FIX_TYPES + ";updateToCurrentVersion(" + FIXER + payload + "I)" + payload,
                    target.invocation());
            assertEquals("(L" + FIX_TYPES + ";" + FIXER + payload + "I)" + payload, target.handler().desc);
            target.assertOnePlayerSite(target.target());
            String wrapper = "updateNativePlayer";
            assertEquals(1, calls(target.handler()).stream().filter(call -> call.owner.equals(
                    "zone/moddev/mc/basepower/migration/LegacyPlayerDataFixes") && call.name.equals(wrapper)).count());
        }
        assertEquals(1, value(loose().injector(), "require", -1));
        assertEquals(1, value(loose().injector(), "allow", -1));
        assertEquals(1, loose().invokeSites(loose().target()));
        PlayerBoundary shared = embedded();
        MethodNode flatten = shared.assertOnePlayerSite(shared.target());
        assertEquals("flattenEmbeddedPlayer", flatten.name);
        assertEquals("(" + TAG + "ILjava/util/List;)" + TAG, flatten.desc);
        var callbacks = calls(flatten).stream().filter(call -> call.owner.equals(
                "zone/moddev/mc/basemetals/api/migration/Legacy112MigrationExtension")
                && call.name.equals("afterPlayerFlattening") && call.desc.equals("(" + TAG + TAG + ")V")).toList();
        assertEquals(1, callbacks.size());
        assertTrue("The companion callback must precede componentization", flatten.instructions.indexOf(callbacks.getFirst())
                < flatten.instructions.indexOf(playerCall(shared, shared.target())));
    }

    @Test public void nativeLooseRedirectExecutesAndDoesNotReconvertCurrentPlayer() throws Exception {
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
        var rejected = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> invoke(redirect, DataFixTypes.LEVEL, fixer, unrelated, current));
        assertTrue(rejected.getCause() instanceof IllegalArgumentException);
        assertEquals("native-forwarding", unrelated.getStringOr("witness", ""));
        assertTrue("Unrelated types cannot enter the player wrapper", fixer.playerCalls.isEmpty());
    }

    @Test public void sharedEmbeddedExtensionExecutesInBothOrdersWithoutRewritingTheSourceLevel() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Class<?> owner = Class.forName("zone.moddev.mc.basemetals.migration.Legacy112WorldMigrator");
        Method flatten = owner.getDeclaredMethod("flattenEmbeddedPlayer", CompoundTag.class, int.class, List.class);
        flatten.setAccessible(true);
        var constructor = BaseMetalsLegacyMigration.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var power = constructor.newInstance();
        for (int old : new int[] {512, 1343}) for (boolean reverse : new boolean[] {false, true}) {
            Path file = temp.newFolder("shared-" + old + "-" + reverse).toPath().resolve("level.dat");
            CompoundTag original = levelData(old, legacyPlayer());
            NbtIo.writeCompressed(original, file);
            CompoundTag source = original.getCompoundOrEmpty("Data").getCompoundOrEmpty("Player").copy();
            power.prepareData(source, old, zone.moddev.mc.basemetals.api.migration.Legacy112MigrationExtension.Payload.PLAYER);
            CompoundTag prepared = source.copy();
            List<String> calls = new ArrayList<>();
            var observer = new zone.moddev.mc.basemetals.api.migration.Legacy112MigrationExtension() {
                @Override public void afterPlayerFlattening(CompoundTag before, CompoundTag result) {
                    assertEquals(prepared, before);
                    assertEquals(1, result.getListOrEmpty("Inventory").getCompoundOrEmpty(0).getByteOr("Count", (byte) 0));
                    assertFalse(result.getListOrEmpty("Inventory").getCompoundOrEmpty(0).contains("components"));
                    calls.add("before-components");
                }
            };
            var extensions = reverse ? List.of(observer, power) : List.of(power, observer);
            CompoundTag migrated = (CompoundTag) flatten.invoke(null, source.copy(), old, extensions);
            assertEquals(prepared, source);
            assertEquals(List.of("before-components"), calls);
            assertPlayerContents(migrated);
            int current = SharedConstants.getCurrentVersion().dataVersion().version();
            assertEquals(4903, current);
            assertEquals(current, migrated.getIntOr("DataVersion", 0));
            assertEquals(original, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
            calls.clear();
            assertEquals(migrated, flatten.invoke(null, migrated.copy(), current, extensions));
            assertTrue("Current host player cannot re-enter the shared legacy callback", calls.isEmpty());
            assertEquals(original, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
        }
    }

    @Test public void renamedNativePlayerMethodNamesDoNotAffectBoundaryMatching() throws Exception {
        for (PlayerBoundary target : targets()) {
            ClassNode nativeClass = read(target.target().name);
            target.assertOnePlayerSite(nativeClass).name = "renamedPlayerLambda";
            assertEquals("renamedPlayerLambda", target.assertOnePlayerSite(nativeClass).name);
        }
    }

    @Test public void absentCallFailsClosedWithoutAcceptingOtherOwnersOrNames() throws Exception {
        for (PlayerBoundary target : targets()) for (boolean owner : new boolean[] {true, false}) {
            ClassNode nativeClass = read(target.target().name);
            MethodInsnNode call = playerCall(target, nativeClass);
            if (owner) call.owner = "other/UnrelatedNBTUtil";
            else call.name = "renamedNativePlayerUpdate";
            rejectedUnchanged(target, nativeClass);
        }
    }

    @Test public void duplicateCallsInOneReaderFailClosed() throws Exception {
        for (PlayerBoundary target : targets()) {
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
        PlayerBoundary target = embedded();
        ClassNode nativeClass = read(target.target().name);
        nativeClass.methods.add(copy(target.assertOnePlayerSite(nativeClass), "anotherPlayerReader"));
        rejectedUnchanged(target, nativeClass);
    }

    @Test public void wrongOverloadIsNotMistakenForTheThreeArgumentCall() throws Exception {
        for (PlayerBoundary target : targets()) {
            ClassNode nativeClass = read(target.target().name);
            MethodInsnNode call = playerCall(target, nativeClass);
            call.desc = call.desc.replace(";I)", ";II)");
            rejectedUnchanged(target, nativeClass);
        }
    }

    @Test public void embeddedReceiverCannotBorrowANearbyPlayerConstant() throws Exception {
        PlayerBoundary target = embedded();
        ClassNode nativeClass = read(target.target().name);
        MethodNode method = target.assertOnePlayerSite(nativeClass);
        var player = playerReceiver(nativeClass, method, playerCall(target, nativeClass));
        assertNotNull(player);
        InsnList change = new InsnList();
        change.add(new InsnNode(Opcodes.POP));
        change.add(new FieldInsnNode(Opcodes.GETSTATIC, FIX_TYPES, "LEVEL", "L" + FIX_TYPES + ";"));
        method.instructions.insert(player, change);
        rejectedUnchanged(target, nativeClass);
    }

    @Test public void selectedWorldHostSeedDelegatesToSharedFlatteningBeforeFileFixing() throws Exception {
        Binding capture = binding("basemetals", "LevelStorageSourceMixin", "prepareWorld");
        assertSelectedWorld(capture);
        ClassNode legacyStorage = read(STORAGE + "LevelStorageSource");
        assertFalse(legacyStorage.methods.stream().anyMatch(method -> method.name.equals("readLevelDataTagFixed")));
        ClassNode powerStorage = read("zone/moddev/mc/basepower/mixin/LevelStorageSourceMixin");
        assertFalse("Removed embedded redirect must not survive as an unbound handler", powerStorage.methods.stream()
                .anyMatch(method -> method.name.equals("basepower$preserveEmbeddedPlayer")));
        String hooks = "zone/moddev/mc/basemetals/migration/LegacyWorldDataHook";
        String migrator = embedded().target().name;
        MethodNode prepare = method(read(hooks), "prepareSelectedWorld");
        var seedCall = calls(prepare).stream().filter(call -> call.owner.equals(hooks)
                && call.name.equals("preserveEmbeddedPlayer") && call.desc.equals("(Ljava/nio/file/Path;)V")).findFirst().orElseThrow();
        var migrateCall = calls(prepare).stream().filter(call -> call.owner.equals(migrator)
                && call.name.equals("migrateIfNeeded") && call.desc.equals("(Ljava/nio/file/Path;)V")).findFirst().orElseThrow();
        assertTrue("Legacy preparation precedes host componentization", prepare.instructions.indexOf(migrateCall)
                < prepare.instructions.indexOf(seedCall));
        MethodNode seed = method(read(hooks), "preserveEmbeddedPlayer");
        assertEquals(1, calls(seed).stream().filter(call -> call.owner.equals(migrator)
                && call.name.equals("flattenEmbeddedPlayer") && call.desc.equals("(" + TAG + "I)" + TAG)).count());
        assertEquals("Both old and relocated loose-player paths have authority", 2, calls(seed).stream()
                .filter(call -> call.owner.equals("java/nio/file/Files") && call.name.equals("exists")).count());
        ClassNode companion = read("zone/moddev/mc/basepower/migration/BaseMetalsLegacyMigration");
        assertEquals(1, calls(method(companion, "afterPlayerFlattening")).stream().filter(call -> call.owner.equals(
                "zone/moddev/mc/basepower/migration/LegacyPlayerDataFixes") && call.name.equals("preserveNames")
                && call.desc.equals("(Lnet/minecraft/nbt/Tag;Lnet/minecraft/nbt/Tag;)V")).count());
    }

    private static Method redirect() throws Exception {
        Method method = LegacyPlayerDataFixes.class.getDeclaredMethod("updateNativePlayer",
                DataFixTypes.class, DataFixer.class, CompoundTag.class, int.class);
        method.setAccessible(true);
        return method;
    }

    private static CompoundTag invoke(Method redirect, DataFixTypes type, DataFixer fixer,
            CompoundTag input, int version) throws Exception {
        CompoundTag before = input.copy();
        CompoundTag result = (CompoundTag) redirect.invoke(null, type, fixer, input, version);
        assertEquals("Native boundary cannot mutate its caller", before, input);
        return result;
    }

    private static MethodInsnNode playerCall(PlayerBoundary binding, ClassNode nativeClass) throws Exception {
        return calls(binding.assertOnePlayerSite(nativeClass)).stream().filter(binding::matches).findFirst().orElseThrow();
    }

    private static MethodNode copy(MethodNode source, String name) {
        MethodNode result = new MethodNode(source.access, name, source.desc, source.signature, null);
        source.accept(result);
        return result;
    }

    private static void rejectedUnchanged(PlayerBoundary target, ClassNode nativeClass) throws Exception {
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
