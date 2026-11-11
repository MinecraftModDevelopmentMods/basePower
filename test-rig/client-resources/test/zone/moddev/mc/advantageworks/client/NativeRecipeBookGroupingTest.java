package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real native packet bytes and ClientRecipeBook collections; no graphics-pass claim. */
public class NativeRecipeBookGroupingTest {
    private static RegistryAccess registries;
    private static ContextMap context;

    @BeforeClass public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        context = RecipeBookGroupingProbe.context(registries);
    }

    @Test public void actualClientBuilderKeepsVariantsTogetherAndOutputsSeparate() {
        var verified = RecipeBookGroupingProbe.verifyPacket(registries, context, bytes(entries(false, false)), manifest());
        assertEquals(3, verified.recipes());
        assertEquals(3, verified.displays());
        assertEquals(2, verified.book().getCollections().size());
        assertEquals(List.of(1, 2), verified.book().getCollections().stream()
                .map(cell -> cell.getRecipes().size()).sorted().toList());
    }

    @Test public void splitVariantGroupIsRejected() {
        rejected(bytes(entries(true, false)), manifest(), "Missing/split native cell");
    }

    @Test public void mixedOutputNumericGroupIsRejected() {
        rejected(bytes(entries(false, true)), manifest(), "Native numeric group mixes output items");
    }

    @Test public void oldPacketTypeIsRejected() {
        JsonObject manifest = manifest();
        manifest.addProperty("bookPacketType", "ClientboundUpdateRecipesPacket");
        rejected(bytes(entries(false, false)), manifest, "native display-packet protocol");
    }

    @Test public void duplicateManifestDisplayIdIsRejected() {
        JsonObject manifest = manifest();
        manifest.getAsJsonArray("bookRecipes").get(1).getAsJsonObject().getAsJsonArray("displayIds").set(0,
                new com.google.gson.JsonPrimitive(1));
        rejected(bytes(entries(false, false)), manifest, "Duplicate/invalid display ID");
    }

    @Test public void missingVariantIsRejected() {
        rejected(bytes(entries(false, false).subList(0, 2)), manifest(), "Missing network displays");
    }

    @Test public void alteredOutputCountIsRejected() {
        JsonObject manifest = manifest();
        manifest.getAsJsonArray("bookRecipes").get(0).getAsJsonObject().addProperty("count", 2);
        rejected(bytes(entries(false, false)), manifest, "count mismatch");
    }

    @Test public void trailingNetworkBytesAreRejected() {
        byte[] original = bytes(entries(false, false));
        rejected(Arrays.copyOf(original, original.length + 1), manifest(), "Trailing recipe packet bytes");
    }

    @Test public void namedIngredientRequiresActualNativeTagPacketBeforeRecipeDecode() {
        byte[] tags = tagPacket();
        assertEquals(1, RecipeBookGroupingProbe.applyItemTags(registries, tags));
        var key = TagKey.create(Registries.ITEM, Identifier.parse("advantageclientresources:native_fixture"));
        var display = entry(1, 3, new ItemStack(Items.APPLE)).contents();
        var named = new RecipeDisplayEntry(display.id(), display.display(), display.group(), display.category(),
                Optional.of(List.of(Ingredient.of(BuiltInRegistries.ITEM.get(key).orElseThrow()))));
        var entries = new java.util.ArrayList<>(entries(false, false));
        entries.set(0, new ClientboundRecipeBookAddPacket.Entry(named, false, false));
        byte[] book = bytes(entries);
        BuiltInRegistries.ITEM.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM, java.util.Map.of())).apply();
        boolean missingTagRejected = false;
        try { RecipeBookGroupingProbe.verifyPacket(registries, context, book, manifest()); }
        catch (java.util.NoSuchElementException expected) { missingTagRejected = true; }
        assertTrue("Native recipe decoder accepted an absent named tag", missingTagRejected);
        assertEquals(1, RecipeBookGroupingProbe.applyItemTags(registries, tags));
        assertEquals(3, RecipeBookGroupingProbe.verifyPacket(registries, RecipeBookGroupingProbe.context(registries), book, manifest()).displays());
    }

    @Test public void trailingNativeTagPacketBytesAreRejected() {
        byte[] tags = tagPacket();
        try {
            RecipeBookGroupingProbe.applyItemTags(registries, Arrays.copyOf(tags, tags.length + 1));
            fail("Native tag packet decoder accepted trailing bytes");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Trailing native tag packet bytes")); }
    }

    @Test public void nativeTagPacketCannotSilentlyDropUnknownRegistryMembers() {
        try {
            RecipeBookGroupingProbe.applyItemTags(registries, tagPacket(BuiltInRegistries.ITEM.size() + 100));
            fail("Native item-tag registry mismatch was silently dropped");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("dropped/changed a member")); }
    }

    private static byte[] tagPacket() {
        return tagPacket(BuiltInRegistries.ITEM.getId(Items.STICK));
    }

    private static byte[] tagPacket(int itemId) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(1);
            buffer.writeResourceKey(Registries.ITEM);
            buffer.writeVarInt(1);
            buffer.writeIdentifier(Identifier.parse("advantageclientresources:native_fixture"));
            buffer.writeIntIdList(new it.unimi.dsi.fastutil.ints.IntArrayList(new int[]{itemId}));
            return io.netty.buffer.ByteBufUtil.getBytes(buffer);
        } finally { buffer.release(); }
    }

    private static List<ClientboundRecipeBookAddPacket.Entry> entries(boolean split, boolean mixed) {
        return List.of(entry(1, 3, new ItemStack(Items.APPLE)),
                entry(2, split ? 6 : 3, new ItemStack(Items.APPLE, 2)),
                entry(3, mixed ? 3 : 7, new ItemStack(Items.CARROT)));
    }

    private static ClientboundRecipeBookAddPacket.Entry entry(int id, int group, ItemStack output) {
        var display = new ShapedCraftingRecipeDisplay(1, 1, List.of(new SlotDisplay.ItemSlotDisplay(Items.STICK)),
                new SlotDisplay.ItemStackSlotDisplay(output), new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE));
        return new ClientboundRecipeBookAddPacket.Entry(new RecipeDisplayEntry(new RecipeDisplayId(id), display,
                OptionalInt.of(group), RecipeBookCategories.CRAFTING_MISC, Optional.empty()), false, false);
    }

    private static JsonObject manifest() {
        JsonObject result = new JsonObject();
        result.addProperty("status", "PASS");
        result.addProperty("bookPacketType", "ClientboundRecipeBookAddPacket");
        JsonArray rows = new JsonArray();
        result.add("bookRecipes", rows);
        for (int i = 1; i <= 3; i++) {
            JsonObject row = new JsonObject();
            row.addProperty("id", "basepower:fixture_" + i);
            row.addProperty("output", i == 3 ? "minecraft:carrot" : "minecraft:apple");
            row.addProperty("group", row.get("output").getAsString());
            row.addProperty("type", "minecraft:crafting");
            row.addProperty("count", i == 2 ? 2 : 1);
            JsonArray displays = new JsonArray(); displays.add(i); row.add("displayIds", displays);
            rows.add(row);
        }
        return result;
    }

    private static byte[] bytes(List<ClientboundRecipeBookAddPacket.Entry> entries) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            ClientboundRecipeBookAddPacket.STREAM_CODEC.encode(buffer, new ClientboundRecipeBookAddPacket(entries, true));
            return io.netty.buffer.ByteBufUtil.getBytes(buffer);
        } finally { buffer.release(); }
    }

    private static void rejected(byte[] bytes, JsonObject manifest, String reason) {
        try {
            RecipeBookGroupingProbe.verifyPacket(registries, context, bytes, manifest);
            fail("Native client oracle accepted mutation: " + reason);
        } catch (IllegalStateException expected) {
            assertTrue(expected.toString(), expected.getMessage().contains(reason));
        }
    }
}
