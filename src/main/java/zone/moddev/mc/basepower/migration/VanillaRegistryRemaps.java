package zone.moddev.mc.basepower.migration;

import java.util.function.UnaryOperator;
import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.datafix.fixes.CavesAndCliffsRenames;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.basepower.PowerAdvantage;

/** Vanilla's data fixers rename saved data, but not Forge's registry snapshot. */
public final class VanillaRegistryRemaps {
    private static final ResourceLocation BIOME_REGISTRY = new ResourceLocation("minecraft:worldgen/biome");

    private VanillaRegistryRemaps() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, VanillaRegistryRemaps::blocks);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, VanillaRegistryRemaps::items);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, VanillaRegistryRemaps::entities);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, VanillaRegistryRemaps::sounds);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, VanillaRegistryRemaps::biomes);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, VanillaRegistryRemaps::memories);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, VanillaRegistryRemaps::blockEntities);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, VanillaRegistryRemaps::fluids);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, VanillaRegistryRemaps::enchantments);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, VanillaRegistryRemaps::menus);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, VanillaRegistryRemaps::recipeSerializers);
    }

    private static void blocks(MissingMappingsEvent event) {
        remap(event, ForgeRegistries.BLOCKS);
        remapAdvantage(event, ForgeRegistries.BLOCKS, LegacyMigrationApi::blockTarget);
    }
    private static void items(MissingMappingsEvent event) {
        remap(event, ForgeRegistries.ITEMS);
        remapAdvantage(event, ForgeRegistries.ITEMS, LegacyMigrationApi::itemTarget);
    }
    private static void entities(MissingMappingsEvent event) {
        remap(event, ForgeRegistries.ENTITY_TYPES);
        remapAdvantage(event, ForgeRegistries.ENTITY_TYPES,
                id -> new ResourceLocation(LegacyMigrationApi.entityTarget(id.toString())));
    }
    private static void sounds(MissingMappingsEvent event) { remap(event, ForgeRegistries.SOUND_EVENTS); }
    private static void biomes(MissingMappingsEvent event) { remap(event, ForgeRegistries.BIOMES); }
    private static void memories(MissingMappingsEvent event) { remap(event, ForgeRegistries.MEMORY_MODULE_TYPES); }

    private static void blockEntities(MissingMappingsEvent event) {
        remapAdvantage(event, ForgeRegistries.BLOCK_ENTITY_TYPES,
                id -> new ResourceLocation(LegacyMigrationApi.tileTarget(id.toString())));
    }
    private static void fluids(MissingMappingsEvent event) {
        remapAdvantage(event, ForgeRegistries.FLUIDS,
                id -> new ResourceLocation(LegacyMigrationApi.fluidTarget(id.toString())));
    }
    private static void enchantments(MissingMappingsEvent event) {
        remapAdvantage(event, ForgeRegistries.ENCHANTMENTS, LegacyMigrationApi::registryTarget);
    }
    private static void menus(MissingMappingsEvent event) {
        remapAdvantage(event, ForgeRegistries.MENU_TYPES, LegacyMigrationApi::registryTarget);
    }
    private static void recipeSerializers(MissingMappingsEvent event) {
        remapAdvantage(event, ForgeRegistries.RECIPE_SERIALIZERS, LegacyMigrationApi::registryTarget);
    }

    private static <T> void remapAdvantage(MissingMappingsEvent event,
            IForgeRegistry<T> active, UnaryOperator<ResourceLocation> replacement) {
        for (MissingMappingsEvent.Mapping<T> mapping : event.getAllMappings(active.getRegistryKey())) {
            ResourceLocation target = replacement.apply(mapping.getKey());
            if (!mapping.getKey().equals(target) && active.containsKey(target)) {
                mapping.remap(active.getValue(target));
                PowerAdvantage.LOGGER.info("Migrated Advantage registry entry {} to {}", mapping.getKey(), target);
            }
        }
    }

    static <T> void remap(MissingMappingsEvent event, IForgeRegistry<T> active) {
        for (MissingMappingsEvent.Mapping<T> mapping : event.getMappings(active.getRegistryKey(), "minecraft")) {
            if (retiredTransientMemory(active.getRegistryName(), mapping.getKey())) {
                // Both old types used register(name) without a serializer: no saved
                // brain values exist. Retire only their obsolete Forge ID indexes.
                mapping.ignore();
                PowerAdvantage.LOGGER.info("Retired non-serialized vanilla AI registry index {}", mapping.getKey());
                continue;
            }
            ResourceLocation updatedBiome = vanillaBiomeFixTarget(active.getRegistryName(), mapping.getKey());
            if (updatedBiome != null && active.containsKey(updatedBiome)) {
                // Vanilla fixes biome names in chunks and world settings. Several
                // old entries share one target; do not assign it multiple numeric IDs.
                mapping.ignore();
                PowerAdvantage.LOGGER.info("Retired vanilla biome registry index {} (data fixer: {})",
                        mapping.getKey(), updatedBiome);
                continue;
            }
            ResourceLocation replacement = replacement(active.getRegistryName(), mapping.getKey());
            if (replacement == null) continue;
            // The event's registry is STAGING: new entries need not be present there yet.
            if (!active.containsKey(replacement)) {
                throw new IllegalStateException("Missing native vanilla migration target " + replacement);
            }
            T target = active.getValue(replacement);
            mapping.remap(target);
            PowerAdvantage.LOGGER.info("Migrated vanilla registry entry {} to {}", mapping.getKey(), replacement);
        }
    }

    static boolean retiredTransientMemory(ResourceLocation registry, ResourceLocation old) {
        return registry.equals(new ResourceLocation("minecraft:memory_module_type"))
                && old.getNamespace().equals("minecraft")
                && (old.getPath().equals("golem_last_seen_time") || old.getPath().equals("opened_doors"));
    }

    static ResourceLocation vanillaBiomeFixTarget(ResourceLocation registry, ResourceLocation old) {
        if (!BIOME_REGISTRY.equals(registry) || !"minecraft".equals(old.getNamespace())) return null;
        String name = old.toString();
        String updated = name.equals("minecraft:deep_warm_ocean") ? "minecraft:warm_ocean"
                : CavesAndCliffsRenames.RENAMES.get(name);
        return updated == null ? null : new ResourceLocation(updated);
    }

    static ResourceLocation replacement(ResourceLocation registry, ResourceLocation old) {
        if (!"minecraft".equals(old.getNamespace())) return null;
        String target = null;
        if ((registry.equals(new ResourceLocation("minecraft:block")) || registry.equals(new ResourceLocation("minecraft:item")))
                && old.getPath().equals("grass_path")) {
            target = "dirt_path";
        } else if (registry.equals(new ResourceLocation("minecraft:entity_type")) && old.getPath().equals("zombie_pigman")) {
            target = "zombified_piglin";
        } else if (registry.equals(new ResourceLocation("minecraft:item")) && old.getPath().equals("zombie_pigman_spawn_egg")) {
            target = "zombified_piglin_spawn_egg";
        } else if (registry.equals(new ResourceLocation("minecraft:worldgen/biome")) && old.getPath().equals("nether")) {
            target = "nether_wastes";
        } else if (registry.equals(new ResourceLocation("minecraft:sound_event"))) {
            for (String sound : new String[] {"ambient", "angry", "death", "hurt"}) {
                if (old.getPath().equals("entity.zombie_pigman." + sound)) target = "entity.zombified_piglin." + sound;
            }
            // The former single Nether biome becomes Nether Wastes in 1.16.
            if (old.getPath().equals("music.nether")) target = "music.nether.nether_wastes";
            if (old.getPath().equals("item.sweet_berries.pick_from_bush")) target = "block.sweet_berry_bush.pick_berries";
        }
        return target == null ? null : new ResourceLocation("minecraft", target);
    }
}
