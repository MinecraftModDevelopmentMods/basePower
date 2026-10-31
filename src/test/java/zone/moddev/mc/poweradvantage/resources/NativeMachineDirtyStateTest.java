package zone.moddev.mc.poweradvantage.resources;

import static org.junit.Assert.*;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Wiring guard; stopped-region regression tests prove actual machine persistence. */
public class NativeMachineDirtyStateTest {
    private ClassNode production() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = getClass().getResourceAsStream("/cyano/poweradvantage/api/PoweredEntity.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(node, 0);
        }
        return node;
    }

    private int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.equals(owner) && call.name.equals(name)) count++;
            }
        }
        return count;
    }

    @Test public void timersAndExternalEnergyTransfersInvalidateTheSavedChunk() throws Exception {
        int tick = 0, transfers = 0;
        for (MethodNode method : production().methods) {
            int marks = calls(method, "cyano/poweradvantage/api/PoweredEntity", "markMachineStateUnsaved");
            if (method.name.equals("tick")) tick += marks;
            if (method.name.equals("addEnergy")) transfers += marks;
        }
        assertEquals("A native machine tick must retain progress even without a GUI sync", 1, tick);
        assertEquals("All three energy-transfer return paths must retain the changed buffer", 3, transfers);
    }

    @Test public void dirtyMarkUsesOnlyAnAlreadyLoadedChunkWithoutComparatorNotifications() throws Exception {
        MethodNode mark = null;
        for (MethodNode method : production().methods) if (method.name.equals("markMachineStateUnsaved")) mark = method;
        assertNotNull("Missing native save invalidation", mark);
        assertEquals(1, calls(mark, "net/minecraft/server/level/ServerChunkCache", "getChunkNow"));
        assertEquals(1, calls(mark, "net/minecraft/world/level/chunk/LevelChunk", "setUnsaved"));
        for (AbstractInsnNode instruction : mark.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            String name = ((MethodInsnNode) instruction).name;
            assertFalse("Dirty marking must not load chunks or send comparator/client updates: " + name,
                name.equals("getChunk") || name.equals("getChunkAt") || name.equals("setChanged")
                    || name.equals("sendBlockUpdated") || name.equals("updateNeighbourForOutputSignal"));
        }
    }
}
