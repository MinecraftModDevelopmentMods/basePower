package zone.moddev.mc.advantageworks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.util.context.ContextMap;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeType;
import static zone.moddev.mc.advantageworks.ProgressionProbe.*;

/** Native crafting and server-to-client recipe payload, not a source-JSON simulation. */
final class RecipeBookProbe {
    static void verify(MinecraftServer server, ServerLevel world, String label, JsonObject report) throws Exception {
        JsonArray entries = new JsonArray();
        List<ClientboundRecipeBookAddPacket.Entry> displays = new ArrayList<>();
        Map<Integer, String> groupOutputs = new HashMap<>();
        ContextMap displayContext = SlotDisplayContext.fromLevel(world);
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            Identifier recipeId = holder.id().identifier();
            String namespace = recipeId.getNamespace();
            if (!(namespace.equals("basepower") || namespace.equals("basesteam") || namespace.equals("baseelectric"))) continue;
            if (recipe.getType() != RecipeType.CRAFTING && recipe.getType() != RecipeType.SMELTING) continue;
            List<RecipeDisplayEntry> recipeDisplays = new ArrayList<>();
            server.getRecipeManager().listDisplaysForRecipe(holder.id(), recipeDisplays::add);
            require(!recipeDisplays.isEmpty(), "Recipe has no native book display: " + recipeId);
            ItemStack result = displayResult(recipeDisplays.getFirst(), displayContext);
            String output = id(result);
            require(output.equals(recipe.group()), "Mixed-output recipe group: " + recipeId + " -> " + recipe.group());
            JsonObject entry = new JsonObject();
            entry.addProperty("id", recipeId.toString());
            entry.addProperty("group", recipe.group());
            entry.addProperty("type", net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString());
            entry.addProperty("output", output);
            entry.addProperty("count", result.getCount());
            JsonArray displayIds = new JsonArray();
            for (RecipeDisplayEntry display : recipeDisplays) {
                require(ItemStack.matches(result, displayResult(display, displayContext)),
                        "Recipe displays disagree on output: " + recipeId);
                require(display.group().isPresent(), "Recipe display lost its book group: " + recipeId);
                String previous = groupOutputs.putIfAbsent(display.group().getAsInt(), output);
                require(previous == null || previous.equals(output), "Native book group mixes outputs: " + recipeId);
                displayIds.add(display.id().index());
                displays.add(new ClientboundRecipeBookAddPacket.Entry(display, false, false));
            }
            entry.add("displayIds", displayIds);
            entries.add(entry);
        }
        report.add("bookRecipes", entries);
        report.addProperty("bookPacketType", "ClientboundRecipeBookAddPacket");
        var tags = TagNetworkSerialization.serializeTagsToNetwork(server.registries());
        byte[] tagBytes = itemTagsPacket(tags);
        String tagFile = "tags-" + label + ".bin";
        Files.write(Paths.get(tagFile), tagBytes);
        report.addProperty("tagsPacketType", "ClientboundUpdateTagsPacket");
        report.addProperty("tagsPacketFile", tagFile);
        report.addProperty("tagsPacketSha256", java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(tagBytes)));
        report.addProperty("itemTagCount", tags.get(Registries.ITEM).size());
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), world.registryAccess());
        try {
            ClientboundRecipeBookAddPacket.STREAM_CODEC.encode(buffer, new ClientboundRecipeBookAddPacket(displays, true));
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            ClientboundRecipeBookAddPacket decoded = ClientboundRecipeBookAddPacket.STREAM_CODEC.decode(buffer);
            require(!buffer.isReadable() && decoded.replace() && decoded.entries().equals(displays),
                    "Native recipe-book packet did not round-trip");
            Files.write(Paths.get("recipes-" + label + ".bin"), bytes);
        } finally { buffer.release(); }
        var starchId = RecipeUnlockProbe.recipeKey("basepower:starch_from_potato");
        Recipe<?> starchRecipe = server.getRecipeManager().byKey(starchId).map(RecipeHolder::value).orElse(null);
        require(starchRecipe instanceof CraftingRecipe, "Missing native potato crafting recipe");
        CraftingRecipe starch = (CraftingRecipe) starchRecipe;
        int cases = 0;
        for (int width : new int[] {2, 3}) for (String potato : new String[] {"minecraft:potato", "minecraft:poisonous_potato"}) {
            for (int slot = 0; slot < width * width; slot++) for (int count : new int[] {1, 64}) {
                CraftingContainer grid = grid(width);
                ItemStack input = stack(potato);
                input.setCount(count);
                grid.setItem(slot, input);
                require(starch.matches(grid.asCraftInput(), world), "Potato recipe rejected slot/stack " + slot + "/" + count);
                RecipeHolder<?> selected = world.recipeAccess().getRecipeFor(RecipeType.CRAFTING, grid.asCraftInput(), world).orElse(null);
                require(selected != null && selected.id().equals(starchId), "Conflicting native potato crafting recipe");
                ItemStack result = starch.assemble(grid.asCraftInput());
                require(id(result).equals("basepower:starch") && result.getCount() == 1, "Starch output changed");
                require(grid.getItem(slot).getCount() == count, "Result preview mutated input");
                ItemStack consumed = grid.removeItem(slot, 1);
                require(consumed.getCount() == 1 && grid.getItem(slot).getCount() == count - 1,
                        "Crafting must consume exactly one potato");
                cases++;
            }
        }
        for (String item : new String[] {null, "minecraft:baked_potato", "minecraft:carrot", "minecraft:dirt"}) {
            CraftingContainer grid = grid(3);
            if (item != null) grid.setItem(0, stack(item));
            require(!starch.matches(grid.asCraftInput(), world), "Invalid starch input accepted: " + item);
            cases++;
        }
        for (String second : new String[] {"minecraft:potato", "minecraft:poisonous_potato", "minecraft:dirt"}) {
            CraftingContainer grid = grid(3);
            grid.setItem(0, stack("minecraft:potato"));
            grid.setItem(1, stack(second));
            require(!starch.matches(grid.asCraftInput(), world), "Extra occupied ingredient slot accepted");
            cases++;
        }
        report.addProperty("potatoCraftingCases", cases);
    }

    static ItemStack displayResult(RecipeDisplayEntry display, ContextMap context) {
        List<ItemStack> results = display.resultItems(context);
        require(results.size() == 1 && !results.getFirst().isEmpty(),
                "Recipe display must have exactly one nonempty output: " + display.id());
        return results.getFirst();
    }

    static byte[] itemTagsPacket(Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags) {
        var items = tags.get(Registries.ITEM);
        require(items != null && !items.isEmpty(), "Native recipe export requires the real server item tags");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundUpdateTagsPacket.STREAM_CODEC.encode(buffer, new ClientboundUpdateTagsPacket(tags));
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            var decoded = ClientboundUpdateTagsPacket.STREAM_CODEC.decode(buffer);
            require(!buffer.isReadable() && decoded.tags().keySet().equals(tags.keySet()),
                    "Native tag packet did not round-trip its registry set");
            return bytes;
        } finally { buffer.release(); }
    }

    private static CraftingContainer grid(int width) {
        return new net.minecraft.world.inventory.TransientCraftingContainer(new AbstractContainerMenu(null, 0) {
            @Override public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
            @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int slot) {
                return ItemStack.EMPTY;
            }
        }, width, width);
    }
}
