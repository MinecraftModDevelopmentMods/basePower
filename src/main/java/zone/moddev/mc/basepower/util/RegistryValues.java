package zone.moddev.mc.basepower.util;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

/** Optional lookups must not mistake a defaulted registry's air for a real entry. */
public final class RegistryValues {
    private RegistryValues() { }

    public static <T> T find(Registry<T> registry, ResourceLocation id) {
        return registry.getOptional(id).orElse(null);
    }
}
