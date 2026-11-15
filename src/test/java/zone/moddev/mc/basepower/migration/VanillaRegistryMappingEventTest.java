package zone.moddev.mc.basepower.migration;

import com.mojang.serialization.Lifecycle;
import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import java.lang.reflect.Constructor;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.junit.Test;
import static org.junit.Assert.*;

/** NeoForge uses native aliases, not Forge's staging-registry MissingMappingsEvent. */
public class VanillaRegistryMappingEventTest {
    private static ResourceLocation id(String value) { return ResourceLocation.parse(value); }

    private static Registry<String> registry() {
        LegacyMigrationApi.registerNamespaceAlias("poweradvantage", "basepower");
        @SuppressWarnings("unchecked")
        ResourceKey<? extends Registry<String>> key = (ResourceKey<? extends Registry<String>>) (ResourceKey<?>) Registries.ITEM;
        Registry<String> registry = new MappedRegistry<>(key, Lifecycle.stable());
        Registry.register(registry, id("basepower:fixture"), "fixture");
        return registry;
    }

    private static void register(Registry<?> registry) throws Exception {
        Constructor<RegisterEvent> ctor = RegisterEvent.class.getDeclaredConstructor(ResourceKey.class, Registry.class);
        ctor.setAccessible(true);
        VanillaRegistryRemaps.registerAliases(ctor.newInstance(registry.key(), registry));
    }

    @Test public void legacyNamespaceResolvesTheSameLiveNativeValue() throws Exception {
        Registry<String> registry = registry(); register(registry);
        assertEquals("fixture", registry.get(id("poweradvantage:fixture")));
        assertEquals("fixture", registry.get(id("basepower:fixture")));
    }

    @Test public void aliasesDoNotReplaceARealRegistration() throws Exception {
        Registry<String> registry = registry();
        Registry.register(registry, id("poweradvantage:fixture"), "existing");
        register(registry);
        assertEquals("existing", registry.get(id("poweradvantage:fixture")));
    }

    @Test public void registrationDoesNotInventUnknownReplacementItems() throws Exception {
        Registry<String> registry = registry(); register(registry);
        assertTrue(registry.getOptional(id("poweradvantage:absent")).isEmpty());
        assertTrue(registry.getOptional(id("example:fixture")).isEmpty());
    }

    @Test public void registryAliasesAreIdempotent() throws Exception {
        Registry<String> registry = registry(); register(registry); register(registry);
        assertEquals("fixture", registry.get(id("poweradvantage:fixture")));
        assertEquals(1, registry.size());
    }

    @Test public void foreignNamespacesRemainUntouched() throws Exception {
        Registry<String> registry = registry();
        Registry.register(registry, id("example:fixture"), "foreign"); register(registry);
        assertEquals("foreign", registry.get(id("example:fixture")));
        assertEquals(2, registry.size());
    }
}
