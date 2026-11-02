package zone.moddev.mc.advantageworks;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.util.ResourceLocation;

/** These four released enchantment registry keys intentionally retain their old domain. */
final class CanonicalSteamEnchantments {
    private static final Set<String> PATHS = new HashSet<>(Arrays.asList(
            "rapid_reload", "powderless", "high_explosive", "recoil"));

    static String canonicalId(String id) {
        if (id.startsWith("basesteam:") && PATHS.contains(id.substring("basesteam:".length())))
            throw new IllegalArgumentException("Retained canonical enchantment required, no new-domain fallback: " + id);
        return id;
    }

    static Enchantment require(String id) {
        ResourceLocation name = new ResourceLocation(canonicalId(id));
        if ("steamadvantage".equals(name.getResourceDomain()))
            for (String path : PATHS) resolve(new ResourceLocation("steamadvantage", path));
        return resolve(name);
    }

    private static Enchantment resolve(ResourceLocation name) {
        Enchantment enchantment = Enchantment.REGISTRY.getObject(name);
        if (enchantment == null || !Enchantment.REGISTRY.containsKey(name)
                || !name.equals(Enchantment.REGISTRY.getNameForObject(enchantment)))
            throw new IllegalStateException("Missing or noncanonical native enchantment: " + name);
        return enchantment;
    }
}
