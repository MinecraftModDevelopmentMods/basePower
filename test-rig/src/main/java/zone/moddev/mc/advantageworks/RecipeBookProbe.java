package zone.moddev.mc.advantageworks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeType;
import static zone.moddev.mc.advantageworks.ProgressionProbe.*;

/** Native crafting and server-to-client recipe payload, not a source-JSON simulation. */
final class RecipeBookProbe {
    static void verify(MinecraftServer server, ServerLevel world, String label, JsonObject report) throws Exception {
        JsonArray entries = new JsonArray();
        List<RecipeHolder<?>> recipes = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            String namespace = holder.id().getNamespace();
            if (!(namespace.equals("basepower") || namespace.equals("basesteam") || namespace.equals("baseelectric"))) continue;
            if (recipe.getType() != RecipeType.CRAFTING && recipe.getType() != RecipeType.SMELTING) continue;
            String output = id(recipe.getResultItem(world.registryAccess()));
            require(output.equals(recipe.getGroup()), "Mixed-output recipe group: " + holder.id() + " -> " + recipe.getGroup());
            JsonObject entry = new JsonObject();
            entry.addProperty("id", holder.id().toString());
            entry.addProperty("group", recipe.getGroup());
            entry.addProperty("type", net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString());
            entry.addProperty("output", output);
            entry.addProperty("count", recipe.getResultItem(world.registryAccess()).getCount());
            entries.add(entry);
            recipes.add(holder);
        }
        report.add("bookRecipes", entries);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), world.registryAccess());
        try {
            ClientboundUpdateRecipesPacket.STREAM_CODEC.encode(buffer, new ClientboundUpdateRecipesPacket(recipes));
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            Files.write(Paths.get("recipes-" + label + ".bin"), bytes);
        } finally { buffer.release(); }
        ResourceLocation starchId = new ResourceLocation("basepower:starch_from_potato");
        Recipe starch = server.getRecipeManager().byKey(starchId).map(RecipeHolder::value).orElse(null);
        require(starch != null && starch.getType() == RecipeType.CRAFTING, "Missing native potato crafting recipe");
        int cases = 0;
        for (int width : new int[] {2, 3}) for (String potato : new String[] {"minecraft:potato", "minecraft:poisonous_potato"}) {
            for (int slot = 0; slot < width * width; slot++) for (int count : new int[] {1, 64}) {
                CraftingContainer grid = grid(width);
                ItemStack input = stack(potato);
                input.setCount(count);
                grid.setItem(slot, input);
                require(starch.matches(grid, world), "Potato recipe rejected slot/stack " + slot + "/" + count);
                RecipeHolder<?> selected = world.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, world).orElse(null);
                require(selected != null && selected.id().equals(starchId), "Conflicting native potato crafting recipe");
                ItemStack result = starch.assemble(grid, world.registryAccess());
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
            require(!starch.matches(grid, world), "Invalid starch input accepted: " + item);
            cases++;
        }
        for (String second : new String[] {"minecraft:potato", "minecraft:poisonous_potato", "minecraft:dirt"}) {
            CraftingContainer grid = grid(3);
            grid.setItem(0, stack("minecraft:potato"));
            grid.setItem(1, stack(second));
            require(!starch.matches(grid, world), "Extra occupied ingredient slot accepted");
            cases++;
        }
        report.addProperty("potatoCraftingCases", cases);
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
