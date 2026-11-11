package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.RegistryManager;

/** Decodes server display packets and exercises the actual native client collection builder. */
final class RecipeBookGroupingProbe {
    static void verify(Minecraft minecraft, JsonObject report) {
        Path root = Path.of("recipe-book-fixtures");
        if (!Files.isDirectory(root)) {
            report.addProperty("recipe_book_grouping_status", "NOT_RUN_NO_NATIVE_FIXTURES");
            return;
        }
        JsonArray checks = new JsonArray();
        report.add("recipe_book_grouping", checks);
        try (Stream<Path> files = Files.list(root)) {
            Map<Identifier, ForgeRegistry.Snapshot> registry = RegistryManager.ACTIVE.takeSnapshot(false);
            CompoundTag level = NbtIo.readCompressed(root.resolve("server-registries.dat"), NbtAccounter.unlimitedHeap());
            require("1.21.11".equals(compound(compound(level, "Data"), "Version").getString("Name").orElseThrow()),
                    "Recipe fixture is not a native 1.21.11 server export");
            boolean nativeForge = false;
            for (var entry : compound(level, "fml").getList("LoadingModList").orElseThrow()) {
                require(entry instanceof CompoundTag, "Malformed native loading-mod entry");
                CompoundTag mod = (CompoundTag) entry;
                if ("forge".equals(mod.getString("ModId").orElseThrow()))
                    nativeForge = "61.1.0".equals(mod.getString("ModVersion").orElseThrow());
            }
            require(nativeForge, "Recipe fixture was not saved by Forge 61.1.0");
            CompoundTag saved = compound(compound(level, "fml"), "Registries");
            for (String key : List.of(ForgeRegistries.ITEMS.getRegistryName().toString(),
                    ForgeRegistries.BLOCKS.getRegistryName().toString())) {
                require(!compound(saved, key).getList("ids").orElseThrow().isEmpty(), "Missing native server registry snapshot: " + key);
            }
            for (String key : saved.keySet()) registry.put(Identifier.parse(key), ForgeRegistry.Snapshot.read(compound(saved, key)));
            require(GameData.injectSnapshot(registry, false, false).isEmpty(), "Client lacks a server registry entry");
            RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            List<Path> packets = files.filter(file -> file.getFileName().toString()
                    .matches("recipes-(normal|tech|apocalyptic)-sweep\\.bin")).sorted().toList();
            Set<String> packetNames = packets.stream().map(file -> file.getFileName().toString()).collect(Collectors.toSet());
            require(packetNames.equals(Set.of("recipes-normal-sweep.bin", "recipes-tech-sweep.bin", "recipes-apocalyptic-sweep.bin")),
                    "Normal, Tech and Apocalyptic recipe-book packets are required");
            for (Path file : packets) {
                Path manifestPath = file.resolveSibling(file.getFileName().toString().replace(".bin", ".json"));
                JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
                require(manifest.has("tagsPacketType") && "ClientboundUpdateTagsPacket".equals(manifest.get("tagsPacketType").getAsString()),
                        "Recipe fixture omitted its native item-tag packet");
                String tagsName = manifest.get("tagsPacketFile").getAsString();
                require(Path.of(tagsName).getFileName().toString().equals(tagsName) && tagsName.startsWith("tags-") && tagsName.endsWith(".bin"),
                        "Tag packet must be a sibling native export");
                byte[] tags = Files.readAllBytes(file.resolveSibling(tagsName));
                require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tags))
                        .equalsIgnoreCase(manifest.get("tagsPacketSha256").getAsString()), "Native tag packet SHA-256 mismatch");
                int tagCount = applyItemTags(registries, tags);
                require(tagCount == manifest.get("itemTagCount").getAsInt(), "Native item-tag count mismatch");
                ContextMap context = context(registries);
                VerifiedBook verified = verifyPacket(registries, context, Files.readAllBytes(file), manifest);
                JsonObject check = new JsonObject();
                check.addProperty("packet", file.getFileName().toString());
                check.addProperty("packet_type", "ClientboundRecipeBookAddPacket");
                check.addProperty("recipes", verified.recipes());
                check.addProperty("displays", verified.displays());
                check.addProperty("cells", verified.book().getCollections().size());
                check.addProperty("item_tags", tagCount);
                check.addProperty("status", "PASS");
                checks.add(check);
                if (file.getFileName().toString().equals("recipes-normal-sweep.bin")) render(minecraft, verified.book(), context);
            }
        } catch (Exception failure) { throw new IllegalStateException("Native recipe-book validation failed: " + failure, failure); }
        report.addProperty("recipe_book_grouping_status", "PASS");
    }

    record VerifiedBook(ClientRecipeBook book, int recipes, int displays) {}

    static int applyItemTags(RegistryAccess registries, byte[] bytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        ClientboundUpdateTagsPacket packet;
        try {
            packet = ClientboundUpdateTagsPacket.STREAM_CODEC.decode(buffer);
            require(!buffer.isReadable(), "Trailing native tag packet bytes");
        } finally { buffer.release(); }
        var payload = packet.getTags().get(net.minecraft.core.registries.Registries.ITEM);
        require(payload != null && !payload.isEmpty(), "Native packet has no item tags");
        var items = registries.lookupOrThrow(net.minecraft.core.registries.Registries.ITEM);
        var resolved = payload.resolve(items);
        require(resolved.tags().size() == payload.size(), "Native item-tag resolution lost tags");
        FriendlyByteBuf members = new FriendlyByteBuf(Unpooled.buffer());
        try {
            payload.write(members);
            Map<Identifier, it.unimi.dsi.fastutil.ints.IntList> expected = members.readMap(
                    FriendlyByteBuf::readIdentifier, FriendlyByteBuf::readIntIdList);
            require(!members.isReadable(), "Native item-tag member payload has trailing bytes");
            resolved.tags().forEach((key, holders) -> require(holders.stream().map(holder -> items.getId(holder.value())).toList()
                    .equals(expected.get(key.location())), "Native item-tag resolution dropped/changed a member: " + key.location()));
        } finally { members.release(); }
        // The same payload.resolve/prepareTagReload/apply path as ClientPacketListener.handleUpdateTags.
        items.prepareTagReload(resolved).apply();
        require(items.getTags().count() == payload.size(), "Native registry lost applied item tags");
        return payload.size();
    }

    static VerifiedBook verifyPacket(RegistryAccess registries, ContextMap context, byte[] bytes, JsonObject manifest) {
        require("PASS".equals(manifest.get("status").getAsString()), "Server recipe fixture did not pass");
        require(manifest.has("bookPacketType") && "ClientboundRecipeBookAddPacket".equals(manifest.get("bookPacketType").getAsString()),
                "Recipe fixture must identify the native display-packet protocol");
        Map<String, JsonObject> recipes = new HashMap<>();
        Map<Integer, JsonObject> displays = new HashMap<>();
        Map<String, Set<Integer>> expectedCells = new HashMap<>();
        for (var value : manifest.getAsJsonArray("bookRecipes")) {
            JsonObject row = value.getAsJsonObject();
            require(recipes.put(row.get("id").getAsString(), row) == null, "Duplicate recipe in packet manifest");
            require(row.get("group").getAsString().equals(row.get("output").getAsString()), "Manifest group is not output-specific");
            require(row.has("displayIds") && !row.getAsJsonArray("displayIds").isEmpty(), "Recipe manifest omitted native display IDs");
            for (var display : row.getAsJsonArray("displayIds")) {
                int id = display.getAsInt();
                require(id >= 0 && displays.put(id, row) == null, "Duplicate/invalid display ID in manifest: " + id);
                expectedCells.computeIfAbsent(cellKey(row), ignored -> new HashSet<>()).add(id);
            }
        }
        require(!recipes.isEmpty(), "Empty native recipe-book fixture");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registries);
        ClientboundRecipeBookAddPacket packet;
        try {
            packet = ClientboundRecipeBookAddPacket.STREAM_CODEC.decode(buffer);
            require(!buffer.isReadable(), "Trailing recipe packet bytes");
            require(packet.replace(), "Expected a complete native recipe-book snapshot");
        } finally { buffer.release(); }
        ClientRecipeBook book = new ClientRecipeBook();
        Set<Integer> seen = new HashSet<>();
        Map<Integer, String> groupOutputs = new HashMap<>();
        for (var entry : packet.entries()) {
            RecipeDisplayEntry display = entry.contents();
            int id = display.id().index();
            JsonObject row = displays.get(id);
            require(row != null && seen.add(id), "Unexpected/duplicate network display " + id);
            ItemStack stack = result(display, context);
            String output = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            require(output.equals(row.get("output").getAsString()) && stack.getCount() == row.get("count").getAsInt(),
                    "Client/server item registry or count mismatch for " + row.get("id"));
            require(display.group().isPresent(), "Native recipe display lost its group");
            String previous = groupOutputs.putIfAbsent(display.group().getAsInt(), output);
            require(previous == null || previous.equals(output), "Native numeric group mixes output items");
            book.add(display);
            if (entry.highlight()) book.addHighlight(display.id());
        }
        require(seen.equals(displays.keySet()), "Missing network displays");
        book.rebuildCollections();
        Set<Integer> collected = new HashSet<>();
        Set<String> actualCells = new HashSet<>();
        for (RecipeCollection cell : book.getCollections()) {
            Set<String> keys = new HashSet<>();
            Set<Integer> ids = new HashSet<>();
            for (RecipeDisplayEntry display : cell.getRecipes()) {
                int id = display.id().index();
                require(collected.add(id) && ids.add(id), "Display repeated across native cells");
                JsonObject row = displays.get(id);
                require(row != null, "Native cell contains an unmanifested display");
                keys.add(cellKey(row));
                require(ItemStack.isSameItemSameComponents(result(display, context), result(cell.getRecipes().getFirst(), context)),
                        "Mixed items share a native recipe-book cell");
            }
            require(keys.size() == 1, "Unrelated intended groups share a native cell: " + keys);
            String key = keys.iterator().next();
            require(actualCells.add(key) && ids.equals(expectedCells.get(key)), "Missing/split native cell: " + key);
            cell.selectRecipes(new StackedItemContents(), display ->
                    display instanceof ShapedCraftingRecipeDisplay shaped ? shaped.width() <= 3 && shaped.height() <= 3
                    : !(display instanceof ShapelessCraftingRecipeDisplay shapeless) || shapeless.ingredients().size() <= 9);
            require(cell.getSelectedRecipes(RecipeCollection.CraftableStatus.ANY).size() == cell.getRecipes().size(),
                    "Unlocked variants hidden in native cell");
        }
        require(collected.equals(displays.keySet()) && actualCells.equals(expectedCells.keySet()), "Missing or split native cells");
        return new VerifiedBook(book, recipes.size(), displays.size());
    }

    private static String cellKey(JsonObject row) {
        return row.get("type").getAsString() + "|" + row.get("output").getAsString();
    }

    static ContextMap context(RegistryAccess registries) {
        return new ContextMap.Builder().withParameter(SlotDisplayContext.REGISTRIES, registries)
                .withParameter(SlotDisplayContext.FUEL_VALUES, net.minecraft.world.level.block.entity.FuelValues
                        .vanillaBurnTimes(registries, net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS))
                .create(SlotDisplayContext.CONTEXT);
    }

    private static CompoundTag compound(CompoundTag tag, String key) {
        return tag.getCompound(key).orElseThrow(() -> new IllegalStateException("Missing native fixture compound: " + key));
    }

    private static ItemStack result(RecipeDisplayEntry display, ContextMap context) {
        List<ItemStack> results = display.resultItems(context);
        require(results.size() == 1 && !results.getFirst().isEmpty(), "Recipe display has no unique native result: " + display.id());
        return results.getFirst();
    }

    private static void render(Minecraft minecraft, ClientRecipeBook book, ContextMap context) throws Exception {
        List<RecipeCollection> shown = new ArrayList<>();
        Set<String> selected = Set.of("baseelectric:blank_circuit_board", "baseelectric:integrated_circuit",
                "baseelectric:control_circuit", "baseelectric:psu", "basepower:item_conveyor");
        for (RecipeCollection cell : book.getCollections()) {
            String id = cellOutputId(cell, context);
            if (selected.contains(id) || id.startsWith("basepower:item_filter_")) shown.add(cell);
        }
        shown.sort(java.util.Comparator.comparing(cell -> cellOutputId(cell, context)));
        require(shown.size() == 13, "The thirteen reported circuit/conveyor cells must be separate");
        RecipeBookPage page = new RecipeBookPage(null, () -> 0, false);
        Field bookField = RecipeBookPage.class.getDeclaredField("recipeBook");
        bookField.setAccessible(true);
        bookField.set(page, book);
        int width = 1000, height = 180;
        BufferedImage image = NativeGuiProjection.render(minecraft, width, height, 1, 0xFF1E1E1E, graphics -> {
            var menu = new net.minecraft.world.inventory.CraftingMenu(0,
                    new net.minecraft.world.entity.player.Inventory(null, new net.minecraft.world.entity.EntityEquipment()));
            require(menu.getRecipeBookType() == net.minecraft.world.inventory.RecipeBookType.CRAFTING, "Wrong native crafting menu type");
            for (int i = 0; i < shown.size(); i++) {
                int x = 10 + (i % 5) * 198, y = 12 + (i / 5) * 55;
                RecipeButton button = new RecipeButton(() -> 0);
                button.init(shown.get(i), false, page, context);
                button.setPosition(x, y);
                button.render(graphics, -1, -1, 1F);
                graphics.drawString(minecraft.font, result(shown.get(i).getRecipes().getFirst(), context).getHoverName(),
                        x + 30, y + 8, 0xFFFFFFFF, false);
            }
        });
        int colored = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int rgb = image.getRGB(x, y);
            if ((rgb & 255) > 80 || ((rgb >>> 16) & 255) > 80) colored++;
        }
        ImageIO.write(image, "png", new File("recipe-book-cells.png"));
        require(colored > 1000, "Native recipe buttons rendered blank");
    }

    private static String cellOutputId(RecipeCollection cell, ContextMap context) {
        return BuiltInRegistries.ITEM.getKey(result(cell.getRecipes().getFirst(), context).getItem()).toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
