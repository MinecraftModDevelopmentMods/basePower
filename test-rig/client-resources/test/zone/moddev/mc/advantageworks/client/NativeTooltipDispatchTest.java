package zone.moddev.mc.advantageworks.client;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import static org.junit.Assert.*;

/** Actual mapped native dispatch and the compiled probe hooks, without a GPU. */
public class NativeTooltipDispatchTest {
    private static final String GRAPHICS = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String SIX = "(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;Lnet/minecraft/world/item/ItemStack;II)V";
    private static final String SEVEN = SIX.replace("II)V", "IIZ)V");
    private static final String SCREEN = "zone/moddev/mc/advantageworks/client/MachineGuiLayoutProbe$RecordingScreen";

    private static ClassNode read(String name) throws Exception {
        try (var stream = NativeTooltipDispatchTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull("Missing actual compiled class " + name, stream);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    @Test public void nativeScreenCallsSevenArgumentsDirectlyAndSixIsOnlyADelegatingConvenience() throws Exception {
        List<String> calls = new ArrayList<>();
        for (var method : read("net/minecraft/client/gui/screens/inventory/AbstractContainerScreen").methods)
            if (method.name.equals("extractTooltip")) for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call && call.owner.equals(GRAPHICS)
                        && call.name.equals("setTooltipForNextFrame")) calls.add(call.desc);
        assertEquals(List.of(SEVEN), calls);
        boolean delegated = false;
        for (var method : read(GRAPHICS).methods) if (method.name.equals("setTooltipForNextFrame") && method.desc.equals(SIX))
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.desc.equals(SEVEN)) {
                assertEquals(Opcodes.ICONST_0, instruction.getPrevious().getOpcode());
                delegated = true;
            }
        assertTrue("Actual native six-argument method must delegate to seven with false", delegated);
    }

    @Test public void compiledPositiveHookForwardsOriginalBooleanAndRetainsIndependentOldSixCounter() throws Exception {
        List<String> graphics = new ArrayList<>();
        for (var method : read(SCREEN).methods) if (method.name.equals("recordingGraphics"))
            for (var instruction : method.instructions)
                if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.startsWith(SCREEN + "$"))
                    graphics.add(type.desc);
        assertEquals(1, graphics.size());
        int oldHooks = 0, nativeHooks = 0;
        for (String name : graphics) for (var method : read(name).methods) if (method.name.equals("setTooltipForNextFrame")) {
            if (method.desc.equals(SIX)) oldHooks++;
            if (method.desc.equals(SEVEN)) {
                nativeHooks++;
                boolean forwarded = false;
                for (var instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(GRAPHICS) && call.desc.equals(SEVEN)) {
                        assertEquals(Opcodes.INVOKESPECIAL, call.getOpcode());
                        assertTrue(instruction.getPrevious() instanceof VarInsnNode);
                        VarInsnNode argument = (VarInsnNode) instruction.getPrevious();
                        assertEquals(Opcodes.ILOAD, argument.getOpcode());
                        assertEquals(7, argument.var);
                        forwarded = true;
                    }
                assertTrue("Positive hook must forward the unmodified native boolean", forwarded);
            }
        }
        assertEquals(1, oldHooks);
        assertEquals(1, nativeHooks);
    }
}
