package zone.moddev.mc.advantageworks.client;

import net.minecraft.core.registries.BuiltInRegistries;

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
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.RegistrySnapshot;
import net.neoforged.neoforge.registries.RegistryManager;

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
            // Import a native server handshake export, never infer IDs from a user world.
            CompoundTag level = NbtIo.readCompressed(root.resolve("server-registries.dat"), NbtAccounter.unlimitedHeap());
            require("1.21.1".equals(level.getCompound("Data").getCompound("Version").getString("Name")),
                    "Recipe fixture is not a native 1.21.1 server export");
            ListTag mods = level.getCompound("fml").getList("LoadingModList", Tag.TAG_COMPOUND);
            boolean nativeNeoForge = false;
            for (int i = 0; i < mods.size(); i++) {
                CompoundTag mod = mods.getCompound(i);
                if ("neoforge".equals(mod.getString("ModId"))) nativeNeoForge = "21.1.265".equals(mod.getString("ModVersion"));
            }
            require(nativeNeoForge, "Recipe fixture was not saved by NeoForge 21.1.265");
            List<Path> binaries = files.filter(file -> file.toString().endsWith(".bin")).sorted().toList();
            List<Path> packets = binaries.stream().filter(file -> !file.toString().endsWith(".registry.bin")
                    && !file.getFileName().toString().equals("server-registry-snapshot.bin")).sorted().toList();
            Set<String> packetNames = packets.stream().map(file -> file.getFileName().toString()).collect(Collectors.toSet());
            require(packetNames.equals(Set.of("recipes-normal-sweep.bin", "recipes-tech-sweep.bin", "recipes-apocalyptic-sweep.bin")),
                    "Normal, Tech and Apocalyptic recipe-book packets are required");
            Set<String> snapshotNames = binaries.stream().filter(file -> file.toString().endsWith(".registry.bin"))
                    .map(file -> file.getFileName().toString()).collect(Collectors.toSet());
            require(snapshotNames.equals(packetNames.stream().map(RecipeBookGroupingProbe::snapshotName).collect(Collectors.toSet())),
                    "Each recipe packet requires its own exact registry snapshot sidecar");
            for (Path file : packets) {
                Path manifestPath = file.resolveSibling(file.getFileName().toString().replace(".bin", ".json"));
                JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
                require("PASS".equals(manifest.get("status").getAsString()), "Server recipe fixture did not pass");
                NativeRecipeFixture fixture = loadNativeFixture(file, manifest);
                RegistryAccess registries = fixture.registries();
                Map<String, JsonObject> expected = new HashMap<>();
                manifest.getAsJsonArray("bookRecipes").forEach(row -> require(expected.put(row.getAsJsonObject().get("id").getAsString(),
                        row.getAsJsonObject()) == null, "Duplicate recipe in packet manifest"));
                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                        Unpooled.wrappedBuffer(fixture.packetBytes()), registries);
                ClientboundUpdateRecipesPacket packet;
                try {
                    packet = ClientboundUpdateRecipesPacket.STREAM_CODEC.decode(buffer);
                    require(!buffer.isReadable(), "Trailing recipe packet bytes");
                }
                finally { buffer.release(); }
                require(packet.getRecipes().size() == manifest.getAsJsonObject("registrySnapshot").get("recipeCount").getAsInt(),
                        "Recipe packet count differs from its registry-bound receipt");
                RecipeManager manager = new RecipeManager(registries);
                Set<String> cellKeys = new HashSet<>();
                for (RecipeHolder<?> holder : packet.getRecipes()) {
                    Recipe<?> recipe = holder.value();
                    JsonObject row = expected.get(holder.id().toString());
                    require(row != null, "Unexpected network recipe " + holder.id());
                    ItemStack stack = recipe.getResultItem(registries);
                    String output = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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
                    Set<String> outputs = cell.getRecipes().stream().map(holder -> BuiltInRegistries.ITEM.getKey(
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

    private record NativeRecipeFixture(RegistryAccess registries, byte[] packetBytes) {}

    private static String snapshotName(String packetName) {
        require(packetName.matches("recipes-[a-zA-Z0-9_-]+\\.bin"), "Unsafe recipe packet filename");
        return packetName.substring(0, packetName.length() - 4) + ".registry.bin";
    }

    private static void validateNativeReceipt(Path packetFile, JsonObject receipt, byte[] packetBytes, byte[] snapshotBytes)
            throws Exception {
        String packetName = packetFile.getFileName().toString();
        require(receipt.get("schema").getAsInt() == 1
                && "1.21.1".equals(receipt.get("minecraftVersion").getAsString())
                && "21.1.265".equals(receipt.get("neoForgeVersion").getAsString())
                && "SYNC_TO_CLIENT".equals(receipt.get("snapshotType").getAsString()), "Wrong native registry receipt schema/version");
        require(packetName.equals(receipt.get("packetFile").getAsString())
                && snapshotName(packetName).equals(receipt.get("file").getAsString()), "Registry receipt binds a different packet or snapshot");
        require(receipt.get("sameRegistries").getAsBoolean(), "Packet was not encoded with its exported registry IDs");
        require(packetBytes.length > 0 && packetBytes.length == receipt.get("packetBytes").getAsInt()
                && snapshotBytes.length > 0 && snapshotBytes.length == receipt.get("bytes").getAsInt(), "Native fixture byte count mismatch");
        require(receipt.get("registryCount").getAsInt() > 0 && receipt.get("recipeCount").getAsInt() > 0,
                "Empty native registry/recipe receipt");
        String packetHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(packetBytes));
        String snapshotHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(snapshotBytes));
        require(packetHash.equals(receipt.get("packetSha256").getAsString())
                && snapshotHash.equals(receipt.get("sha256").getAsString()), "Native fixture SHA-256 mismatch");
    }

    private static NativeRecipeFixture loadNativeFixture(Path packetFile, JsonObject manifest) throws Exception {
        JsonObject receipt = manifest.getAsJsonObject("registrySnapshot");
        Path snapshotFile = packetFile.resolveSibling(snapshotName(packetFile.getFileName().toString()));
        require(Files.isRegularFile(snapshotFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(packetFile, java.nio.file.LinkOption.NOFOLLOW_LINKS), "Missing native per-packet handshake export");
        byte[] packetBytes = Files.readAllBytes(packetFile);
        byte[] snapshotBytes = Files.readAllBytes(snapshotFile);
        validateNativeReceipt(packetFile, receipt, packetBytes, snapshotBytes);
        require(manifest.getAsJsonArray("bookRecipes").size() == receipt.get("recipeCount").getAsInt(), "Recipe manifest count mismatch");
        var snapshotBuffer = new net.minecraft.network.FriendlyByteBuf(Unpooled.wrappedBuffer(snapshotBytes));
        Map<ResourceLocation, RegistrySnapshot> registry;
        try {
            int wireCount = snapshotBuffer.readVarInt();
            snapshotBuffer.readerIndex(0);
            require(wireCount == receipt.get("registryCount").getAsInt(), "Registry wire count mismatch");
            registry = snapshotBuffer.readMap(net.minecraft.network.FriendlyByteBuf::readResourceLocation, RegistrySnapshot.STREAM_CODEC::decode);
            require(!snapshotBuffer.isReadable(), "Trailing registry snapshot bytes");
        } finally { snapshotBuffer.release(); }
        require(registry.size() == receipt.get("registryCount").getAsInt(), "Registry snapshot count mismatch");
        require(registry.containsKey(Registries.ITEM.location()) && registry.containsKey(Registries.BLOCK.location())
                && !registry.get(Registries.ITEM.location()).getIds().isEmpty()
                && !registry.get(Registries.BLOCK.location()).getIds().isEmpty(), "Missing native server registry snapshot");
        require(RegistryManager.applySnapshot(registry, false).isEmpty(), "Client lacks a server registry entry");
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (var entry : registry.entrySet()) {
            net.minecraft.core.Registry<?> applied = registries.registryOrThrow(net.minecraft.resources.ResourceKey.createRegistryKey(entry.getKey()));
            require(applied.size() == entry.getValue().getIds().size(), "Applied registry size mismatch");
            for (var id : entry.getValue().getIds().int2ObjectEntrySet()) {
                require(applied.byId(id.getIntKey()) != null && applied.byId(id.getIntKey()) == applied.get(id.getValue()),
                        "Client did not apply the packet's exact registry IDs");
            }
        }
        return new NativeRecipeFixture(registries, packetBytes);
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
        return BuiltInRegistries.ITEM.getKey(cellResult(cell).getItem()).toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
