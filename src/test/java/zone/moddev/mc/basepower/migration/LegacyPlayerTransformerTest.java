package zone.moddev.mc.basepower.migration;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.validation.DirectoryValidator;
import net.minecraftforge.fml.ModList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

public class LegacyPlayerTransformerTest {
    private static final String NATIVE = "net/minecraft/util/datafix/DataFixTypes";
    private static final String WRAPPER = "zone/moddev/mc/basepower/migration/LegacyPlayerDataFixes";
    private static final String STORAGE = "net/minecraft/world/level/storage/";
    private static final String TAG = "Lnet/minecraft/nbt/CompoundTag;";
    private static final String DYNAMIC = "Lcom/mojang/serialization/Dynamic;";
    private static final String FIXER = "Lcom/mojang/datafixers/DataFixer;";
    private static final String READER_DESC = "(Ljava/nio/file/Path;" + FIXER + ")" + DYNAMIC;
    private static final PlayerHook LOOSE = new PlayerHook("PlayerDataStorage", "legacy_player_file", TAG, "updateNativePlayer");
    private static final PlayerHook EMBEDDED = new PlayerHook("LevelStorageSource", "legacy_world_info", DYNAMIC,
            "updateNativeEmbeddedPlayer");
    private static final List<PlayerHook> TARGETS = List.of(LOOSE, EMBEDDED);

    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void exactNativeLoosePlayerLoadAndEmbeddedUpdateArePatched() throws Exception {
        for (PlayerHook target : TARGETS) {
            ClassNode node = target.read();
            MethodNode containing = nativePlayerMethod(node, target);
            assertTrue(containing.name, containing.name.startsWith(target == LOOSE ? "lambda$load$" : "lambda$readLevelDataTagFixed$"));
            int otherNativeUpdates = target.nativeCalls(node).size() - 1;
            target.transform(node);
            assertEquals(otherNativeUpdates, target.nativeCalls(node).size());
            assertEquals(1, LegacyTransformerOrderTest.calls(node, WRAPPER, target.wrapperName(), target.wrapperDesc()));
            assertEquals(1, LegacyTransformerOrderTest.calls(containing, WRAPPER, target.wrapperName(), target.wrapperDesc()));
        }
    }

    @Test public void transformedNativeEmbeddedReaderExecutesAndDoesNotReconvertCurrentPlayer() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ClassNode node = EMBEDDED.read();
        EMBEDDED.transform(node);
        byte[] transformed = bytes(node);
        String name = node.name.replace('/', '.');
        Class<?> storage = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(name, transformed, 0, transformed.length); }

            @Override protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                // Keep the native access object's nest in the transformed reader's loader.
                if (!requested.startsWith(name + "$")) return super.loadClass(requested, resolve);
                synchronized (getClassLoadingLock(requested)) {
                    Class<?> type = findLoadedClass(requested);
                    if (type == null) {
                        try (InputStream input = LegacyTransformerOrderTest.resource("/" + requested.replace('.', '/') + ".class")) {
                            byte[] data = input.readAllBytes();
                            type = defineClass(requested, data, 0, data.length);
                        } catch (IOException e) {
                            throw new ClassNotFoundException(requested, e);
                        }
                    }
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        }.define();
        Path saves = temp.newFolder("saves").toPath();
        RecordingFixer fixer = new RecordingFixer(DataFixers.getDataFixer());
        Object validator = storage.getMethod("parseValidator", Path.class).invoke(null, saves.resolve("allowed_symlinks.txt"));
        Object source = storage.getConstructor(Path.class, Path.class, DirectoryValidator.class, DataFixer.class)
                .newInstance(saves, saves.resolveSibling("backups"), validator, fixer);

        // An empty native ModList supplies the metadata hook's normal loader context in plain JUnit.
        ModList previous = ModList.get();
        if (previous == null) ModList.of(List.of(), List.of());
        try {
            int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            for (int old : new int[] {512, 1343}) {
                String world = "legacy-" + old;
                Path levelDat = saves.resolve(world).resolve("level.dat");
                CompoundTag original = levelData(old, legacyPlayer());
                CompoundTag migrated;
                try (AutoCloseable access = openWorld(storage, source, world)) {
                    NbtIo.writeCompressed(original, levelDat);
                    fixer.playerCalls.clear();
                    migrated = readPlayer(access);
                    assertPlayerContents(migrated);
                    int boundary = 3465; // Minecraft 1.20.1, before item components.
                    assertEquals(old < 704 ? List.of(old + "->704", "704->" + boundary, boundary + "->" + current)
                            : List.of(old + "->" + boundary, boundary + "->" + current), fixer.playerCalls);
                    assertEquals(original, NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap()));
                }
                CompoundTag saved = levelData(current, migrated);
                NbtIo.writeCompressed(saved, levelDat);
                try (AutoCloseable access = openWorld(storage, source, world)) {
                    fixer.playerCalls.clear();
                    assertEquals(migrated, readPlayer(access));
                    assertTrue("Current player data must not re-enter legacy DFU", fixer.playerCalls.isEmpty());
                    assertEquals(saved, NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap()));
                }
            }
        } finally {
            if (previous == null) {
                Field instance = ModList.class.getDeclaredField("INSTANCE");
                instance.setAccessible(true);
                instance.set(null, null);
            }
        }
    }

    @Test public void renamedPlayerCallAndNativeLambdaNamesDoNotAffectMatching() throws Exception {
        for (PlayerHook target : TARGETS) {
            ClassNode node = target.read();
            MethodNode method = nativePlayerMethod(node, target);
            nativePlayerCall(node, target).name = "renamedNativePlayerUpdate";
            method.name = "renamedPlayerLambda";
            target.transform(node);
            assertEquals(1, LegacyTransformerOrderTest.calls(method, WRAPPER, target.wrapperName(), target.wrapperDesc()));
        }
    }

    @Test public void absentCallFailsClosedWithoutPatchingOtherOwners() throws Exception {
        for (PlayerHook target : TARGETS) {
            ClassNode node = target.read();
            nativePlayerCall(node, target).owner = "other/UnrelatedNBTUtil";
            rejectedUnchanged(node, target);
        }
    }

    @Test public void duplicateCallsFailClosedBeforeEitherIsRewritten() throws Exception {
        for (PlayerHook target : TARGETS) {
            ClassNode node = target.read();
            MethodNode method = nativePlayerMethod(node, target);
            MethodInsnNode call = nativePlayerCall(node, target);
            method.instructions.insertBefore(call, new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
            rejectedUnchanged(node, target);
        }
    }

    @Test public void duplicateEmbeddedPlayerSitesFailClosedBeforeAnyReaderIsRewritten() throws Exception {
        ClassNode node = EMBEDDED.read();
        MethodNode original = nativePlayerMethod(node, EMBEDDED);
        MethodNode extra = new MethodNode(original.access, "anotherPlayerReader", original.desc, original.signature, null);
        original.accept(extra);
        node.methods.add(extra);
        rejectedUnchanged(node, EMBEDDED);
    }

    @Test public void wrongOverloadIsNotMistakenForTheThreeArgumentCall() throws Exception {
        for (PlayerHook target : TARGETS) {
            ClassNode node = target.read();
            nativePlayerCall(node, target).desc = target.desc().replace(";I)", ";II)");
            rejectedUnchanged(node, target);
        }
    }

    @Test public void embeddedScanCannotBorrowAnotherDataFixTypeAfterPlayer() throws Exception {
        ClassNode node = EMBEDDED.read();
        MethodNode method = nativePlayerMethod(node, EMBEDDED);
        AbstractInsnNode playerType = Arrays.stream(method.instructions.toArray())
                .filter(LegacyPlayerTransformerTest::loadsPlayerType).findFirst().orElseThrow();
        InsnList changeType = new InsnList();
        changeType.add(new InsnNode(Opcodes.POP));
        changeType.add(new FieldInsnNode(Opcodes.GETSTATIC, NATIVE, "LEVEL", "L" + NATIVE + ";"));
        method.instructions.insert(playerType, changeType);
        rejectedUnchanged(node, EMBEDDED);
    }

    @Test public void publicEmbeddedReadStillDelegatesToThePatchedNativeReader() throws Exception {
        ClassNode node = EMBEDDED.read();
        EMBEDDED.transform(node);
        ClassNode access = LegacyTransformerOrderTest.nativeClass(STORAGE + "LevelStorageSource$LevelStorageAccess");
        assertEquals(1, LegacyTransformerOrderTest.calls(access, node.name, "readLevelDataTagFixed", READER_DESC));
        assertEquals(1, LegacyTransformerOrderTest.calls(node, WRAPPER, EMBEDDED.wrapperName(), EMBEDDED.wrapperDesc()));
    }

    private static MethodNode nativePlayerMethod(ClassNode node, PlayerHook target) {
        List<MethodNode> methods = node.methods.stream().filter(method ->
                target.nativeCalls(method).size() == 1 && Arrays.stream(method.instructions.toArray()).anyMatch(
                        LegacyPlayerTransformerTest::loadsPlayerType)).toList();
        assertEquals("Expected one Forge 50 PLAYER reader in " + node.name, 1, methods.size());
        return methods.getFirst();
    }

    private static MethodInsnNode nativePlayerCall(ClassNode node, PlayerHook target) {
        return target.nativeCalls(nativePlayerMethod(node, target)).getFirst();
    }

    private static boolean loadsPlayerType(AbstractInsnNode instruction) {
        return instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals(NATIVE) && field.name.equals("PLAYER");
    }

    private static void rejectedUnchanged(ClassNode node, PlayerHook target) throws Exception {
        byte[] before = bytes(node);
        LegacyTransformerOrderTest.expectRejection(() -> target.transform(node),
                target == LOOSE ? "exactly one native player" : "embedded player");
        assertArrayEquals("Rejection must precede any bytecode edit", before, bytes(node));
    }

    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static CompoundTag levelData(int version, CompoundTag player) {
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.putInt("DataVersion", version);
        data.put("Player", player.copy());
        root.put("Data", data);
        return root;
    }

    private static CompoundTag legacyPlayer() {
        CompoundTag book = legacyStack("minecraft:enchanted_book", 4, 1, 0);
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
        inventory.add(legacyStack("minecraft:wool", 7, 5, 14));
        CompoundTag player = new CompoundTag();
        player.put("Inventory", inventory);
        return player;
    }

    private static CompoundTag legacyStack(String id, int slot, int count, int damage) {
        CompoundTag stack = new CompoundTag();
        stack.putString("id", id);
        stack.putByte("Slot", (byte) slot);
        stack.putByte("Count", (byte) count);
        stack.putShort("Damage", (short) damage);
        return stack;
    }

    private static CompoundTag readPlayer(AutoCloseable access) throws Exception {
        Dynamic<?> fixed = (Dynamic<?>) access.getClass().getMethod("getDataTag").invoke(access);
        return ((CompoundTag) fixed.getValue()).getCompound("Player");
    }

    private static AutoCloseable openWorld(Class<?> storage, Object source, String world) throws Exception {
        return (AutoCloseable) storage.getMethod("createAccess", String.class).invoke(source, world);
    }

    private static void assertPlayerContents(CompoundTag player) {
        ListTag items = player.getList("Inventory", Tag.TAG_COMPOUND);
        assertEquals(2, items.size());
        CompoundTag book = items.getCompound(0);
        assertEquals("minecraft:enchanted_book", book.getString("id"));
        assertEquals(4, book.getByte("Slot"));
        assertEquals(1, book.getInt("count"));
        CompoundTag levels = book.getCompound("components").getCompound("minecraft:stored_enchantments").getCompound("levels");
        assertEquals(3, levels.getInt("steamadvantage:steam_capacity"));
        CompoundTag wool = items.getCompound(1);
        assertEquals("minecraft:red_wool", wool.getString("id"));
        assertEquals(7, wool.getByte("Slot"));
        assertEquals(5, wool.getInt("count"));
    }

    private record PlayerHook(String nativeName, String coremodName, String payload, String wrapperName) {
        String desc() { return "(" + FIXER + payload + "I)" + payload; }
        String wrapperDesc() { return "(L" + NATIVE + ";" + desc().substring(1); }

        ClassNode read() throws IOException {
            return LegacyTransformerOrderTest.nativeClass(STORAGE + nativeName);
        }

        void transform(ClassNode node) throws Exception {
            LegacyTransformerOrderTest.transform(node, coremodName, false);
        }

        List<MethodInsnNode> nativeCalls(MethodNode method) {
            return Arrays.stream(method.instructions.toArray())
                    .filter(insn -> insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && call.owner.equals(NATIVE) && call.desc.equals(desc()))
                    .map(MethodInsnNode.class::cast).toList();
        }

        List<MethodInsnNode> nativeCalls(ClassNode node) {
            return node.methods.stream().flatMap(method -> nativeCalls(method).stream()).toList();
        }
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
