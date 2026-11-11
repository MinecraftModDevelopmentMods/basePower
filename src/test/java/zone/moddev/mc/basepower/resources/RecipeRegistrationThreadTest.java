package zone.moddev.mc.basepower.resources;

import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

public class RecipeRegistrationThreadTest {
    @Test public void sharedForgeConditionsUseTheNativeDeferredRegistry() throws Exception {
        String conditions = "zone/moddev/mc/basepower/recipe/RecipeConditions";
        int subscriptions = 0;
        for (MethodNode method : read("zone/moddev/mc/basepower/PowerAdvantage").methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(conditions) && call.name.equals("register")) {
                    assertEquals("Subscribe during mod construction, before the registry event", "<init>", method.name);
                    assertEquals("(Lnet/minecraftforge/eventbus/api/bus/BusGroup;)V", call.desc);
                    subscriptions++;
                }
            }
        }
        assertEquals(1, subscriptions);
        ClassNode codecs = read(conditions);
        MethodNode register = codecs.methods.stream().filter(method -> method.name.equals("register"))
                .findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(register.instructions.toArray()).anyMatch(instruction ->
                instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraftforge/registries/DeferredRegister")
                        && call.name.equals("register") && call.desc.equals("(Lnet/minecraftforge/eventbus/api/bus/BusGroup;)V")));
        assertTrue(codecs.methods.stream().flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .anyMatch(instruction -> instruction instanceof org.objectweb.asm.tree.FieldInsnNode field
                        && field.owner.equals("net/minecraftforge/registries/ForgeRegistries$Keys")
                        && field.name.equals("CONDITION_SERIALIZERS")));
    }

    private ClassNode read(String type) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = getClass().getResourceAsStream("/" + type + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(node, 0);
        }
        return node;
    }
}
