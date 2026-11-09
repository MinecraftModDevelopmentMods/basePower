package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.tags.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.BeforeClass;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

public class NativeTagReaderContractTest {
    @BeforeClass public static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test public void optionalSteelBarsUseTheNativeParserWithAndWithoutBaseMetals() throws Exception {
        JsonObject json = new JsonParser().parse(new String(Files.readAllBytes(Paths.get(
                "src/main/resources/data/forge/tags/items/bars/steel.json")), StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse("Do not silently ignore an obsolete optional array", json.has("optional"));
        Tag.Builder builder = Tag.Builder.tag().addFromJson(json, "test");
        Tag<Item> absent = builder.<Item>build(id -> null, id -> null).right().get();
        assertTrue(absent.getValues().isEmpty());
        Tag<Item> present = builder.<Item>build(id -> null, id ->
                id.equals(new ResourceLocation("basemetals:steel_bars")) ? Items.IRON_BARS : null).right().get();
        assertEquals(java.util.Collections.singletonList(Items.IRON_BARS), present.getValues());
    }

    @Test public void configuredAliasesUseANativeDataPackWithoutGlobalTagMutation() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = getClass().getResourceAsStream(
                "/zone/moddev/mc/basepower/recipe/MaterialTags.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(node, 0);
        }
        int policies = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                assertNotEquals("net/minecraft/tags/SerializationTags", call.owner);
                assertNotEquals("net/minecraft/tags/StaticTags", call.owner);
                if (call.owner.equals("net/minecraft/server/packs/repository/Pack")
                        && call.name.equals("create")) {
                    policies++;
                }
            }
        }
        assertEquals(1, policies);
    }
}
