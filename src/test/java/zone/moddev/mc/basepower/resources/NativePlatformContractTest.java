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
    @Test public void allMigrationHooksUseRequiredNativeMixinsAndNoJavaScriptCoremod() throws Exception {
        assertFalse(Files.exists(Paths.get("src/main/resources/META-INF/coremods.json")));
        JsonObject config = JsonParser.parseString(Files.readString(Paths.get("src/main/resources/basepower.mixins.json")))
                .getAsJsonObject();
        assertTrue(config.get("required").getAsBoolean());
        assertEquals("JAVA_21", config.get("compatibilityLevel").getAsString());
        assertEquals(1, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
        java.util.Set<String> hooks = new java.util.HashSet<>();
        config.getAsJsonArray("mixins").forEach(value -> hooks.add(value.getAsString()));
        assertEquals(java.util.Set.of("BlockStateDataMixin", "LevelStorageSourceMixin", "PlayerDataStorageMixin",
                "SimpleRegionStorageMixin", "WorldGenRegionMixin"), hooks);
        for (String name : hooks) assertNotNull(read("zone/moddev/mc/basepower/mixin/" + name));
    }

    @Test public void nativeRecipeAndAdvancementLoadingShareThePostponedTagContext() throws Exception {
        ClassNode node = read("net/minecraft/server/ReloadableServerResources");
        MethodNode constructor = node.methods.stream().filter(method -> method.name.equals("<init>"))
                .findFirst().orElseThrow();
        int contexts = 0, recipes = 0, advancements = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("<init>")) {
                if (call.owner.equals("net/minecraftforge/common/crafting/conditions/ConditionContext")) contexts++;
                if (call.owner.equals("net/minecraft/world/item/crafting/RecipeManager")) {
                    assertEquals(1, contexts);
                    assertTrue(call.desc.contains("ICondition$IContext"));
                    recipes++;
                }
                if (call.owner.equals("net/minecraft/server/ServerAdvancementManager")) {
                    assertEquals(1, contexts);
                    assertTrue(call.desc.contains("ICondition$IContext"));
                    advancements++;
                }
            }
        }
        assertEquals(1, contexts);
        assertEquals(1, recipes);
        assertEquals(1, advancements);
    }

    @Test public void nativeLootLoaderCallsForgeEventAndNotTheRemovedAdvantageBridge() throws Exception {
        ClassNode node = read("net/minecraftforge/common/ForgeHooks");
        int calls = 0;
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call) {
                assertNotEquals("zone/moddev/mc/basepower/recipe/PartsLoot", call.owner);
                if (call.owner.equals("net/minecraftforge/event/ForgeEventFactory")
                        && call.name.equals("onLoadLootTable")) calls++;
            }
        }
        assertEquals(1, calls);
    }

    private static ClassNode read(String name) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = NativePlatformContractTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(node, 0);
        }
        return node;
    }
}
