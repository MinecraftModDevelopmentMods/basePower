package zone.moddev.mc.advantageworks;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native12111RecipeBookProbeTest {
    private static final ContextMap CONTEXT = new ContextMap.Builder().create(SlotDisplayContext.CONTEXT);

    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void nativeDisplayRetainsOutputCountAndIdentity() {
        ItemStack expected = new ItemStack(Items.CARROT, 3);
        assertTrue(ItemStack.matches(expected,
                RecipeBookProbe.displayResult(display(new SlotDisplay.ItemStackSlotDisplay(net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(expected))), CONTEXT)));
    }

    @Test public void emptyDisplayFailsClosed() {
        assertThrows(IllegalStateException.class, () -> RecipeBookProbe.displayResult(display(SlotDisplay.Empty.INSTANCE), CONTEXT));
    }

    @Test public void ambiguousDisplayCannotSelectOnlyItsFirstOutput() {
        SlotDisplay mixed = new SlotDisplay.Composite(List.of(
                new SlotDisplay.ItemSlotDisplay(Items.CARROT), new SlotDisplay.ItemSlotDisplay(Items.POTATO)));
        assertThrows(IllegalStateException.class, () -> RecipeBookProbe.displayResult(display(mixed), CONTEXT));
    }

    @Test public void nativeBookPacketRoundTripsDisplayIdsGroupsAndIngredients() {
        RecipeDisplayEntry expected = display(new SlotDisplay.ItemStackSlotDisplay(new net.minecraft.world.item.ItemStackTemplate(Items.CARROT, 3)));
        var entries = List.of(new ClientboundRecipeBookAddPacket.Entry(expected, false, false));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            ClientboundRecipeBookAddPacket.STREAM_CODEC.encode(buffer, new ClientboundRecipeBookAddPacket(entries, true));
            ClientboundRecipeBookAddPacket decoded = ClientboundRecipeBookAddPacket.STREAM_CODEC.decode(buffer);
            assertFalse(buffer.isReadable());
            assertTrue(decoded.replace());
            assertEquals(entries, decoded.entries());
            RecipeDisplayEntry actual = decoded.entries().getFirst().contents();
            assertEquals(7, actual.id().index());
            assertEquals(3, actual.group().orElseThrow());
            assertTrue(actual.craftingRequirements().orElseThrow().getFirst().test(new ItemStack(Items.POTATO)));
            assertFalse(actual.craftingRequirements().orElseThrow().getFirst().test(new ItemStack(Items.CARROT)));
            assertTrue(ItemStack.matches(new ItemStack(Items.CARROT, 3), RecipeBookProbe.displayResult(actual, CONTEXT)));
        } finally { buffer.release(); }
    }

    @Test public void nativeTagPacketRetainsEveryTagAndEveryRealItemMember() {
        FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
        byte[] encoded;
        var tag = Identifier.parse("advantageworks:recipe_probe");
        try {
            payload.writeMap(java.util.Map.of(tag, new it.unimi.dsi.fastutil.ints.IntArrayList(new int[] {
                    BuiltInRegistries.ITEM.getId(Items.POTATO), BuiltInRegistries.ITEM.getId(Items.CARROT)})),
                    FriendlyByteBuf::writeIdentifier, FriendlyByteBuf::writeIntIdList);
            var nativeTags = TagNetworkSerialization.NetworkPayload.read(payload);
            assertFalse(payload.isReadable());
            encoded = RecipeBookProbe.itemTagsPacket(java.util.Map.of(Registries.ITEM, nativeTags));
        } finally { payload.release(); }
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded));
        try {
            var decoded = ClientboundUpdateTagsPacket.STREAM_CODEC.decode(packet);
            assertFalse(packet.isReadable());
            var tags = decoded.getTags().get(Registries.ITEM);
            assertEquals(1, tags.size());
            assertEquals(List.of(Items.POTATO, Items.CARROT), tags.resolve(BuiltInRegistries.ITEM).tags()
                    .get(net.minecraft.tags.TagKey.create(Registries.ITEM, tag)).stream()
                    .map(net.minecraft.core.Holder::value).toList());
        } finally { packet.release(); }
    }

    @Test public void recipeExportCannotPretendAnAbsentItemTagPacketIsComplete() {
        assertThrows(IllegalStateException.class, () -> RecipeBookProbe.itemTagsPacket(java.util.Map.of()));
    }

    private static RecipeDisplayEntry display(SlotDisplay result) {
        return new RecipeDisplayEntry(new RecipeDisplayId(7), new ShapelessCraftingRecipeDisplay(
                List.of(new SlotDisplay.ItemSlotDisplay(Items.POTATO)), result,
                new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)), OptionalInt.of(3),
                RecipeBookCategories.CRAFTING_MISC, Optional.of(List.of(Ingredient.of(Items.POTATO))));
    }
}
