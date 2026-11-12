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
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.InventoryChangeTrigger;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.tags.ItemTags;
import net.minecraft.resources.Identifier;
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
            RegisterCommandsEvent.BUS.addListener(probe::starting);
            net.minecraftforge.event.server.ServerStoppedEvent.BUS.addListener(probe::stopped);
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
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
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
                    UUID.fromString("b9bcb1ba-e8e3-44ec-a729-ef70587dc453"), "RecipeProbe"), net.minecraft.server.level.ClientInformation.createDefault());
            // Real server player and recipe book, with a sink instead of a remote client.
            transport = nativeTransport();
            player.connection = new ServerGamePacketListenerImpl(server, transport, player, WorksOfflinePlayer.cookie(player)) {
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
                AdvancementHolder advancement = advancement(server, id);
                RecipeHolder<?> recipe = server.getRecipeManager().byKey(recipeKey(id)).orElse(null);
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
                            advancement.value().criteria().get(criterion.getKey()).triggerInstance();
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
                    require(recipe == null || player.getRecipeBook().contains(recipe.id()), "Reward failed for " + id);
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
                reset(advancement(server, id), server.getRecipeManager().byKey(recipeKey(id)).orElse(null));
            }
            JsonArray kit = new JsonArray();
            for (JsonElement id : fixture.getAsJsonArray("materials")) {
                Identifier name = Identifier.parse(id.getAsString());
                if (!ForgeRegistries.ITEMS.containsKey(name)) continue;
                discover(item(id.getAsString()));
                kit.add(id.getAsString());
            }
            JsonArray additions = new JsonArray();
            for (JsonElement element : cases) {
                JsonObject definition = element.getAsJsonObject();
                AdvancementHolder advancement = advancement(server, definition.get("id").getAsString());
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
            Native117PlayerIo.load(server.getPlayerList(), player);
            require(transport.channel().isActive(), "Native recipe-login channel is not active");
            require(net.minecraftforge.network.NetworkContext.get(transport).getType()
                            == net.minecraftforge.network.ConnectionType.VANILLA,
                    "Server-only probe must not impersonate a remote modded-client handshake");
            // Dispatch the real Forge event to exercise all three namespaces' restoration.
            PlayerEvent.PlayerLoggedInEvent.BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
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
            RecipeHolder<?> recipe = server.getRecipeManager().byKey(recipeKey(id)).orElse(null);
            require(recipe == null || !available || player.getRecipeBook().contains(recipe.id()), "Lost recipe reward: " + id);
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

    private void reset(AdvancementHolder advancement, RecipeHolder<?> recipe) {
        player.getInventory().clearContent();
        if (recipe != null) player.resetRecipes(Collections.singletonList(recipe));
        for (String criterion : advancement.value().criteria().keySet())
            player.getAdvancements().revoke(advancement, criterion);
    }

    private static AdvancementHolder advancement(MinecraftServer server, String id) {
        Identifier name = Identifier.parse(id);
        return server.getAdvancements().get(Identifier.fromNamespaceAndPath(name.getNamespace(), "recipes/" + name.getPath()));
    }

    static net.minecraft.resources.ResourceKey<net.minecraft.world.item.crafting.Recipe<?>> recipeKey(String id) {
        return net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,
                Identifier.parse(id));
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
                ItemTags.create(Identifier.parse(predicate.get("tag").getAsString())))) items.add(holder.value());
        items.sort(Comparator.comparing(candidate -> net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(candidate).toString()));
        return items;
    }

    private static Item item(String id) {
        Identifier name = Identifier.parse(id);
        Item item = ForgeRegistries.ITEMS.getValue(name);
        require(item != null, "Missing test ingredient: " + id);
        return item;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
