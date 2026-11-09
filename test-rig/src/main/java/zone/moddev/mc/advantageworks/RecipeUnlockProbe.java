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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.tags.ItemTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Native criteria, reward and saved-player checks in marked disposable worlds only. */
final class RecipeUnlockProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File MARKER = new File("advantage-recipes.allow");
    private ServerPlayer player;
    private Connection transport;

    static void register() {
        if (MARKER.isFile()) {
            RecipeUnlockProbe probe = new RecipeUnlockProbe();
            MinecraftForge.EVENT_BUS.addListener(probe::starting);
            MinecraftForge.EVENT_BUS.addListener(probe::stopped);
        }
    }

    static Connection nativeTransport() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        io.netty.channel.embedded.EmbeddedChannel channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
        require(connection.channel() == channel && channel.isActive(), "Native test channel did not activate");
        return connection;
    }

    private void stopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        if (transport != null) ((io.netty.channel.embedded.EmbeddedChannel) transport.channel()).finishAndReleaseAll();
    }

    private void starting(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("advrecipes")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("operation", StringArgumentType.word())
                        .then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                            String operation = StringArgumentType.getString(context, "operation");
                            String label = StringArgumentType.getString(context, "label");
                            try {
                                run(context.getSource().getServer(), context.getSource().getLevel(), operation, label);
                            } catch (Throwable error) {
                                System.err.println("ADVRECIPES FAIL " + error);
                                error.printStackTrace();
                                throw new IllegalStateException("Recipe probe failed", error);
                            }
                            System.out.println("ADVRECIPES COMPLETE " + operation + " " + label);
                            return 1;
                        }))));
    }

    private void run(MinecraftServer server, ServerLevel world, String operation, String label) throws Exception {
        if (!MARKER.isFile() || !world.getServer().getWorldData().getLevelName().equals("AdvantageWorksMigration")
                || !label.matches("[a-zA-Z0-9_-]+")) throw new IllegalStateException("Unmarked world or unsafe label");
        JsonObject fixture = JSON.fromJson(new String(Files.readAllBytes(new File("recipe-fixture.json").toPath()),
                StandardCharsets.UTF_8), JsonObject.class);
        if (player == null) {
            player = new ServerPlayer(server, world, new GameProfile(
                    UUID.fromString("b9bcb1ba-e8e3-44ec-a729-ef70587dc453"), "RecipeProbe"));
            // Real server player and recipe book, with a sink instead of a remote client.
            transport = nativeTransport();
            player.connection = new ServerGamePacketListenerImpl(server, transport, player) {
                @Override public void send(Packet<?> packet) {}
            };
        }
        JsonArray cases = fixture.getAsJsonArray("recipes");
        JsonObject result = new JsonObject();
        JsonArray rows = new JsonArray();
        result.add("cases", rows);
        RecipeBookProbe.verify(server, world, label, result);
        for (JsonElement element : cases) {
            String id = element.getAsJsonObject().get("id").getAsString();
            require(id.matches("base(power|steam|electric):[a-z0-9_]+"), "Unsafe recipe ID: " + id);
            require(advancement(server, id) != null, "Missing loaded advancement: " + id);
        }
        if (operation.equals("sweep")) {
            for (JsonElement element : cases) {
                JsonObject definition = element.getAsJsonObject();
                String id = definition.get("id").getAsString();
                Advancement advancement = advancement(server, id);
                Recipe<?> recipe = server.getRecipeManager().byKey(new ResourceLocation(id)).orElse(null);
                JsonObject row = new JsonObject();
                row.addProperty("id", id);
                row.addProperty("recipeActive", recipe != null);
                int positives = 0, members = 0, emptyTags = 0;
                reset(advancement, recipe);
                player.getInventory().setItem(0, new ItemStack(item("minecraft:barrier")));
                CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), player.getInventory().getItem(0));
                require(!player.getAdvancements().getOrStartProgress(advancement).isDone(), "Unrelated item discovered " + id);
                for (Map.Entry<String, JsonElement> criterion : definition.getAsJsonObject("ingredients").entrySet()) {
                    InventoryChangeTrigger.TriggerInstance condition = (InventoryChangeTrigger.TriggerInstance)
                            advancement.getCriteria().get(criterion.getKey()).getTrigger();
                    player.getInventory().clearContent();
                    require(!matchesInventory(condition, player.getInventory()), "Empty inventory matches " + id);
                    player.getInventory().setItem(0, new ItemStack(item("minecraft:barrier")));
                    require(!matchesInventory(condition, player.getInventory()), "Unrelated item matches " + id);
                    List<Item> choices = choices(criterion.getValue().getAsJsonObject());
                    if (choices.isEmpty()) { emptyTags++; continue; }
                    for (Item candidate : choices) {
                        player.getInventory().setItem(0, new ItemStack(candidate));
                        require(matchesInventory(condition, player.getInventory()), "Tag member rejected: " + id + " / " + net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(candidate));
                        members++;
                    }
                    reset(advancement, recipe);
                    player.getInventory().setItem(0, new ItemStack(choices.get(0)));
                    CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), player.getInventory().getItem(0));
                    require(player.getAdvancements().getOrStartProgress(advancement).isDone(), "Inventory event did not discover " + id);
                    require(recipe == null || player.getRecipeBook().contains(recipe), "Reward failed for " + id);
                    positives++;
                }
                // Also exercise /recipe-style discovery and its has_recipe criterion.
                if (recipe != null) {
                    reset(advancement, recipe);
                    player.awardRecipes(Collections.singletonList(recipe));
                    require(player.getAdvancements().getOrStartProgress(advancement).isDone(), "Recipe-command criterion failed: " + id);
                }
                row.addProperty("positiveCriteria", positives);
                row.addProperty("tagMembers", members);
                row.addProperty("emptyTagCriteria", emptyTags);
                row.addProperty("status", positives == 0 ? "SKIP_NO_INGREDIENT_PROVIDER" : "PASS");
                rows.add(row);
            }
        } else if (operation.equals("persist")) {
            for (JsonElement element : cases) {
                String id = element.getAsJsonObject().get("id").getAsString();
                reset(advancement(server, id), server.getRecipeManager().byKey(new ResourceLocation(id)).orElse(null));
            }
            JsonArray kit = new JsonArray();
            for (JsonElement id : fixture.getAsJsonArray("materials")) {
                ResourceLocation name = new ResourceLocation(id.getAsString());
                if (!ForgeRegistries.ITEMS.containsKey(name)) continue;
                discover(item(id.getAsString()));
                kit.add(id.getAsString());
            }
            JsonArray additions = new JsonArray();
            for (JsonElement element : cases) {
                JsonObject definition = element.getAsJsonObject();
                Advancement advancement = advancement(server, definition.get("id").getAsString());
                if (!player.getAdvancements().getOrStartProgress(advancement).isDone()) {
                    for (Map.Entry<String, JsonElement> criterion : definition.getAsJsonObject("ingredients").entrySet()) {
                        List<Item> choices = choices(criterion.getValue().getAsJsonObject());
                        if (!choices.isEmpty()) {
                            Item candidate = choices.get(0);
                            discover(candidate);
                            additions.add(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(candidate).toString());
                            break;
                        }
                    }
                }
            }
            player.getInventory().clearContent();
            player.getAdvancements().save();
            Native117PlayerIo.save(server.getPlayerList(), player);
            result.add("materials", kit);
            result.add("additionalMaterials", additions);
            verifyDiscovered(server, cases, rows);
        } else if (operation.equals("restore")) {
            require(server.getPlayerList().load(player) != null, "No saved test player");
            require(transport.channel().isActive(), "Native recipe-login channel is not active");
            require(net.minecraftforge.network.NetworkHooks.getConnectionData(transport) == null,
                    "Server-only probe must not impersonate a remote modded-client handshake");
            // Dispatch the real Forge event to exercise all three namespaces' restoration.
            MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
            result.addProperty("nativeForgeLoginEvent", true);
            require(player.getInventory().isEmpty(), "Persistence test must not reacquire ingredients");
            verifyDiscovered(server, cases, rows);
        } else throw new IllegalArgumentException("Unknown operation " + operation);
        result.addProperty("status", "PASS");
        Files.write(new File("recipes-" + label + ".json").toPath(), JSON.toJson(result).getBytes(StandardCharsets.UTF_8));
    }

    private void verifyDiscovered(MinecraftServer server, JsonArray definitions, JsonArray rows) {
        for (JsonElement element : definitions) {
            JsonObject definition = element.getAsJsonObject();
            String id = definition.get("id").getAsString();
            boolean available = false;
            for (Map.Entry<String, JsonElement> criterion : definition.getAsJsonObject("ingredients").entrySet())
                available |= !choices(criterion.getValue().getAsJsonObject()).isEmpty();
            boolean done = player.getAdvancements().getOrStartProgress(advancement(server, id)).isDone();
            require(!available || done, "Lost discovery: " + id);
            Recipe<?> recipe = server.getRecipeManager().byKey(new ResourceLocation(id)).orElse(null);
            require(recipe == null || !available || player.getRecipeBook().contains(recipe), "Lost recipe reward: " + id);
            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("recipeActive", recipe != null);
            row.addProperty("discovered", done);
            row.addProperty("status", available ? "PASS" : "SKIP_NO_INGREDIENT_PROVIDER");
            rows.add(row);
        }
    }

    private void discover(Item item) {
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(item));
        CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), player.getInventory().getItem(0));
    }

    private void reset(Advancement advancement, Recipe<?> recipe) {
        player.getInventory().clearContent();
        if (recipe != null) player.resetRecipes(Collections.singletonList(recipe));
        for (String criterion : advancement.getCriteria().keySet())
            player.getAdvancements().revoke(advancement, criterion);
    }

    private static Advancement advancement(MinecraftServer server, String id) {
        ResourceLocation name = new ResourceLocation(id);
        return server.getAdvancements().getAdvancement(new ResourceLocation(name.getNamespace(), "recipes/" + name.getPath()));
    }

    private static boolean matchesInventory(InventoryChangeTrigger.TriggerInstance condition,
                                            net.minecraft.world.entity.player.Inventory inventory) {
        int full = 0, empty = 0, occupied = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) empty++;
            else {
                occupied++;
                if (stack.getCount() >= stack.getMaxStackSize()) full++;
            }
        }
        return condition.matches(inventory, inventory.getItem(0), full, empty, occupied);
    }

    private static List<Item> choices(JsonObject predicate) {
        if (predicate.has("item")) return Collections.singletonList(item(predicate.get("item").getAsString()));
        List<Item> items = new ArrayList<>();
        for (var holder : net.minecraft.core.registries.BuiltInRegistries.ITEM.getTagOrEmpty(
                ItemTags.create(new ResourceLocation(predicate.get("tag").getAsString())))) items.add(holder.value());
        items.sort(Comparator.comparing(candidate -> net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(candidate).toString()));
        return items;
    }

    private static Item item(String id) {
        ResourceLocation name = new ResourceLocation(id);
        Item item = ForgeRegistries.ITEMS.getValue(name);
        require(item != null, "Missing test ingredient: " + id);
        return item;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
