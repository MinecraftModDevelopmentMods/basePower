package zone.moddev.mc.basepower.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.basepower.PowerAdvantage;

/** Adds Advantage parts to the selected vanilla chest tables, not to data-pack replacements. */
public final class PartsLoot extends LootModifier {
    private static final Set<String> CHESTS = Set.of("chests/abandoned_mineshaft", "chests/simple_dungeon",
            "chests/village/village_toolsmith", "chests/stronghold_corridor",
            "chests/stronghold_crossing", "chests/end_city_treasure");
    private static final List<String> MODS = List.of("basepower", "basesteam", "baseelectric");
    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, PowerAdvantage.MODID);
    private static final MapCodec<PartsLoot> CODEC =
            RecordCodecBuilder.mapCodec(instance -> codecStart(instance).apply(instance, PartsLoot::new));

    static { SERIALIZERS.register("vanilla_chest_parts", () -> CODEC); }

    private PartsLoot(LootItemCondition[] conditions) { super(conditions); }

    public static void register(IEventBus bus) { SERIALIZERS.register(bus); }

    @Override public MapCodec<PartsLoot> codec() { return CODEC; }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        ResourceLocation table = context.getQueriedLootTableId();
        if (!isChest(table) || PowerAdvantage.chestLootFactor <= 0 || hasAdditionalLootTables()) return loot;
        // Forge 50 fires LootTableLoadEvent for all packs and doesn't include the source.
        var resourceManager = context.getLevel().getServer().getResourceManager();
        String source = resourceManager.getResource(table.withPath(path -> "loot_tables/" + path + ".json"))
                .map(Resource::sourcePackId).orElse("");
        for (ResourceLocation extra : partTables(table, source, ModList.get()::isLoaded)) {
            var key = ResourceKey.create(Registries.LOOT_TABLE, extra);
            context.getResolver().get(Registries.LOOT_TABLE, key)
                    .ifPresent(holder -> holder.value().getRandomItemsRaw(context, loot::add));
        }
        return loot;
    }

    static List<ResourceLocation> partTables(ResourceLocation table, String source, Predicate<String> loaded) {
        if (!isChest(table) || !"vanilla".equals(source)) return List.of();
        return MODS.stream()
                .filter(mod -> loaded.test(mod)
                        && !(mod.equals("basepower") && table.getPath().equals("chests/end_city_treasure")))
                .map(mod -> ResourceLocation.fromNamespaceAndPath(mod, "chests/advantage_parts")).toList();
    }

    private static boolean isChest(ResourceLocation table) {
        return table != null && "minecraft".equals(table.getNamespace()) && CHESTS.contains(table.getPath());
    }

    private static boolean hasAdditionalLootTables() {
        return ModList.get().getMods().stream().anyMatch(mod -> mod.getModId().replace("_", "").replace("-", "")
                .equals("additionalloottables"));
    }
}
