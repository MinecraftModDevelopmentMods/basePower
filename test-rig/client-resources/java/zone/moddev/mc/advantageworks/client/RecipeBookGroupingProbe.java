package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.stats.RecipeBook;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.RegistryManager;

/** Decodes actual server recipe packets and exercises Minecraft's real cell grouping. */
final class RecipeBookGroupingProbe {
    private static final int GL_COLOR_BUFFER_BIT = 16384, GL_DEPTH_BUFFER_BIT = 256, GL_RGBA = 6408, GL_UNSIGNED_BYTE = 5121;
    static void verify(Minecraft minecraft, JsonObject report) {
        Path root = Path.of("recipe-book-fixtures");
        if (!Files.isDirectory(root)) {
            report.addProperty("recipe_book_grouping_status", "NOT_RUN_NO_NATIVE_FIXTURES");
            return;
        }
        JsonArray checks = new JsonArray();
        report.add("recipe_book_grouping", checks);
        try (Stream<Path> files = Files.list(root)) {
            // A separate main-menu client has not performed Forge's registry handshake.
            // Import only the generated server fixture's ID snapshot, never a user world.
            Map<ResourceLocation, ForgeRegistry.Snapshot> registry = RegistryManager.ACTIVE.takeSnapshot(false);
            CompoundTag level = NbtIo.readCompressed(root.resolve("server-registries.dat"), NbtAccounter.unlimitedHeap());
            require("1.21.1".equals(level.getCompound("Data").getCompound("Version").getString("Name")),
                    "Recipe fixture is not a native 1.21.1 server export");
            ListTag mods = level.getCompound("fml").getList("LoadingModList", Tag.TAG_COMPOUND);
            boolean nativeForge = false;
            for (int i = 0; i < mods.size(); i++) {
                CompoundTag mod = mods.getCompound(i);
                if ("forge".equals(mod.getString("ModId"))) nativeForge = "52.1.0".equals(mod.getString("ModVersion"));
            }
            require(nativeForge, "Recipe fixture was not saved by Forge 52.1.0");
            CompoundTag saved = level.getCompound("fml").getCompound("Registries");
            String itemRegistry = ForgeRegistries.ITEMS.getRegistryName().toString();
            String blockRegistry = ForgeRegistries.BLOCKS.getRegistryName().toString();
            require(saved.contains(itemRegistry, Tag.TAG_COMPOUND) && saved.contains(blockRegistry, Tag.TAG_COMPOUND)
                    && !saved.getCompound(itemRegistry).getList("ids", Tag.TAG_COMPOUND).isEmpty()
                    && !saved.getCompound(blockRegistry).getList("ids", Tag.TAG_COMPOUND).isEmpty(), "Missing native server registry snapshot");
            for (String key : saved.getAllKeys()) registry.put(ResourceLocation.parse(key),
                    ForgeRegistry.Snapshot.read(saved.getCompound(key)));
            require(GameData.injectSnapshot(registry, false, false).isEmpty(), "Client lacks a server registry entry");
            RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            List<Path> packets = files.filter(file -> file.toString().endsWith(".bin")).sorted().toList();
            Set<String> packetNames = packets.stream().map(file -> file.getFileName().toString()).collect(Collectors.toSet());
            require(packetNames.equals(Set.of("recipes-normal-sweep.bin", "recipes-tech-sweep.bin", "recipes-apocalyptic-sweep.bin")),
                    "Normal, Tech and Apocalyptic recipe-book packets are required");
            for (Path file : packets) {
                Path manifestPath = file.resolveSibling(file.getFileName().toString().replace(".bin", ".json"));
                JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
                require("PASS".equals(manifest.get("status").getAsString()), "Server recipe fixture did not pass");
                Map<String, JsonObject> expected = new HashMap<>();
                manifest.getAsJsonArray("bookRecipes").forEach(row -> require(expected.put(row.getAsJsonObject().get("id").getAsString(),
                        row.getAsJsonObject()) == null, "Duplicate recipe in packet manifest"));
                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                        Unpooled.wrappedBuffer(Files.readAllBytes(file)), registries);
                ClientboundUpdateRecipesPacket packet;
                try {
                    packet = ClientboundUpdateRecipesPacket.STREAM_CODEC.decode(buffer);
                    require(!buffer.isReadable(), "Trailing recipe packet bytes");
                }
                finally { buffer.release(); }
                RecipeManager manager = new RecipeManager(registries);
                Set<String> cellKeys = new HashSet<>();
                for (RecipeHolder<?> holder : packet.getRecipes()) {
                    Recipe<?> recipe = holder.value();
                    JsonObject row = expected.get(holder.id().toString());
                    require(row != null, "Unexpected network recipe " + holder.id());
                    ItemStack stack = recipe.getResultItem(registries);
                    String output = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                    require(output.equals(row.get("output").getAsString()) && stack.getCount() == row.get("count").getAsInt(),
                            "Client/server item registry mismatch for " + holder.id());
                    require(recipe.getGroup().equals(row.get("group").getAsString()) && recipe.getGroup().equals(output), "Group changed in recipe packet");
                    String type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
                    require(type.equals(row.get("type").getAsString()), "Recipe type changed in packet");
                    cellKeys.add(type + "|" + output);
                }
                manager.replaceRecipes(packet.getRecipes());
                require(manager.getRecipes().size() == expected.size(), "Missing network recipes");
                ClientRecipeBook book = new ClientRecipeBook();
                manager.getRecipes().forEach(book::add);
                book.setupCollections(manager.getRecipes(), registries);
                int counted = 0;
                for (RecipeCollection cell : book.getCollections()) {
                    Set<String> outputs = cell.getRecipes().stream().map(holder -> ForgeRegistries.ITEMS.getKey(
                            holder.value().getResultItem(registries).getItem()).toString())
                            .collect(Collectors.toSet());
                    require(outputs.size() == 1 && cell.hasSingleResultItem(), "Mixed items share a native recipe-book cell: " + outputs);
                    counted += cell.getRecipes().size();
                    cell.updateKnownRecipes(book);
                    cell.canCraft(new StackedContents(), 3, 3, book);
                    require(cell.getRecipes(false).size() == cell.getRecipes().size(), "Unlocked variants hidden in native cell");
                }
                require(counted == expected.size() && book.getCollections().size() == cellKeys.size(), "Missing or split native cells");
                JsonObject check = new JsonObject();
                check.addProperty("packet", file.getFileName().toString());
                check.addProperty("recipes", counted);
                check.addProperty("cells", book.getCollections().size());
                check.addProperty("status", "PASS");
                checks.add(check);
                if (file.getFileName().toString().equals("recipes-normal-sweep.bin")) render(minecraft, book);
            }
        } catch (Exception failure) { throw new IllegalStateException("Native recipe-book validation failed", failure); }
        report.addProperty("recipe_book_grouping_status", "PASS");
    }

    private static void render(Minecraft minecraft, ClientRecipeBook book) throws Exception {
        List<RecipeCollection> shown = new ArrayList<>();
        Set<String> selected = new HashSet<>(java.util.Arrays.asList("baseelectric:blank_circuit_board",
                "baseelectric:integrated_circuit", "baseelectric:control_circuit", "baseelectric:psu", "basepower:item_conveyor"));
        for (RecipeCollection cell : book.getCollections()) {
            String id = cellOutputId(cell);
            if (selected.contains(id) || id.startsWith("basepower:item_filter_")) shown.add(cell);
        }
        shown.sort(java.util.Comparator.comparing(RecipeBookGroupingProbe::cellOutputId));
        require(shown.size() == 13, "The thirteen reported circuit/conveyor cells must be separate");
        int width = 1000, height = 180;
        RenderTarget target = new com.mojang.blaze3d.pipeline.TextureTarget(width, height, true, Minecraft.ON_OSX);
        target.bindWrite(true);
        NativeGuiProjection projection = new NativeGuiProjection(width, height, -1000, 1000, 0);
        RenderSystem.disableDepthTest();
        try {
            RenderSystem.clearColor(0.12F, 0.12F, 0.12F, 1F);
            RenderSystem.clear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            net.minecraft.world.inventory.CraftingMenu menu = new net.minecraft.world.inventory.CraftingMenu(0, new net.minecraft.world.entity.player.Inventory(null));
            require(menu.getRecipeBookType() == net.minecraft.world.inventory.RecipeBookType.CRAFTING, "Wrong native crafting menu type");
            var graphics = new net.minecraft.client.gui.GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
            for (int i = 0; i < shown.size(); i++) {
                int x = 10 + (i % 5) * 198, y = 12 + (i / 5) * 55;
                RecipeButton button = new RecipeButton();
                // Supply the native menu type without opening a world or connecting a player.
                for (Field field : RecipeButton.class.getDeclaredFields()) {
                    if (field.getType() == RecipeCollection.class || field.getType() == RecipeBook.class) {
                        field.setAccessible(true);
                        field.set(button, field.getType() == RecipeCollection.class ? shown.get(i) : book);
                    } else if (field.getType() == net.minecraft.world.inventory.RecipeBookMenu.class) {
                        field.setAccessible(true);
                        field.set(button, menu);
                    }
                }
                button.setPosition(x, y);
                button.render(graphics, -1, -1, 1F);
                graphics.drawString(minecraft.font, cellResult(shown.get(i)).getDisplayName(), x + 30, y + 8, 0xFFFFFF, false);
            }
            graphics.flush();
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
            RenderSystem.readPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            int colored = 0;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int index = ((height - y - 1) * width + x) * 4;
                int rgb = (pixels.get(index) & 255) << 16 | (pixels.get(index + 1) & 255) << 8 | pixels.get(index + 2) & 255;
                image.setRGB(x, y, rgb);
                if ((rgb & 255) > 80 || ((rgb >>> 16) & 255) > 80) colored++;
            }
            ImageIO.write(image, "png", new File("recipe-book-cells.png"));
            require(colored > 1000, "Native recipe buttons rendered blank");
        } finally {
            projection.close();
            target.destroyBuffers(); minecraft.getMainRenderTarget().bindWrite(true);
            RenderSystem.enableDepthTest();
        }
    }

    private static ItemStack cellResult(RecipeCollection cell) {
        return cell.getRecipes().get(0).value().getResultItem(cell.registryAccess());
    }

    private static String cellOutputId(RecipeCollection cell) {
        return ForgeRegistries.ITEMS.getKey(cellResult(cell).getItem()).toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
