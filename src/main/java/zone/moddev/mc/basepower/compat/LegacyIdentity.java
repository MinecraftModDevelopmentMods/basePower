package zone.moddev.mc.basepower.compat;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.event.FMLMissingMappingsEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

/** Read compatibility for released Cyano 1.10 worlds; new saves use base identities. */
public final class LegacyIdentity {
    private LegacyIdentity() { }

    public static void requireReplacementsOnly(java.util.function.Predicate<String> loaded) {
        for (String old : new String[] { "poweradvantage", "steamadvantage", "electricadvantage" }) {
            if (loaded.test(old)) {
                throw new IllegalStateException("Remove the old " + old
                        + " jar before loading basePower, baseSteam and baseElectric. These are replacements, not add-ons.");
            }
        }
    }

    public static void remapMissingMappings(FMLMissingMappingsEvent event, String oldModId, String newModId) {
        int count = 0;
        for (FMLMissingMappingsEvent.MissingMapping mapping : event.getAll()) {
            if (!oldModId.equals(mapping.resourceLocation.getResourceDomain())) continue;
            ResourceLocation target = new ResourceLocation(newModId, mapping.resourceLocation.getResourcePath());
            if (mapping.type == GameRegistry.Type.BLOCK && Block.REGISTRY.containsKey(target)) {
                mapping.remap(Block.REGISTRY.getObject(target));
                count++;
            } else if (mapping.type == GameRegistry.Type.ITEM && Item.REGISTRY.containsKey(target)) {
                mapping.remap(Item.REGISTRY.getObject(target));
                count++;
            } else {
                // Never silently replace an unsupported legacy component with air.
                mapping.fail();
                FMLLog.severe("%s: no migration target for %s %s", newModId, mapping.type, mapping.name);
            }
        }
        if (count > 0) FMLLog.info("%s: remapped %d released %s registry entries", newModId, count, oldModId);
    }

    public static void registerTileEntity(Class<? extends TileEntity> type, String canonical, String... alternatives) {
        if (alternatives.length == 0 && canonical.startsWith("basepower.")) {
            alternatives = new String[] { "poweradvantage" + canonical.substring("basepower".length()) };
        }
        GameRegistry.registerTileEntityWithAlternatives(type, canonical, alternatives);
    }

    public static File migrateConfiguration(File newConfig, String oldModId) {
        requireModId(oldModId);
        Path target = newConfig.toPath().toAbsolutePath().normalize();
        Path legacy = target.resolveSibling(oldModId + ".cfg");
        try {
            if (!Files.exists(target) && Files.isRegularFile(legacy)) {
                Files.copy(legacy, target);
                FMLLog.info("Imported %s as %s; the original configuration is unchanged", legacy, target);
            }
            return newConfig;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot preserve legacy configuration " + legacy, error);
        }
    }

    public static void migrateAdditionalLootTables(Path config, String oldModId, String newModId) {
        requireModId(oldModId);
        requireModId(newModId);
        Path root = config.toAbsolutePath().normalize().resolve("additional-loot-tables");
        Path legacy = root.resolve(oldModId);
        Path target = root.resolve(newModId);
        if (!Files.isDirectory(legacy)) return;
        try (Stream<Path> walk = Files.walk(legacy)) {
            List<Path> sources = walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json")).collect(Collectors.toList());
            for (Path source : sources) {
                if (Files.isSymbolicLink(source)) throw new IOException("Refusing linked loot file " + source);
                Path destination = target.resolve(legacy.relativize(source)).normalize();
                if (!destination.startsWith(target)) throw new IOException("Loot migration escaped its directory");
                if (!Files.exists(destination)) {
                    JsonElement document = new JsonParser().parse(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
                    remapJsonValues(document, oldModId, newModId);
                    Files.createDirectories(destination.getParent());
                    Files.write(destination, new GsonBuilder().setPrettyPrinting().create().toJson(document)
                            .getBytes(StandardCharsets.UTF_8));
                }
                // ALT reads every *.json. Preserve old bytes outside that suffix to avoid double loot.
                Path backup = source.resolveSibling(source.getFileName() + ".before-" + newModId);
                if (Files.exists(backup)) throw new IOException("Existing loot backup requires review: " + backup);
                Files.move(source, backup);
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot safely migrate legacy loot tables from " + legacy, error);
        }
    }

    public static void remapJsonValues(JsonElement value, String oldModId, String newModId) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(object.entrySet());
            for (Map.Entry<String, JsonElement> entry : entries) {
                JsonElement child = entry.getValue();
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    object.add(entry.getKey(), new JsonPrimitive(remapIdentifier(child.getAsString(), oldModId, newModId)));
                } else remapJsonValues(child, oldModId, newModId);
            }
        } else if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            List<JsonElement> children = new ArrayList<>();
            Iterator<JsonElement> iterator = array.iterator();
            while (iterator.hasNext()) {
                children.add(iterator.next());
                iterator.remove();
            }
            for (JsonElement child : children) {
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    array.add(new JsonPrimitive(remapIdentifier(child.getAsString(), oldModId, newModId)));
                } else {
                    remapJsonValues(child, oldModId, newModId);
                    array.add(child);
                }
            }
        }
    }

    public static String remapIdentifier(String value, String oldModId, String newModId) {
        if ("steamadvantage".equals(oldModId)
                && java.util.Arrays.asList("steamadvantage:rapid_reload", "steamadvantage:powderless",
                        "steamadvantage:high_explosive", "steamadvantage:recoil").contains(value)) {
            // Forge 1.10 has no enchantment missing-mapping event; those saved keys remain stable.
            return value;
        }
        return value.startsWith(oldModId + ":") ? newModId + value.substring(oldModId.length()) : value;
    }

    private static void requireModId(String id) {
        if (!id.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid migration namespace: " + id);
    }
}
