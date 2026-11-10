package zone.moddev.mc.advantageworks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.tags.ItemTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.RecipeMode;

/** Manufacture from declared natural resources; no pre-supplied Advantage parts. */
final class ProgressionProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File MARKER = new File("advantage-progression.allow");

    static void register() {
        if (MARKER.isFile()) MinecraftForge.EVENT_BUS.addListener(new ProgressionProbe()::starting);
    }

    private void starting(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("advprogression").requires(source -> source.hasPermission(4))
                .then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                    String label = StringArgumentType.getString(context, "label");
                    try { audit(context.getSource().getServer(), context.getSource().getLevel(), label); }
                    catch (Throwable error) {
                        System.err.println("ADVPROGRESSION FAIL " + error);
                        error.printStackTrace();
                        throw new IllegalStateException(error);
                    }
                    System.out.println("ADVPROGRESSION COMPLETE " + label);
                    return 1;
                })));
    }

    private void audit(MinecraftServer server, ServerLevel world, String label) throws Exception {
        require(MARKER.isFile() && world.getServer().getWorldData().getLevelName().equals("AdvantageWorksMigration")
                && label.matches("[A-Za-z0-9_-]+"), "Unmarked world or unsafe label");
        JsonObject fixture = JSON.fromJson(new String(Files.readAllBytes(new File("progression-fixture.json").toPath()),
                StandardCharsets.UTF_8), JsonObject.class);
        ServerPlayer player = new ServerPlayer(server, world, new GameProfile(
                UUID.fromString("3ea512a5-4c41-42ac-901f-7fabd2b1cbde"), "ProgressionProbe"));
        player.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), player) {
            @Override public void send(Packet<?> packet) {}
        };
        for (Advancement advancement : server.getAdvancements().getAllAdvancements()) {
            if (owned(advancement.getId())) for (String criterion : advancement.getCriteria().keySet())
                player.getAdvancements().revoke(advancement, criterion);
        }
        List<Recipe<?>> oldBook = new ArrayList<>();
        for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) if (owned(recipe.getId())) oldBook.add(recipe);
        player.resetRecipes(oldBook);
        ProcessProgression.reset();
        Map<String, JsonObject> known = new LinkedHashMap<>();
        Set<String> executed = new LinkedHashSet<>();
        for (JsonElement seed : fixture.getAsJsonArray("naturalResources")) {
            String id = seed.getAsString();
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
            if (item != null) acquire(known, player, new ItemStack(item), "natural_resource", Collections.emptyList());
        }
        JsonArray loot = new JsonArray();
        var overriddenTable = server.getLootData().getLootTable(new ResourceLocation("minecraft:chests/simple_dungeon"));
        LootParams chest = chestParams(world);
        for (int seed = 0; seed < 8; seed++) {
            List<ItemStack> raw = new ArrayList<>();
            overriddenTable.getRandomItemsRaw(new LootContext.Builder(chest).withOptionalRandomSeed(seed + 1L).create(null), raw::add);
            require(raw.size() == 1 && id(raw.get(0)).equals("minecraft:dirt")
                    && raw.get(0).getCount() == 1, "Player loot data-pack override was changed");
            // Base Metals has its own Forge global modifier. It may add loot after the table runs.
            List<ItemStack> gameplay = overriddenTable.getRandomItems(chest, seed + 1L);
            require(gameplay.stream().filter(stack -> id(stack).equals("minecraft:dirt")).mapToInt(ItemStack::getCount).sum() == 1
                    && gameplay.stream().noneMatch(ProgressionProbe::advantage),
                    "The live loot pipeline changed the override's contents or added Advantage parts");
        }
        if (PowerAdvantage.recipeMode == RecipeMode.APOCALYPTIC) {
            for (int i = 0; i < 4096; i++) {
                for (ItemStack stack : server.getLootData().getLootTable(
                        new ResourceLocation("minecraft:chests/abandoned_mineshaft")).getRandomItems(chest, i + 1L)) {
                    if (advantage(stack)) {
                        if (acquire(known, player, stack, "vanilla_chest_loot", Collections.emptyList())) loot.add(id(stack));
                    }
                }
            }
        }
        List<Recipe<?>> recipes = new ArrayList<>(server.getRecipeManager().getRecipes());
        recipes.sort(Comparator.comparing(recipe -> recipe.getId().toString()));
        boolean changed = true;
        int rounds = 0;
        while (changed) {
            require(++rounds <= 100, "Progression closure did not converge");
            changed = false;
            for (Recipe<?> recipe : recipes) {
                if (executed.contains(recipe.getId().toString()) || recipe.getIngredients().isEmpty()) continue;
                String type = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
                boolean crafting = recipe.getType() == RecipeType.CRAFTING;
                boolean smelting = recipe.getType() == RecipeType.SMELTING;
                boolean crushing = type.equals("basemetals:crushing");
                if (!crafting && !smelting && !crushing) continue;
                if (smelting && (!known.containsKey("minecraft:furnace") || !known.containsKey("minecraft:coal"))) continue;
                if (crushing && known.keySet().stream().noneMatch(key -> key.startsWith("basemetals:") && key.endsWith("_crackhammer"))) continue;
                List<ItemStack> ingredients = new ArrayList<>();
                boolean possible = true;
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient == Ingredient.EMPTY) { ingredients.add(ItemStack.EMPTY); continue; }
                    ItemStack chosen = ItemStack.EMPTY;
                    for (ItemStack candidate : ingredient.getItems()) {
                        if (!candidate.isEmpty() && known.containsKey(id(candidate))) { chosen = candidate.copy(); chosen.setCount(1); break; }
                    }
                    if (chosen.isEmpty()) { possible = false; break; }
                    ingredients.add(chosen);
                }
                if (!possible) continue;
                if (crushing && !ProcessProgression.canCrush(known, ingredients.get(0))) continue;
                ItemStack result = ItemStack.EMPTY;
                if (crafting) {
                    AbstractContainerMenu container = new AbstractContainerMenu(null, 0) {
                        @Override public boolean stillValid(net.minecraft.world.entity.player.Player p) { return true; }
                        @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int slot) {
                            return ItemStack.EMPTY;
                        }
                    };
                    for (int width = 1; width <= 3 && result.isEmpty(); width++) {
                        if (ingredients.size() > width * 3) continue;
                        CraftingContainer grid = new TransientCraftingContainer(container, 3, 3);
                        for (int i = 0; i < ingredients.size(); i++) grid.setItem((i / width) * 3 + i % width, ingredients.get(i));
                        result = matchedResult(recipe, grid, world);
                    }
                } else {
                    SimpleContainer grid = new SimpleContainer(1);
                    grid.setItem(0, ingredients.get(0));
                    result = matchedResult(recipe, grid, world);
                }
                if (result.isEmpty()) continue;
                if (owned(recipe.getId()) && !crushing) {
                    Advancement unlock = server.getAdvancements().getAdvancement(new ResourceLocation(
                            recipe.getId().getNamespace(), "recipes/" + recipe.getId().getPath()));
                    require(unlock != null && player.getAdvancements().getOrStartProgress(unlock).isDone()
                            && player.getRecipeBook().contains(recipe), "Manufacture lacks ingredient discovery: " + recipe.getId());
                }
                if (owned(recipe.getId()) && smelting)
                    ProcessProgression.smelt(world, fixture, recipe.getId().toString(), ingredients.get(0), result);
                if (owned(recipe.getId()) && crushing && !(ingredients.get(0).getItem() instanceof net.minecraft.world.item.BlockItem))
                    ProcessProgression.hammer(world, player, known, fixture, recipe.getId().toString(), ingredients.get(0), result);
                executed.add(recipe.getId().toString());
                List<String> inputs = new ArrayList<>();
                for (ItemStack ingredient : ingredients) if (!ingredient.isEmpty()) inputs.add(id(ingredient));
                changed |= acquire(known, player, result, recipe.getId().toString(), inputs);
            }
            changed |= ProcessProgression.advance(world, player, known, fixture);
        }
        JsonArray targets = new JsonArray();
        JsonObject exclusions = fixture.getAsJsonObject("exclusions");
        int blocked = 0;
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            if (!owned(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item))) continue;
            String id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item).toString();
            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            if (exclusions.has(id)) {
                row.addProperty("status", "INTENTIONAL_EXCLUSION");
                row.addProperty("reason", exclusions.get(id).getAsString());
            } else if (PowerAdvantage.recipeMode == RecipeMode.APOCALYPTIC && id.equals("baseelectric:photovoltaic_generator")) {
                row.addProperty("status", "INTENTIONAL_MODE_RESTRICTION");
                row.addProperty("reason", "Legacy Apocalyptic mode forbids new photovoltaic generators; use hydro or steam generation");
            } else if (known.containsKey(id)) {
                row.addProperty("status", "PASS");
                row.add("acquisition", known.get(id));
            } else { row.addProperty("status", "BLOCKED"); blocked++; }
            targets.add(row);
        }
        JsonObject report = new JsonObject();
        JsonArray missingRecipes = new JsonArray();
        for (Recipe recipe : recipes) {
            if (!owned(recipe.getId()) || (recipe.getType() != RecipeType.CRAFTING
                    && recipe.getType() != RecipeType.SMELTING)) continue;
            if (!executed.contains(recipe.getId().toString())) missingRecipes.add(recipe.getId().toString());
        }
        if (missingRecipes.size() > 0) blocked += missingRecipes.size();
        report.addProperty("status", blocked == 0 ? "PASS" : "FAIL");
        report.addProperty("mode", PowerAdvantage.recipeMode.name());
        report.addProperty("plasticEqualsRubber", PowerAdvantage.plasticIsAlsoRubber);
        report.addProperty("rounds", rounds);
        report.addProperty("blocked", blocked);
        report.add("targets", targets);
        report.add("reachable", JSON.toJsonTree(known));
        report.add("executedRecipes", JSON.toJsonTree(executed));
        report.add("blockedRecipes", missingRecipes);
        report.add("lootSeeds", loot);
        report.addProperty("lootDataPackOverrideVerified", true);
        report.addProperty("lootOverrideWithModifiersVerified", true);
        report.add("processWitnesses", ProcessProgression.witnesses());
        JsonObject tags = new JsonObject();
        for (String name : new String[] {"wires", "dusts/carbon", "rubber", "plastic", "circuit_boards", "solder"}) {
            List<String> members = new ArrayList<>();
            for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.create(new ResourceLocation("forge", name))))
                members.add(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item.value()).toString());
            Collections.sort(members);
            tags.add(name, JSON.toJsonTree(members));
        }
        report.add("tags", tags);
        Files.write(new File("progression-" + label + ".json").toPath(), JSON.toJson(report).getBytes(StandardCharsets.UTF_8));
    }

    static boolean acquire(Map<String, JsonObject> known, ServerPlayer player, ItemStack output, String route, List<String> inputs) {
        if (output == null || output.isEmpty() || known.containsKey(id(output))) return false;
        JsonObject node = new JsonObject();
        node.addProperty("via", route);
        node.addProperty("outputCount", output.getCount());
        node.add("inputs", inputJson(inputs));
        known.put(id(output), node);
        player.getInventory().clearContent();
        player.getInventory().setItem(0, output.copy());
        CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), player.getInventory().getItem(0));
        return true;
    }

    static JsonArray inputJson(List<String> inputs) {
        JsonArray result = new JsonArray();
        for (String input : inputs) result.add(input);
        return result;
    }

    static ItemStack stack(String id) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static LootParams chestParams(ServerLevel world) {
        return new LootParams.Builder(world)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, new net.minecraft.world.phys.Vec3(0, 80, 0))
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CHEST);
    }

    @SuppressWarnings("unchecked")
    private static ItemStack matchedResult(Recipe<?> recipe, net.minecraft.world.Container inventory, ServerLevel world) {
        Recipe<net.minecraft.world.Container> typed = (Recipe<net.minecraft.world.Container>) recipe;
        return typed.matches(inventory, world) ? typed.assemble(inventory, world.registryAccess()) : ItemStack.EMPTY;
    }

    private static boolean advantage(ItemStack stack) { return !stack.isEmpty() && owned(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem())); }
    private static boolean owned(ResourceLocation id) {
        return id != null && (id.getNamespace().equals("basepower") || id.getNamespace().equals("basesteam")
                || id.getNamespace().equals("baseelectric"));
    }
    static String id(ItemStack stack) { return net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString(); }
    static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
}
