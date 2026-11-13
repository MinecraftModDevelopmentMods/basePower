package zone.moddev.mc.basepower.migration;

import java.lang.reflect.Proxy;
import java.util.Collections;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.registries.IForgeRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public class VanillaRegistryMappingEventTest {
    private static final Identifier SOURCE = Identifier.parse("minecraft:zombie_pigman_spawn_egg");
    private static final Identifier TARGET = Identifier.parse("minecraft:zombified_piglin_spawn_egg");

    @Test public void resolvesTheTargetFromActiveNotTheIncompleteStagingRegistry() {
        Entry target = new Entry();
        IForgeRegistry<Entry> staging = registry(null, false), active = registry(target, true);
        MissingMappingsEvent.Mapping<Entry> mapping = new MissingMappingsEvent.Mapping<>(staging, active, SOURCE, 42);
        VanillaRegistryRemaps.remap(event(staging, mapping), active);
        assertEquals(MissingMappingsEvent.Action.REMAP, property(mapping, "action"));
        assertSame(target, property(mapping, "target"));
        assertEquals(42, mapping.getId());
    }

    @Test public void aLaterExplicitForgeDecisionOverridesTheCompatibilityMapping() {
        IForgeRegistry<Entry> active = registry(new Entry(), true);
        MissingMappingsEvent.Mapping<Entry> mapping = new MissingMappingsEvent.Mapping<>(active, active, SOURCE, 42);
        var group = net.minecraftforge.eventbus.api.bus.BusGroup.create("basepower-migration-test");
        var bus = net.minecraftforge.eventbus.api.bus.EventBus.create(group, MissingMappingsEvent.class);
        bus.addListener(net.minecraftforge.eventbus.api.listener.Priority.HIGHEST,
                (MissingMappingsEvent event) -> VanillaRegistryRemaps.remap(event, active));
        bus.addListener(net.minecraftforge.eventbus.api.listener.Priority.NORMAL,
                (MissingMappingsEvent event) -> mapping.ignore());
        group.startup();
        try {
            bus.post(event(active, mapping));
        } finally {
            group.dispose();
        }
        assertEquals(MissingMappingsEvent.Action.IGNORE, property(mapping, "action"));
    }

    @Test public void leavesUnrecognizedEntriesUnresolvedForForgeToReport() {
        IForgeRegistry<Entry> active = registry(new Entry(), true);
        MissingMappingsEvent.Mapping<Entry> mapping = new MissingMappingsEvent.Mapping<>(active, active,
                Identifier.parse("othermod:zombie_pigman_spawn_egg"), 42);
        VanillaRegistryRemaps.remap(event(active, mapping), active);
        assertEquals(MissingMappingsEvent.Action.DEFAULT, property(mapping, "action"));
    }

    @Test public void neverSubstitutesARegistryDefaultForAMissingTarget() {
        IForgeRegistry<Entry> absent = registry(new Entry(), false);
        MissingMappingsEvent.Mapping<Entry> mapping = new MissingMappingsEvent.Mapping<>(absent, absent, SOURCE, 42);
        try {
            VanillaRegistryRemaps.remap(event(absent, mapping), absent);
            fail("A registry default must not replace the missing item");
        } catch (IllegalStateException expected) {
            assertEquals(MissingMappingsEvent.Action.DEFAULT, property(mapping, "action"));
        }
    }

    @Test public void retiresOnlyTheTwoNonSerializedVanillaMemoryIndexes() {
        IForgeRegistry<Entry> active = registry(null, false, "minecraft:memory_module_type");
        for (String name : new String[] {"golem_last_seen_time", "opened_doors"}) {
            MissingMappingsEvent.Mapping<Entry> mapping = new MissingMappingsEvent.Mapping<>(active, active,
                    Identifier.fromNamespaceAndPath("minecraft", name), 42);
            MissingMappingsEvent event = new MissingMappingsEvent(key("minecraft:memory_module_type"),
                    active, Collections.singletonList(mapping));
            VanillaRegistryRemaps.remap(event, active);
            assertEquals(MissingMappingsEvent.Action.IGNORE, property(mapping, "action"));
            assertNull(property(mapping, "target"));
        }
    }

    @Test public void neverRetiresOtherModsOrCurrentMemoryTypes() {
        Identifier registry = Identifier.parse("minecraft:memory_module_type");
        assertFalse(VanillaRegistryRemaps.retiredTransientMemory(registry, Identifier.parse("othermod:opened_doors")));
        assertFalse(VanillaRegistryRemaps.retiredTransientMemory(registry, Identifier.parse("minecraft:golem_detected_recently")));
        assertFalse(VanillaRegistryRemaps.retiredTransientMemory(Identifier.parse("minecraft:item"),
                Identifier.parse("minecraft:opened_doors")));
    }

    @Test public void retiresOnlyTheRemovedSerializedTemptationIndex() {
        IForgeRegistry<Entry> active = registry(null, false, "minecraft:memory_module_type");
        var mapping = new MissingMappingsEvent.Mapping<>(active, active,
                Identifier.parse("minecraft:is_tempted"), 43);
        VanillaRegistryRemaps.remap(new MissingMappingsEvent(key("minecraft:memory_module_type"),
                active, Collections.singletonList(mapping)), active);
        assertEquals(MissingMappingsEvent.Action.IGNORE, property(mapping, "action"));
        assertNull(property(mapping, "target"));
        assertFalse(VanillaRegistryRemaps.retiredTemptationMemory(
                Identifier.parse("minecraft:item"), Identifier.parse("minecraft:is_tempted")));
        assertFalse(VanillaRegistryRemaps.retiredTemptationMemory(
                Identifier.parse("minecraft:memory_module_type"), Identifier.parse("othermod:is_tempted")));
        assertFalse(VanillaRegistryRemaps.retiredTemptationMemory(
                Identifier.parse("minecraft:memory_module_type"), Identifier.parse("minecraft:temptation_cooldown_ticks")));
    }

    @Test public void retiresOnlyTheTwoPointsOfInterestRemovedByVanilla() {
        IForgeRegistry<Entry> active = registry(null, false, "minecraft:point_of_interest_type");
        for (String id : new String[] {"minecraft:nitwit", "minecraft:unemployed", "minecraft:home", "othermod:nitwit"}) {
            var mapping = new MissingMappingsEvent.Mapping<>(active, active, Identifier.parse(id), 42);
            VanillaRegistryRemaps.remap(event(active, mapping), active);
            boolean retired = id.equals("minecraft:nitwit") || id.equals("minecraft:unemployed");
            assertEquals(id, retired ? MissingMappingsEvent.Action.IGNORE : MissingMappingsEvent.Action.DEFAULT,
                    property(mapping, "action"));
            assertNull(property(mapping, "target"));
        }
    }

    @Test public void retiresOnlyTheRemovedVanillaEmptyPotionIndex() {
        IForgeRegistry<Entry> active = registry(null, false, "minecraft:potion");
        for (String id : new String[] {"minecraft:empty", "minecraft:water", "othermod:empty"}) {
            var mapping = new MissingMappingsEvent.Mapping<>(active, active, Identifier.parse(id), 42);
            VanillaRegistryRemaps.remap(event(active, mapping), active);
            assertEquals(id, id.equals("minecraft:empty") ? MissingMappingsEvent.Action.IGNORE
                    : MissingMappingsEvent.Action.DEFAULT, property(mapping, "action"));
            assertNull(property(mapping, "target"));
        }
    }

    @Test public void movedPaintingsRetireOnlyTheirKnownStaticIndexes() {
        IForgeRegistry<Entry> active = registry(null, false, "minecraft:painting_variant");
        for (String id : new String[] {"minecraft:alban", "minecraft:aztec", "minecraft:unknown", "othermod:alban"}) {
            var mapping = new MissingMappingsEvent.Mapping<>(active, active, Identifier.parse(id), 42);
            VanillaRegistryRemaps.remap(event(active, mapping), active);
            assertEquals(id, id.equals("minecraft:alban") || id.equals("minecraft:aztec")
                    ? MissingMappingsEvent.Action.IGNORE : MissingMappingsEvent.Action.DEFAULT, property(mapping, "action"));
            assertNull(property(mapping, "target"));
        }
    }

    private static MissingMappingsEvent event(IForgeRegistry<Entry> staging, MissingMappingsEvent.Mapping<Entry> mapping) {
        return new MissingMappingsEvent(staging.getRegistryKey(), staging, Collections.singletonList(mapping));
    }

    private static ResourceKey<Registry<Entry>> key(String id) {
        return ResourceKey.createRegistryKey(Identifier.parse(id));
    }

    // Forge keeps the result fields package-private for its snapshot loader.
    private static Object property(MissingMappingsEvent.Mapping<Entry> mapping, String name) {
        try {
            var field = mapping.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(mapping);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static IForgeRegistry<Entry> registry(Entry target, boolean contains) {
        return registry(target, contains, "minecraft:item");
    }

    @SuppressWarnings("unchecked")
    private static IForgeRegistry<Entry> registry(Entry target, boolean contains, String id) {
        return (IForgeRegistry<Entry>) Proxy.newProxyInstance(IForgeRegistry.class.getClassLoader(),
                new Class<?>[] {IForgeRegistry.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getRegistryKey": return key(id);
                        case "getRegistryName": return Identifier.parse(id);
                        case "containsKey": return contains && TARGET.equals(args[0]);
                        case "getValue": return target;
                        case "getKey": return args[0] == target ? TARGET : null;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static final class Entry {}
}
