package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

public class NativePlatformContractTest {
    @Test public void onlyPreDatafixMigrationStillUsesACoremod() throws Exception {
        String json = new String(Files.readAllBytes(zone.moddev.mc.advantageworks.unit.TestProject.path("src/main/resources/META-INF/coremods.json")), StandardCharsets.UTF_8);
        JsonObject coremods = new JsonParser().parse(json).getAsJsonObject();
        assertEquals(1, coremods.size());
        assertEquals("coremods/poweradvantage_118_migration.js", coremods.get("poweradvantage_118_migration").getAsString());
        for (String removed : new String[] {"oil_renderer", "material_tags", "parts_loot"})
            assertFalse(Files.exists(zone.moddev.mc.advantageworks.unit.TestProject.path("src/main/resources/coremods/poweradvantage_" + removed + ".js")));
    }

    @Test public void pinnedForgeFiresTagEventAfterBindingNativeCollections() throws Exception {
        ClassNode node = read("net/minecraft/server/ReloadableServerResources");
        MethodNode bind = node.methods.stream().filter(method -> method.name.equals("updateRegistryTags")
                        && method.desc.contains("TagManager$LoadResult"))
                .findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(bind.instructions.toArray()).anyMatch(instruction ->
                instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/core/Registry")
                        && call.name.equals("bindTags")));
        int events = 0;
        for (MethodNode method : node.methods) {
            boolean installed = false;
            boolean tagEventConstructed = false;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.owner.equals("java/lang/Iterable") || call.owner.equals("java/util/List")) {
                        if (call.name.equals("forEach")) installed = true;
                    }
                }
                if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode type
                        && type.getOpcode() == org.objectweb.asm.Opcodes.NEW
                        && type.desc.equals("net/neoforged/neoforge/event/TagsUpdatedEvent")) {
                    assertTrue("Bind tags before notifying observers", installed);
                    tagEventConstructed = true;
                }
                if (instruction instanceof MethodInsnNode call && tagEventConstructed
                        && call.owner.equals("net/neoforged/bus/api/IEventBus") && call.name.equals("post")) {
                    events++;
                    tagEventConstructed = false;
                }
            }
        }
        assertEquals(1, events);
    }

    @Test public void nativeLootLoaderCallsForgeEventAndNotTheRemovedAdvantageBridge() throws Exception {
        ClassNode node = read("net/minecraft/world/level/storage/loot/LootDataType");
        int calls = 0;
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call) {
                assertNotEquals("zone/moddev/mc/basepower/recipe/PartsLoot", call.owner);
                if (call.owner.equals("net/neoforged/neoforge/event/EventHooks")
                        && call.name.equals("loadLootTable")) calls++;
            }
        }
        assertEquals(1, calls);
    }

    private static ClassNode read(String name) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = zone.moddev.mc.advantageworks.unit.NativeClassResources.open("/" + name + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(node, 0);
        }
        return node;
    }
}
