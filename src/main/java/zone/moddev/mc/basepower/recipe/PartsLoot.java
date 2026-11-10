package zone.moddev.mc.basepower.recipe;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraftforge.event.LootTableLoadEvent;
import net.minecraftforge.fml.ModList;
import zone.moddev.mc.basepower.PowerAdvantage;

/** Forge emits this event for bundled tables, leaving user data-pack tables untouched. */
public final class PartsLoot {
    private static final Set<String> CHESTS = new HashSet<>(Arrays.asList(
            "chests/abandoned_mineshaft", "chests/simple_dungeon", "chests/village/village_toolsmith",
            "chests/stronghold_corridor", "chests/stronghold_crossing", "chests/end_city_treasure"));

    private PartsLoot() {}

    public static void onLoad(LootTableLoadEvent event) {
        addPools(event.getTable(), event.getName(), PowerAdvantage.chestLootFactor > 0 && !hasAdditionalLootTables(),
                mod -> ModList.get().isLoaded(mod));
    }

    static void addPools(net.minecraft.world.level.storage.loot.LootTable table, ResourceLocation name,
            boolean enabled, java.util.function.Predicate<String> loaded) {
        if (!"minecraft".equals(name.getNamespace()) || !CHESTS.contains(name.getPath())
                || !enabled) return;
        for (String mod : new String[] {"basepower", "basesteam", "baseelectric"}) {
            if (!loaded.test(mod)
                    || (mod.equals("basepower") && name.getPath().equals("chests/end_city_treasure"))) continue;
            String poolName = mod + ":legacy_parts";
            LootPool existing = table.removePool(poolName);
            if (existing != null) {
                table.addPool(existing);
                continue;
            }
            table.addPool(LootPool.lootPool().name(poolName).setRolls(ConstantValue.exactly(1))
                    .add(NestedLootTable.lootTableReference(ResourceKey.create(Registries.LOOT_TABLE,
                            new ResourceLocation(mod, "chests/advantage_parts")))).build());
        }
    }

    private static boolean hasAdditionalLootTables() {
        return ModList.get().getMods().stream().anyMatch(mod -> mod.getModId().replace("_", "").replace("-", "")
                .equals("additionalloottables"));
    }
}
