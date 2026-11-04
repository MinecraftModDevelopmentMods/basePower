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
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.datafix.IFixableData;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.IForgeRegistryEntry;

/** Read compatibility for released Cyano 1.10 worlds; new saves use base identities. */
@Mod.EventBusSubscriber(modid = "basepower")
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

    public static <T extends IForgeRegistryEntry<T>> void remapMissingMappings(
            RegistryEvent.MissingMappings<T> event, IForgeRegistry<T> registry, String oldModId, String newModId) {
        for (RegistryEvent.MissingMappings.Mapping<T> mapping : event.getAllMappings()) {
            if (!oldModId.equals(mapping.key.getNamespace())) continue;
            ResourceLocation target = new ResourceLocation(newModId, mapping.key.getPath());
            if (registry.containsKey(target)) {
                mapping.remap(registry.getValue(target));
            } else {
                mapping.fail();
                FMLLog.severe("%s: no migration target for %s", newModId, mapping.key);
            }
        }
    }

    private static <T extends IForgeRegistryEntry<T>> void remapInstalled(
            RegistryEvent.MissingMappings<T> event, IForgeRegistry<T> registry) {
        remapMissingMappings(event, registry, "poweradvantage", "basepower");
        if (Loader.isModLoaded("basesteam")) remapMissingMappings(event, registry, "steamadvantage", "basesteam");
        if (Loader.isModLoaded("baseelectric")) remapMissingMappings(event, registry, "electricadvantage", "baseelectric");
    }

    @SubscribeEvent public static void remapBlocks(RegistryEvent.MissingMappings<Block> event) {
        remapInstalled(event, ForgeRegistries.BLOCKS);
    }

    @SubscribeEvent public static void remapItems(RegistryEvent.MissingMappings<Item> event) {
        remapInstalled(event, ForgeRegistries.ITEMS);
    }

    @SubscribeEvent public static void remapRecipes(RegistryEvent.MissingMappings<IRecipe> event) {
        remapInstalled(event, ForgeRegistries.RECIPES);
    }

    @SubscribeEvent public static void remapEnchantments(RegistryEvent.MissingMappings<Enchantment> event) {
        remapInstalled(event, ForgeRegistries.ENCHANTMENTS);
    }

    @SubscribeEvent public static void remapRemovedOrbSound(RegistryEvent.MissingMappings<SoundEvent> event) {
        // Forge 1.10 snapshots include the unused orb-touch event; 1.12 only has pickup.
        ResourceLocation old = new ResourceLocation("minecraft", "entity.experience_orb.touch");
        ResourceLocation pickup = new ResourceLocation("minecraft", "entity.experience_orb.pickup");
        if (!ForgeRegistries.SOUND_EVENTS.containsKey(pickup)) return;
        for (RegistryEvent.MissingMappings.Mapping<SoundEvent> mapping : event.getAllMappings()) {
            if (old.equals(mapping.key)) mapping.remap(ForgeRegistries.SOUND_EVENTS.getValue(pickup));
        }
    }

    public static IFixableData tileIds(String oldModId, String newModId, int version) {
        return idFix(version, id -> {
            for (String prefix : new String[] {oldModId + ":", oldModId + ".", newModId + ".",
                    "minecraft:" + oldModId + ".", "minecraft:" + newModId + "."}) {
                if (id.startsWith(prefix)) return newModId + ":" + id.substring(prefix.length());
            }
            return id;
        });
    }

    public static IFixableData itemIds(String oldModId, String newModId, int version) {
        return idFix(version, id -> {
            if (!id.startsWith(oldModId + ":")) return id;
            ResourceLocation target = new ResourceLocation(newModId, id.substring(oldModId.length() + 1));
            return net.minecraftforge.fml.common.registry.ForgeRegistries.ITEMS.containsKey(target)
                    ? target.toString() : id;
        });
    }

    public static IFixableData idFix(int version, java.util.function.UnaryOperator<String> rename) {
        return new IFixableData() {
            @Override public int getFixVersion() { return version; }
            @Override public NBTTagCompound fixTagCompound(NBTTagCompound tag) {
                if (tag.hasKey("id", 8)) {
                    String id = tag.getString("id");
                    String canonical = rename.apply(id);
                    if (!id.equals(canonical)) tag.setString("id", canonical);
                }
                return tag;
            }
        };
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
        return value.startsWith(oldModId + ":") ? newModId + value.substring(oldModId.length()) : value;
    }

    private static void requireModId(String id) {
        if (!id.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid migration namespace: " + id);
    }
}
