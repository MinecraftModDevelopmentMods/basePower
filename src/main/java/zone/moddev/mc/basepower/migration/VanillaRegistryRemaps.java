package zone.moddev.mc.basepower.migration;

import java.util.function.UnaryOperator;
import java.util.Set;
import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.datafix.fixes.CavesAndCliffsRenames;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.basepower.PowerAdvantage;

/** Vanilla's data fixers rename saved data, but not Forge's registry snapshot. */
public final class VanillaRegistryRemaps {
    private static final Identifier BIOME_REGISTRY = Identifier.parse("minecraft:worldgen/biome");
    // Data-registry keys only: Forge's static biome registry has no bound native objects.
    private static final Set<ResourceKey<Biome>> NATIVE_BIOMES = Set.of(
            Biomes.THE_VOID, Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.SNOWY_PLAINS,
            Biomes.ICE_SPIKES, Biomes.DESERT, Biomes.SWAMP, Biomes.MANGROVE_SWAMP,
            Biomes.FOREST, Biomes.FLOWER_FOREST, Biomes.BIRCH_FOREST, Biomes.DARK_FOREST,
            Biomes.PALE_GARDEN, Biomes.OLD_GROWTH_BIRCH_FOREST, Biomes.OLD_GROWTH_PINE_TAIGA,
            Biomes.OLD_GROWTH_SPRUCE_TAIGA, Biomes.TAIGA, Biomes.SNOWY_TAIGA, Biomes.SAVANNA,
            Biomes.SAVANNA_PLATEAU, Biomes.WINDSWEPT_HILLS, Biomes.WINDSWEPT_GRAVELLY_HILLS,
            Biomes.WINDSWEPT_FOREST, Biomes.WINDSWEPT_SAVANNA, Biomes.JUNGLE, Biomes.SPARSE_JUNGLE,
            Biomes.BAMBOO_JUNGLE, Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS,
            Biomes.MEADOW, Biomes.CHERRY_GROVE, Biomes.GROVE, Biomes.SNOWY_SLOPES,
            Biomes.FROZEN_PEAKS, Biomes.JAGGED_PEAKS, Biomes.STONY_PEAKS, Biomes.RIVER,
            Biomes.FROZEN_RIVER, Biomes.BEACH, Biomes.SNOWY_BEACH, Biomes.STONY_SHORE,
            Biomes.WARM_OCEAN, Biomes.LUKEWARM_OCEAN, Biomes.DEEP_LUKEWARM_OCEAN, Biomes.OCEAN,
            Biomes.DEEP_OCEAN, Biomes.COLD_OCEAN, Biomes.DEEP_COLD_OCEAN, Biomes.FROZEN_OCEAN,
            Biomes.DEEP_FROZEN_OCEAN, Biomes.MUSHROOM_FIELDS, Biomes.DRIPSTONE_CAVES,
            Biomes.LUSH_CAVES, Biomes.DEEP_DARK, Biomes.NETHER_WASTES, Biomes.WARPED_FOREST,
            Biomes.CRIMSON_FOREST, Biomes.SOUL_SAND_VALLEY, Biomes.BASALT_DELTAS, Biomes.THE_END,
            Biomes.END_HIGHLANDS, Biomes.END_MIDLANDS, Biomes.SMALL_END_ISLANDS, Biomes.END_BARRENS);
    private static final Set<String> LEGACY_PAINTINGS = Set.of("kebab", "aztec", "alban", "aztec2", "bomb",
            "plant", "wasteland", "pool", "courbet", "sea", "sunset", "creebet", "wanderer", "graham",
            "match", "bust", "stage", "void", "skull_and_roses", "wither", "fighters", "pointer", "pigscene",
            "burning_skull", "skeleton", "donkey_kong", "earth", "wind", "water", "fire");
    private static final Set<String> SPLIT_ENTITY_INDEXES = Set.of("boat", "chest_boat", "potion");
    private static final Set<String> REMOVED_SOUND_INDEXES = Set.of("entity.goat.screaming.horn_break",
            "entity.leash_knot.break", "entity.leash_knot.place", "entity.puffer_fish.ambient",
            "entity.wolf.howl", "item.goat_horn.play");

    private VanillaRegistryRemaps() {}

    public static void register() {
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::blocks);
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::items);
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::entities);
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::sounds);
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::biomes);
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::memories);
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::pointsOfInterest);
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::blockEntities);
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::fluids);
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::potions);
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::menus);
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::recipeSerializers);
        MissingMappingsEvent.BUS.addListener(Priority.HIGHEST, VanillaRegistryRemaps::paintings);
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
                id -> Identifier.parse(LegacyMigrationApi.entityTarget(id.toString())));
    }
    private static void sounds(MissingMappingsEvent event) { remap(event, ForgeRegistries.SOUND_EVENTS); }
    private static void paintings(MissingMappingsEvent event) { remap(event, ForgeRegistries.PAINTING_VARIANTS); }
    private static void biomes(MissingMappingsEvent event) { remap(event, ForgeRegistries.BIOMES); }
    private static void memories(MissingMappingsEvent event) { remap(event, ForgeRegistries.MEMORY_MODULE_TYPES); }
    private static void pointsOfInterest(MissingMappingsEvent event) { remap(event, ForgeRegistries.POI_TYPES); }

    private static void blockEntities(MissingMappingsEvent event) {
        remapAdvantage(event, ForgeRegistries.BLOCK_ENTITY_TYPES,
                id -> Identifier.parse(LegacyMigrationApi.tileTarget(id.toString())));
    }
    private static void fluids(MissingMappingsEvent event) {
        remapAdvantage(event, ForgeRegistries.FLUIDS,
                id -> Identifier.parse(LegacyMigrationApi.fluidTarget(id.toString())));
    }
    private static void potions(MissingMappingsEvent event) { remap(event, ForgeRegistries.POTIONS); }
    private static void menus(MissingMappingsEvent event) {
        remapAdvantage(event, ForgeRegistries.MENU_TYPES, LegacyMigrationApi::registryTarget);
    }
    private static void recipeSerializers(MissingMappingsEvent event) {
        remapAdvantage(event, ForgeRegistries.RECIPE_SERIALIZERS, LegacyMigrationApi::registryTarget);
    }

    private static <T> void remapAdvantage(MissingMappingsEvent event,
            IForgeRegistry<T> active, UnaryOperator<Identifier> replacement) {
        for (MissingMappingsEvent.Mapping<T> mapping : event.getAllMappings(active.getRegistryKey())) {
            Identifier target = replacement.apply(mapping.getKey());
            if (!mapping.getKey().equals(target) && active.containsKey(target)) {
                mapping.remap(active.getValue(target));
                PowerAdvantage.LOGGER.info("Migrated Advantage registry entry {} to {}", mapping.getKey(), target);
            }
        }
    }

    static <T> void remap(MissingMappingsEvent event, IForgeRegistry<T> active) {
        for (MissingMappingsEvent.Mapping<T> mapping : event.getMappings(active.getRegistryKey(), "minecraft")) {
            if (retiredPaintingIndex(active.getRegistryName(), mapping.getKey())) {
                // These names still exist in the native data-pack registry. Only
                // Forge's old numeric index is retired; entity variant NBT is unchanged.
                mapping.ignore();
                continue;
            }
            if (active.getRegistryName().equals(Identifier.parse("minecraft:potion"))
                    && mapping.getKey().equals(Identifier.parse("minecraft:empty"))) {
                // An absent potion is now represented by empty potion contents, not a registry entry.
                mapping.ignore();
                continue;
            }
            if (retiredTransientMemory(active.getRegistryName(), mapping.getKey())) {
                // Both old types used register(name) without a serializer: no saved
                // brain values exist. Retire only their obsolete Forge ID indexes.
                mapping.ignore();
                PowerAdvantage.LOGGER.info("Retired non-serialized vanilla AI registry index {}", mapping.getKey());
                continue;
            }
            if (retiredVanillaPoi(active.getRegistryName(), mapping.getKey())) {
                // Vanilla's "Remove unpopulated villager PoI types" fixer handles
                // saved POI sections. Forge still sees the obsolete numeric indexes.
                mapping.ignore();
                PowerAdvantage.LOGGER.info("Retired unused vanilla point of interest index {}", mapping.getKey());
                continue;
            }
            if (retiredNativeIndex(active.getRegistryName(), mapping.getKey())) {
                // BoatSplitFix and ThrownPotionSplitFix select the concrete type
                // from entity NBT. Reusing one type here would collapse variants.
                // Removed sounds have no persistent world payload to translate.
                mapping.ignore();
                PowerAdvantage.LOGGER.info("Retired obsolete vanilla registry index {} from {}",
                        mapping.getKey(), active.getRegistryName());
                continue;
            }
            Identifier updatedBiome = vanillaBiomeIndexTarget(active.getRegistryName(), mapping.getKey());
            if (updatedBiome != null) {
                // Native data registries retain current names and DFU fixes old names
                // in terrain/settings. Retire only Forge's obsolete numeric index.
                mapping.ignore();
                PowerAdvantage.LOGGER.info("Retired vanilla biome registry index {} (data fixer: {})",
                        mapping.getKey(), updatedBiome);
                continue;
            }
            Identifier replacement = replacement(active.getRegistryName(), mapping.getKey());
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

    static boolean retiredPaintingIndex(Identifier registry, Identifier old) {
        return registry.equals(Identifier.parse("minecraft:painting_variant"))
                && old.getNamespace().equals("minecraft") && LEGACY_PAINTINGS.contains(old.getPath());
    }

    static boolean retiredTransientMemory(Identifier registry, Identifier old) {
        return registry.equals(Identifier.parse("minecraft:memory_module_type"))
                && old.getNamespace().equals("minecraft")
                && (old.getPath().equals("golem_last_seen_time") || old.getPath().equals("opened_doors"));
    }

    static boolean retiredVanillaPoi(Identifier registry, Identifier old) {
        return registry.equals(Identifier.parse("minecraft:point_of_interest_type"))
                && old.getNamespace().equals("minecraft")
                && (old.getPath().equals("nitwit") || old.getPath().equals("unemployed"));
    }

    private static boolean retiredNativeIndex(Identifier registry, Identifier old) {
        if (!old.getNamespace().equals("minecraft")) return false;
        return registry.equals(Identifier.parse("minecraft:entity_type")) && SPLIT_ENTITY_INDEXES.contains(old.getPath())
                || registry.equals(Identifier.parse("minecraft:sound_event")) && REMOVED_SOUND_INDEXES.contains(old.getPath());
    }

    static Identifier vanillaBiomeFixTarget(Identifier registry, Identifier old) {
        if (!BIOME_REGISTRY.equals(registry) || !"minecraft".equals(old.getNamespace())) return null;
        String name = old.toString();
        String updated = name.equals("minecraft:nether") ? Biomes.NETHER_WASTES.identifier().toString()
                : name.equals("minecraft:deep_warm_ocean") ? Biomes.WARM_OCEAN.identifier().toString()
                : CavesAndCliffsRenames.RENAMES.get(name);
        return updated == null ? null : Identifier.parse(updated);
    }

    private static Identifier vanillaBiomeIndexTarget(Identifier registry, Identifier old) {
        if (!BIOME_REGISTRY.equals(registry) || !"minecraft".equals(old.getNamespace())) return null;
        Identifier renamed = vanillaBiomeFixTarget(registry, old);
        Identifier target = renamed == null ? old : renamed;
        return NATIVE_BIOMES.contains(ResourceKey.create(Registries.BIOME, target)) ? target : null;
    }

    static Identifier replacement(Identifier registry, Identifier old) {
        if (!"minecraft".equals(old.getNamespace())) return null;
        String target = null;
        if ((registry.equals(Identifier.parse("minecraft:block")) || registry.equals(Identifier.parse("minecraft:item")))
                && old.getPath().equals("grass_path")) {
            target = "dirt_path";
        } else if ((registry.equals(Identifier.parse("minecraft:block")) || registry.equals(Identifier.parse("minecraft:item")))
                && old.getPath().equals("grass")) {
            target = "short_grass";
        } else if ((registry.equals(Identifier.parse("minecraft:block")) || registry.equals(Identifier.parse("minecraft:item")))
                && old.getPath().equals("chain")) {
            target = "iron_chain";
        } else if (registry.equals(Identifier.parse("minecraft:enchantment")) && old.getPath().equals("sweeping")) {
            target = "sweeping_edge";
        } else if (registry.equals(Identifier.parse("minecraft:entity_type")) && old.getPath().equals("zombie_pigman")) {
            target = "zombified_piglin";
        } else if (registry.equals(Identifier.parse("minecraft:item")) && old.getPath().equals("zombie_pigman_spawn_egg")) {
            target = "zombified_piglin_spawn_egg";
        } else if (registry.equals(Identifier.parse("minecraft:item"))) {
            // Forge keeps the 1.19.4 experimental IDs even when that data pack was not enabled.
            target = switch (old.getPath()) {
                case "pottery_shard_archer" -> "archer_pottery_sherd";
                case "pottery_shard_arms_up" -> "arms_up_pottery_sherd";
                case "pottery_shard_prize" -> "prize_pottery_sherd";
                case "pottery_shard_skull" -> "skull_pottery_sherd";
                case "scute" -> "turtle_scute";
                default -> null;
            };
        } else if (registry.equals(Identifier.parse("minecraft:worldgen/biome")) && old.getPath().equals("nether")) {
            target = "nether_wastes";
        } else if (registry.equals(Identifier.parse("minecraft:sound_event"))) {
            if (old.getPath().equals("block.trial_spawner.ambient_charged")) target = "block.trial_spawner.ambient_ominous";
            if (old.getPath().equals("block.trial_spawner.charge_activate")) target = "block.trial_spawner.ominous_activate";
            for (String sound : new String[] {"ambient", "angry", "death", "hurt"}) {
                if (old.getPath().equals("entity.zombie_pigman." + sound)) target = "entity.zombified_piglin." + sound;
            }
            // The former single Nether biome becomes Nether Wastes in 1.16.
            if (old.getPath().equals("music.nether")) target = "music.nether.nether_wastes";
            if (old.getPath().equals("item.sweet_berries.pick_from_bush")) target = "block.sweet_berry_bush.pick_berries";
            if (old.getPath().equals("item.brush.brushing")) target = "item.brush.brushing.generic";
            if (old.getPath().equals("item.brush.brush_sand_completed")) target = "item.brush.brushing.sand.complete";
            // Native biomes choose their own new music; this alias is for the old shared registry ID.
            if (old.getPath().equals("music.overworld.jungle_and_forest")) target = "music.overworld.forest";
        }
        return target == null ? null : Identifier.fromNamespaceAndPath("minecraft", target);
    }
}
