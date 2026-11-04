package cyano.poweradvantage.api;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registry of semantic fluid categories shared by Advantage mods.
 *
 * <p>Categories use fluid registry names rather than implementation classes, so
 * sibling mods can interoperate without compile-time dependencies on one
 * another.</p>
 */
public final class FluidCategoryRegistry {
    public static final String CRUDE_OIL = "crude_oil";

    private static final Map<String, LinkedHashSet<String>> ALIASES = new LinkedHashMap<>();

    static {
        registerAlias(CRUDE_OIL, "crude_oil");
        registerAlias(CRUDE_OIL, "mineralogy_crude_oil");
        registerAlias(CRUDE_OIL, "oil");
    }

    private FluidCategoryRegistry() {
    }

    /** Adds a fluid registry name to a semantic category. */
    public static synchronized boolean registerAlias(String category, String fluidName) {
        String normalizedCategory = normalize(category);
        String normalizedFluid = normalize(fluidName);
        return ALIASES.computeIfAbsent(normalizedCategory, ignored -> new LinkedHashSet<>())
                .add(normalizedFluid);
    }

    /** Returns true when the fluid's registry name belongs to the category. */
    public static synchronized boolean matches(String category, Fluid fluid) {
        return fluid != null && matches(category, fluid.getName());
    }

    /** Returns true when a registry name belongs to the category. */
    public static synchronized boolean matches(String category, String fluidName) {
        if (category == null || fluidName == null) return false;
        Set<String> aliases = ALIASES.get(normalize(category));
        return aliases != null && aliases.contains(normalize(fluidName));
    }

    /** Returns an immutable snapshot of the registered names for a category. */
    public static synchronized Set<String> getAliases(String category) {
        Set<String> aliases = ALIASES.get(normalize(category));
        if (aliases == null) return Collections.emptySet();
        return Collections.unmodifiableSet(new LinkedHashSet<>(aliases));
    }

    /** Returns the currently registered fluids represented by a category. */
    public static synchronized Set<Fluid> getRegisteredFluids(String category) {
        LinkedHashSet<Fluid> fluids = new LinkedHashSet<>();
        for (String name : getAliases(category)) {
            Fluid fluid = FluidRegistry.getFluid(name);
            if (fluid != null) fluids.add(fluid);
        }
        return Collections.unmodifiableSet(fluids);
    }

    private static String normalize(String value) {
        if (value == null) throw new NullPointerException("Category and fluid names cannot be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Category and fluid names cannot be empty");
        return normalized;
    }
}