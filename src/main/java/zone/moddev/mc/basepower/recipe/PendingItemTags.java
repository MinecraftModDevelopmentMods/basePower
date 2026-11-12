package zone.moddev.mc.basepower.recipe;

import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;

/** Carries this reload's actual contents, before the named tags are bound. */
public record PendingItemTags(HolderLookup.RegistryLookup<Item> parent,
        Registry.PendingTags<Item> pending) implements HolderLookup.RegistryLookup.Delegate<Item> {
    public static List<HolderLookup.RegistryLookup<?>> attach(List<HolderLookup.RegistryLookup<?>> lookups,
            List<Registry.PendingTags<?>> tags) {
        for (var pending : tags) {
            if (pending.key() == Registries.ITEM) {
                @SuppressWarnings("unchecked")
                var itemTags = (Registry.PendingTags<Item>) pending;
                return lookups.stream().<HolderLookup.RegistryLookup<?>>map(lookup -> {
                    if (lookup.key() != Registries.ITEM) return lookup;
                    @SuppressWarnings("unchecked")
                    var items = (HolderLookup.RegistryLookup<Item>) lookup;
                    return new PendingItemTags(items, itemTags);
                }).toList();
            }
        }
        return lookups;
    }
}
