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
import net.minecraft.network.FriendlyByteBuf;
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
        List<Recipe<?>> recipes = new ArrayList<>();
        for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
            String namespace = recipe.getId().getNamespace();
            if (!(namespace.equals("basepower") || namespace.equals("basesteam") || namespace.equals("baseelectric"))) continue;
            if (recipe.getType() != RecipeType.CRAFTING && recipe.getType() != RecipeType.SMELTING) continue;
            String output = id(recipe.getResultItem());
            require(output.equals(recipe.getGroup()), "Mixed-output recipe group: " + recipe.getId() + " -> " + recipe.getGroup());
            JsonObject entry = new JsonObject();
            entry.addProperty("id", recipe.getId().toString());
            entry.addProperty("group", recipe.getGroup());
            entry.addProperty("type", net.minecraft.core.Registry.RECIPE_TYPE.getKey(recipe.getType()).toString());
            entry.addProperty("output", output);
            entry.addProperty("count", recipe.getResultItem().getCount());
            entries.add(entry);
            recipes.add(recipe);
        }
        report.add("bookRecipes", entries);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ClientboundUpdateRecipesPacket(recipes).write(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            Files.write(Paths.get("recipes-" + label + ".bin"), bytes);
        } finally { buffer.release(); }
        Recipe starch = server.getRecipeManager().byKey(new ResourceLocation("basepower:starch_from_potato")).orElse(null);
        require(starch != null && starch.getType() == RecipeType.CRAFTING, "Missing native potato crafting recipe");
        int cases = 0;
        for (int width : new int[] {2, 3}) for (String potato : new String[] {"minecraft:potato", "minecraft:poisonous_potato"}) {
            for (int slot = 0; slot < width * width; slot++) for (int count : new int[] {1, 64}) {
                CraftingContainer grid = grid(width);
                ItemStack input = stack(potato);
                input.setCount(count);
                grid.setItem(slot, input);
                require(starch.matches(grid, world), "Potato recipe rejected slot/stack " + slot + "/" + count);
                Recipe<?> selected = world.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, world).orElse(null);
                require(selected != null && selected.getId().equals(starch.getId()), "Conflicting native potato crafting recipe");
                ItemStack result = starch.assemble(grid);
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
        return new CraftingContainer(new AbstractContainerMenu(null, 0) {
            @Override public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
        }, width, width);
    }
}
