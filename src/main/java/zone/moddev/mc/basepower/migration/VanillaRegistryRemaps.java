package zone.moddev.mc.basepower.migration;

import java.util.function.UnaryOperator;
import java.util.Set;
import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.datafix.fixes.CavesAndCliffsRenames;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import zone.moddev.mc.basepower.PowerAdvantage;

/** Vanilla's data fixers rename saved data, but not Forge's registry snapshot. */
public final class VanillaRegistryRemaps {
    private static final ResourceLocation BIOME_REGISTRY = ResourceLocation.parse("minecraft:worldgen/biome");
    private static final Set<String> LEGACY_PAINTINGS = Set.of("kebab", "aztec", "alban", "aztec2", "bomb",
            "plant", "wasteland", "pool", "courbet", "sea", "sunset", "creebet", "wanderer", "graham",
            "match", "bust", "stage", "void", "skull_and_roses", "wither", "fighters", "pointer", "pigscene",
            "burning_skull", "skeleton", "donkey_kong", "earth", "wind", "water", "fire");

    private VanillaRegistryRemaps() {}

    /** Aliases apply to named registries; legacy numeric snapshots are handled before data fixing. */
    public static void registerAliases(net.neoforged.neoforge.registries.RegisterEvent event) {
        net.minecraft.core.Registry<?> registry = event.getRegistry();
        ResourceLocation registryId = event.getRegistryKey().location();
        for (ResourceLocation current : java.util.List.copyOf(registry.keySet())) {
            if (!LegacyMigrationApi.handlesNamespace(current.getNamespace())) continue;
            for (String namespace : LegacyMigrationApi.namespaces()) {
                ResourceLocation legacy = ResourceLocation.fromNamespaceAndPath(namespace, current.getPath());
                ResourceLocation target = migrationTarget(registryId, legacy);
                if (!legacy.equals(target) && target.equals(current) && !registry.containsKey(legacy)) {
                    registry.addAlias(legacy, target);
                }
                ResourceLocation prefixed = ResourceLocation.withDefaultNamespace(namespace + "." + current.getPath());
                if (registryId.getPath().equals("block_entity_type")) {
                    ResourceLocation tileTarget = ResourceLocation.tryParse(LegacyMigrationApi.tileTarget(prefixed.toString()));
                    if (current.equals(tileTarget) && !registry.containsKey(prefixed)) registry.addAlias(prefixed, current);
                }
            }
        }
        if (registryId.getPath().equals("block") || registryId.getPath().equals("item")) {
            for (ResourceLocation oldId : LegacyMigrationApi.blockStateCodecs().keySet()) {
                ResourceLocation target = migrationTarget(registryId, oldId);
                if (!oldId.equals(target) && registry.containsKey(target) && !registry.containsKey(oldId))
                    registry.addAlias(oldId, target);
            }
        }
    }

    private static ResourceLocation migrationTarget(ResourceLocation registry, ResourceLocation id) {
        return switch (registry.getPath()) {
            case "block" -> LegacyMigrationApi.blockTarget(id);
            case "item" -> LegacyMigrationApi.itemTarget(id);
            case "entity_type" -> ResourceLocation.parse(LegacyMigrationApi.entityTarget(id.toString()));
            case "block_entity_type" -> ResourceLocation.parse(LegacyMigrationApi.tileTarget(id.toString()));
            case "fluid" -> ResourceLocation.parse(LegacyMigrationApi.fluidTarget(id.toString()));
            default -> LegacyMigrationApi.registryTarget(id);
        };
    }

    static boolean retiredPaintingIndex(ResourceLocation registry, ResourceLocation old) {
        return registry.equals(ResourceLocation.parse("minecraft:painting_variant"))
                && old.getNamespace().equals("minecraft") && LEGACY_PAINTINGS.contains(old.getPath());
    }

    static boolean retiredTransientMemory(ResourceLocation registry, ResourceLocation old) {
        return registry.equals(ResourceLocation.parse("minecraft:memory_module_type"))
                && old.getNamespace().equals("minecraft")
                && (old.getPath().equals("golem_last_seen_time") || old.getPath().equals("opened_doors"));
    }

    static boolean retiredVanillaPoi(ResourceLocation registry, ResourceLocation old) {
        return registry.equals(ResourceLocation.parse("minecraft:point_of_interest_type"))
                && old.getNamespace().equals("minecraft")
                && (old.getPath().equals("nitwit") || old.getPath().equals("unemployed"));
    }

    static ResourceLocation vanillaBiomeFixTarget(ResourceLocation registry, ResourceLocation old) {
        if (!BIOME_REGISTRY.equals(registry) || !"minecraft".equals(old.getNamespace())) return null;
        String name = old.toString();
        String updated = name.equals("minecraft:deep_warm_ocean") ? "minecraft:warm_ocean"
                : CavesAndCliffsRenames.RENAMES.get(name);
        return updated == null ? null : ResourceLocation.parse(updated);
    }

    static ResourceLocation replacement(ResourceLocation registry, ResourceLocation old) {
        if (!"minecraft".equals(old.getNamespace())) return null;
        String target = null;
        if ((registry.equals(ResourceLocation.parse("minecraft:block")) || registry.equals(ResourceLocation.parse("minecraft:item")))
                && old.getPath().equals("grass_path")) {
            target = "dirt_path";
        } else if ((registry.equals(ResourceLocation.parse("minecraft:block")) || registry.equals(ResourceLocation.parse("minecraft:item")))
                && old.getPath().equals("grass")) {
            target = "short_grass";
        } else if (registry.equals(ResourceLocation.parse("minecraft:enchantment")) && old.getPath().equals("sweeping")) {
            target = "sweeping_edge";
        } else if (registry.equals(ResourceLocation.parse("minecraft:entity_type")) && old.getPath().equals("zombie_pigman")) {
            target = "zombified_piglin";
        } else if (registry.equals(ResourceLocation.parse("minecraft:item")) && old.getPath().equals("zombie_pigman_spawn_egg")) {
            target = "zombified_piglin_spawn_egg";
        } else if (registry.equals(ResourceLocation.parse("minecraft:item"))) {
            // Forge keeps the 1.19.4 experimental IDs even when that data pack was not enabled.
            target = switch (old.getPath()) {
                case "pottery_shard_archer" -> "archer_pottery_sherd";
                case "pottery_shard_arms_up" -> "arms_up_pottery_sherd";
                case "pottery_shard_prize" -> "prize_pottery_sherd";
                case "pottery_shard_skull" -> "skull_pottery_sherd";
                case "scute" -> "turtle_scute";
                default -> null;
            };
        } else if (registry.equals(ResourceLocation.parse("minecraft:worldgen/biome")) && old.getPath().equals("nether")) {
            target = "nether_wastes";
        } else if (registry.equals(ResourceLocation.parse("minecraft:sound_event"))) {
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
        return target == null ? null : ResourceLocation.fromNamespaceAndPath("minecraft", target);
    }
}
