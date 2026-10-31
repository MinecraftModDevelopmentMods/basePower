package zone.moddev.mc.poweradvantage.recipe;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.tags.Tag;
import net.minecraft.tags.TagCollection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Registry;
import net.minecraftforge.fml.ModList;
import zone.moddev.mc.poweradvantage.PowerAdvantage;
import zone.moddev.mc.poweradvantage.RecipeMode;
import zone.moddev.mc.poweradvantage.init.Items;

/** Config-dependent aliases resolved by the server's native tag reload. */
public final class MaterialTags {
    private MaterialTags() {}

    public static void registerPack(net.minecraftforge.event.AddPackFindersEvent event) {
        if (event.getPackType() != net.minecraft.server.packs.PackType.SERVER_DATA) return;
        event.addRepositorySource((consumer, constructor) -> {
            net.minecraft.server.packs.repository.Pack pack = net.minecraft.server.packs.repository.Pack.create(
                    "poweradvantage:material_policy", true, MaterialPolicyPack::new, constructor,
                    net.minecraft.server.packs.repository.Pack.Position.BOTTOM,
                    net.minecraft.server.packs.repository.PackSource.BUILT_IN);
            if (pack != null) consumer.accept(pack);
        });
    }

    static TagCollection<Item> applyPolicy(TagCollection<Item> tags, Item plastic,
            boolean rubber, boolean solder) {
        if (!rubber && !solder) return tags;
        Map<ResourceLocation, Tag<Item>> result = new LinkedHashMap<>(tags.getAllTags());
        if (rubber) {
            append(result, "forge:rubber", java.util.Collections.singleton(plastic));
            append(result, "forge:ingots/rubber", java.util.Collections.singleton(plastic));
        }
        if (solder) {
            for (String metal : new String[] {"lead", "tin", "silver"}) {
                append(result, "forge:solder", tags.getTagOrEmpty(
                        new ResourceLocation("forge", "ingots/" + metal)).getValues());
            }
        }
        return TagCollection.of(result);
    }

    private static void append(Map<ResourceLocation, Tag<Item>> tags, String name,
            java.util.Collection<Item> additions) {
        ResourceLocation id = new ResourceLocation(name);
        Set<Item> values = new LinkedHashSet<>();
        Tag<Item> existing = tags.get(id);
        if (existing != null) values.addAll(existing.getValues());
        for (Item item : additions) if (item != null) values.add(item);
        tags.put(id, Tag.fromSet(values));
    }
}
