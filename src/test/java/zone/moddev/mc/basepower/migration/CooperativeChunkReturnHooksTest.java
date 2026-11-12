package zone.moddev.mc.basepower.migration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import static org.junit.Assert.*;

/** Execute the real handlers: annotation coverage alone missed an early-return data loss. */
public class CooperativeChunkReturnHooksTest {
    @Test public void bothOrdersFinishTheNativeResultWithoutCancellingTheNextHandler() throws Exception {
        for (boolean metalsFirst : new boolean[] {true, false}) {
            CompoundTag root = new CompoundTag();
            root.putInt("xPos", 0);
            root.putInt("zPos", 0);
            root.put("entities", new ListTag());
            CompoundTag turbine = new CompoundTag();
            turbine.putString("id", "example:turbine");
            turbine.putIntArray("parentPos", new int[] {8, 5, 8});
            ListTag staged = new ListTag();
            staged.add(turbine);
            root.put("AdvantageLegacyEntityDataFix", staged);
            String marker = metalsMarker();
            root.putBoolean(marker, true);
            String[] order = metalsFirst ? new String[] {"basemetals", "basepower"}
                    : new String[] {"basepower", "basemetals"};
            for (String mod : order) {
                CallbackInfoReturnable<CompoundTag> callback = new CallbackInfoReturnable<>("upgradeChunkTag", true, root);
                invokeHandler(mod, root, callback);
                assertFalse(mod + " skipped the remaining migration handlers", callback.isCancelled());
                assertSame(root, callback.getReturnValue());
            }
            assertEquals(turbine, root.getListOrEmpty("entities").getCompoundOrEmpty(0));
            assertEquals(1, root.getListOrEmpty("entities").size());
            assertFalse(root.contains("AdvantageLegacyEntityDataFix"));
            assertFalse(root.contains(marker));
            assertEquals("full", root.getString("Status").orElseThrow());
        }
    }

    private static String metalsMarker() throws Exception {
        var type = Class.forName("zone.moddev.mc.basemetals.migration.LegacyWorldDataHook");
        var field = type.getDeclaredField("PRESERVE_CHUNK_MARKER");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static void invokeHandler(String mod, CompoundTag root, CallbackInfoReturnable<CompoundTag> callback)
            throws Exception {
        String name = "zone/moddev/mc/" + mod + "/mixin/SimpleRegionStorageMixin";
        ClassNode node = new ClassNode();
        try (var input = CooperativeChunkReturnHooksTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull("Missing real migration mixin " + mod, input);
            new ClassReader(input).accept(node, 0);
        }
        // Mixin classes are abstract templates. Make only the test copy instantiable;
        // the original handler bytecode and callback semantics are left unchanged.
        node.access = (node.access & ~Opcodes.ACC_ABSTRACT) | Opcodes.ACC_PUBLIC;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        class FixtureLoader extends ClassLoader {
            FixtureLoader() { super(CooperativeChunkReturnHooksTest.class.getClassLoader()); }
            Class<?> define(byte[] bytes) { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
        }
        Class<?> type = new FixtureLoader().define(writer.toByteArray());
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Method handler = type.getDeclaredMethod(mod + "$finishChunk", CompoundTag.class, int.class,
                CompoundTag.class, int.class, CallbackInfoReturnable.class);
        assertThrows("The removed three-argument chunk ABI must not satisfy this test", NoSuchMethodException.class,
                () -> type.getDeclaredMethod(mod + "$finishChunk", CompoundTag.class, int.class,
                        CompoundTag.class, CallbackInfoReturnable.class));
        handler.setAccessible(true);
        handler.invoke(constructor.newInstance(), root, 0, null,
                net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(), callback);
    }
}
