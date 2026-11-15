package zone.moddev.mc.advantageworks;

import net.minecraft.core.registries.BuiltInRegistries;

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
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileAlreadyExistsException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;

/** Native criteria, reward and saved-player checks in marked disposable worlds only. */
final class RecipeUnlockProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File MARKER = new File("advantage-recipes.allow");
    private ServerPlayer player;
    private Connection transport;

    static void register() {
        if (MARKER.isFile()) {
            RecipeUnlockProbe probe = new RecipeUnlockProbe();
            NeoForge.EVENT_BUS.addListener(probe::starting);
            NeoForge.EVENT_BUS.addListener(probe::stopped);
        }
    }

    static Connection nativeTransport() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        io.netty.channel.embedded.EmbeddedChannel channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
        require(connection.channel() == channel && channel.isActive(), "Native test channel did not activate");
        net.neoforged.neoforge.network.registration.ChannelAttributes.setConnectionType(connection,
                net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        return connection;
    }

    private void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
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
        Path packetPath = Path.of("recipes-" + label + ".bin");
        Path resultPath = Path.of("recipes-" + label + ".json");
        requireNewRecipeOutputs(packetPath, resultPath);
        NativeRegistryExport registries = captureNativeRegistries(world);
        Path snapshotPath = registrySnapshotPath(label);
        writeMatchingSnapshot(snapshotPath, registries.bytes());
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
        require(Arrays.equals(registries.bytes(), captureNativeRegistries(world).bytes()),
                "Native registry IDs changed while encoding the recipe packet");
        byte[] packetBytes = Files.readAllBytes(packetPath);
        requirePacketBytes(packetBytes, encodeExpectedRecipePacket(server, world, result));
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
                RecipeHolder<?> recipe = server.getRecipeManager().byKey(ResourceLocation.parse(id)).orElse(null);
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
                        require(matchesInventory(condition, player.getInventory()), "Tag member rejected: " + id + " / " + BuiltInRegistries.ITEM.getKey(candidate));
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
                reset(advancement(server, id), server.getRecipeManager().byKey(ResourceLocation.parse(id)).orElse(null));
            }
            JsonArray kit = new JsonArray();
            for (JsonElement id : fixture.getAsJsonArray("materials")) {
                ResourceLocation name = ResourceLocation.parse(id.getAsString());
                if (!BuiltInRegistries.ITEM.containsKey(name)) continue;
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
                            additions.add(BuiltInRegistries.ITEM.getKey(candidate).toString());
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
            require(server.getPlayerList().load(player).isPresent(), "No saved test player");
            require(transport.channel().isActive(), "Native recipe-login channel is not active");
            require(net.neoforged.neoforge.network.registration.ChannelAttributes.getConnectionType(transport)
                            == net.neoforged.neoforge.network.connection.ConnectionType.OTHER,
                    "Server-only probe must not impersonate a remote modded-client handshake");
            // Dispatch the real Forge event to exercise all three namespaces' restoration.
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
            result.addProperty("nativeForgeLoginEvent", true);
            require(player.getInventory().isEmpty(), "Persistence test must not reacquire ingredients");
            verifyDiscovered(server, cases, rows);
        } else throw new IllegalArgumentException("Unknown operation " + operation);
        require(Arrays.equals(registries.bytes(), captureNativeRegistries(world).bytes()),
                "Native registry IDs changed during recipe discovery");
        requirePacketBytes(Files.readAllBytes(packetPath), packetBytes);
        require(Arrays.equals(Files.readAllBytes(snapshotPath), registries.bytes()),
                "Native registry snapshot changed during recipe discovery");
        JsonObject registryReceipt = new JsonObject();
        registryReceipt.addProperty("schema", 1);
        registryReceipt.addProperty("minecraftVersion", net.minecraft.SharedConstants.getCurrentVersion().getName());
        registryReceipt.addProperty("neoForgeVersion", net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion.getVersion());
        registryReceipt.addProperty("snapshotType", "SYNC_TO_CLIENT");
        registryReceipt.addProperty("file", snapshotPath.getFileName().toString());
        registryReceipt.addProperty("sha256", sha256(registries.bytes()));
        registryReceipt.addProperty("bytes", registries.bytes().length);
        registryReceipt.addProperty("registryCount", registries.registryCount());
        registryReceipt.addProperty("sameRegistries", true);
        registryReceipt.addProperty("packetFile", packetPath.getFileName().toString());
        registryReceipt.addProperty("packetSha256", sha256(packetBytes));
        registryReceipt.addProperty("packetBytes", packetBytes.length);
        registryReceipt.addProperty("recipeCount", result.getAsJsonArray("bookRecipes").size());
        result.add("registrySnapshot", registryReceipt);
        result.addProperty("status", "PASS");
        Files.write(resultPath, JSON.toJson(result).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }

    private record NativeRegistryExport(byte[] bytes, int registryCount) {}

    private static NativeRegistryExport captureNativeRegistries(ServerLevel world) {
        Map<ResourceLocation, RegistrySnapshot> snapshots = new TreeMap<>(
                RegistryManager.takeSnapshot(RegistryManager.SnapshotType.SYNC_TO_CLIENT));
        require(snapshots.containsKey(Registries.ITEM.location()) && snapshots.containsKey(Registries.BLOCK.location())
                        && !snapshots.get(Registries.ITEM.location()).getIds().isEmpty()
                        && !snapshots.get(Registries.BLOCK.location()).getIds().isEmpty(),
                "Native client-sync snapshot lacks item or block registry IDs");
        for (var entry : snapshots.entrySet()) {
            Registry<?> registry = world.registryAccess().registryOrThrow(ResourceKey.createRegistryKey(entry.getKey()));
            verifySnapshotIds(registry, entry.getValue());
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buffer.writeMap(snapshots, FriendlyByteBuf::writeResourceLocation, RegistrySnapshot.STREAM_CODEC::encode);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            require(!buffer.isReadable() && bytes.length > 0, "Native snapshot encoder left unread bytes");
            return new NativeRegistryExport(bytes, snapshots.size());
        } finally { buffer.release(); }
    }

    private static <T> void verifySnapshotIds(Registry<T> registry, RegistrySnapshot snapshot) {
        require(registry.size() == snapshot.getIds().size(), "Snapshot size differs from packet registry " + registry.key());
        for (var entry : snapshot.getIds().int2ObjectEntrySet()) {
            T value = registry.byId(entry.getIntKey());
            require(value != null && entry.getValue().equals(registry.getKey(value)),
                    "Snapshot ID differs from the packet registry: " + registry.key() + " / " + entry.getValue());
        }
    }

    private static byte[] encodeExpectedRecipePacket(MinecraftServer server, ServerLevel world, JsonObject result) {
        List<RecipeHolder<?>> recipes = new ArrayList<>();
        for (JsonElement row : result.getAsJsonArray("bookRecipes")) {
            ResourceLocation id = ResourceLocation.parse(row.getAsJsonObject().get("id").getAsString());
            recipes.add(server.getRecipeManager().byKey(id).orElseThrow(() ->
                    new IllegalStateException("Recipe disappeared during native packet export: " + id)));
        }
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), world.registryAccess());
        try {
            ClientboundUpdateRecipesPacket.STREAM_CODEC.encode(buffer, new ClientboundUpdateRecipesPacket(recipes));
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            require(!buffer.isReadable() && bytes.length > 0, "Recipe packet encoder left unread bytes");
            return bytes;
        } finally { buffer.release(); }
    }

    private static void requireNewRecipeOutputs(Path packet, Path report) {
        require(!Files.exists(packet, LinkOption.NOFOLLOW_LINKS) && !Files.exists(report, LinkOption.NOFOLLOW_LINKS),
                "Refusing to overwrite a prior recipe packet or report");
    }

    private static Path registrySnapshotPath(String label) {
        require(label.matches("[a-zA-Z0-9_-]+"), "Unsafe recipe snapshot label");
        return Path.of("recipes-" + label + ".registry.bin");
    }

    private static void requirePacketBytes(byte[] actual, byte[] expected) {
        require(Arrays.equals(actual, expected), "Recipe packet differs from its exact native registry-bound encoding");
    }

    private static void writeMatchingSnapshot(Path path, byte[] bytes) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
                return;
            } catch (FileAlreadyExistsException exists) {
                // A competing export must match exactly; never truncate its snapshot.
            }
        }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && Arrays.equals(Files.readAllBytes(path), bytes),
                "Refusing to overwrite a native registry snapshot from different inputs");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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
            RecipeHolder<?> recipe = server.getRecipeManager().byKey(ResourceLocation.parse(id)).orElse(null);
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

    private void reset(AdvancementHolder advancement, RecipeHolder<?> recipe) {
        player.getInventory().clearContent();
        if (recipe != null) player.resetRecipes(Collections.singletonList(recipe));
        for (String criterion : advancement.value().criteria().keySet())
            player.getAdvancements().revoke(advancement, criterion);
    }

    private static AdvancementHolder advancement(MinecraftServer server, String id) {
        ResourceLocation name = ResourceLocation.parse(id);
        return server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(name.getNamespace(), "recipes/" + name.getPath()));
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
                ItemTags.create(ResourceLocation.parse(predicate.get("tag").getAsString())))) items.add(holder.value());
        items.sort(Comparator.comparing(candidate -> BuiltInRegistries.ITEM.getKey(candidate).toString()));
        return items;
    }

    private static Item item(String id) {
        ResourceLocation name = ResourceLocation.parse(id);
        Item item = zone.moddev.mc.basepower.util.RegistryValues.find(BuiltInRegistries.ITEM, name);
        require(item != null, "Missing test ingredient: " + id);
        return item;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
