package zone.moddev.mc.basepower.migration;

import com.mojang.serialization.Dynamic;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.CavesAndCliffsRenames;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.biome.Biomes;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.MissingMappingsEvent;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native12111BiomeRegistryMappingTest {
    @BeforeClass public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test public void actualForgeBiomeRegistryCannotResolveTheNativeNetherTarget() {
        assertEquals(Registries.BIOME, ForgeRegistries.BIOMES.getRegistryKey());
        assertFalse("Biomes are native data-registry keys, not bound Forge biome objects",
                ForgeRegistries.BIOMES.containsKey(Biomes.NETHER_WASTES.identifier()));
        var mapping = mappings(ForgeRegistries.BIOMES, List.of(id("nether"))).getFirst();
        assertRetired(mapping, id("nether"), 100);
        assertEquals("minecraft:nether_wastes", fixName("minecraft:nether"));
    }

    @Test public void everyNativeBiomeKeyRetiresOnlyItsObsoleteForgeIndex() throws Exception {
        Set<Identifier> current = nativeBiomeKeys();
        assertEquals("Review the allowlist when changing the native target", 65, current.size());
        var before = Set.copyOf(ForgeRegistries.BIOMES.getKeys());
        for (Identifier key : current) assertFalse(key.toString(), ForgeRegistries.BIOMES.containsKey(key));
        var mappings = mappings(ForgeRegistries.BIOMES, new ArrayList<>(current));
        int index = 0;
        for (Identifier key : current) assertRetired(mappings.get(index), key, 100 + index++);
        assertEquals("Retiring a snapshot index must not register or replace biome objects",
                before, ForgeRegistries.BIOMES.getKeys());
    }

    @Test public void everyCavesAndCliffsAndDeepWarmAliasAgreesWithNativeDfuWithoutCollapsingRegistryIds() throws Exception {
        Map<String, String> aliases = new LinkedHashMap<>(CavesAndCliffsRenames.RENAMES);
        aliases.put("minecraft:deep_warm_ocean", "minecraft:warm_ocean");
        var mappings = mappings(ForgeRegistries.BIOMES,
                aliases.keySet().stream().map(Identifier::parse).toList());
        Set<Identifier> current = nativeBiomeKeys();
        int index = 0;
        for (var alias : aliases.entrySet()) {
            Identifier target = Identifier.parse(alias.getValue());
            assertTrue("Alias target is a current native key: " + alias, current.contains(target));
            assertFalse("The test must exercise the real unbound Forge registry: " + target,
                    ForgeRegistries.BIOMES.containsKey(target));
            assertEquals(alias.toString(), alias.getValue(), fixName(alias.getKey()));
            assertRetired(mappings.get(index), Identifier.parse(alias.getKey()), 100 + index++);
        }
    }

    @Test public void nativeDfuPreservesCurrentAndUnrecognizedBiomeNamesExactly() throws Exception {
        for (Identifier key : nativeBiomeKeys()) assertEquals(key.toString(), key.toString(), fixName(key.toString()));
        for (String name : List.of("minecraft:unknown_biome", "othermod:nether", "othermod:desert_hills",
                "othermod:plains", "othermod:private_terrain")) {
            assertEquals(name, name, fixName(name));
        }
    }

    @Test public void unknownModdedAndWrongRegistryEntriesRemainUnresolved() {
        List<Identifier> names = List.of(id("unknown_biome"), id("nether_typo"), id("deep_warm_ocean_typo"),
                Identifier.parse("othermod:nether"), Identifier.parse("othermod:desert_hills"),
                Identifier.parse("othermod:plains"));
        for (var mapping : mappings(ForgeRegistries.BIOMES, names)) {
            assertEquals(mapping.getKey().toString(), MissingMappingsEvent.Action.DEFAULT, property(mapping, "action"));
            assertNull(property(mapping, "target"));
        }
        for (var mapping : mappings(ForgeRegistries.ITEMS, List.of(id("nether"), id("desert_hills"), id("plains")))) {
            assertEquals(MissingMappingsEvent.Action.DEFAULT, property(mapping, "action"));
            assertNull(property(mapping, "target"));
        }
    }

    private static Set<Identifier> nativeBiomeKeys() throws Exception {
        Set<Identifier> keys = new LinkedHashSet<>();
        for (var field : Biomes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ResourceKey.class) {
                ResourceKey<?> key = (ResourceKey<?>) field.get(null);
                assertTrue(field.getName(), key.isFor(Registries.BIOME));
                assertTrue(field.getName(), keys.add(key.identifier()));
            }
        }
        return keys;
    }

    private static String fixName(String name) {
        var original = new Dynamic<>(NbtOps.INSTANCE, StringTag.valueOf(name));
        var fixed = DataFixers.getDataFixer().update(References.BIOME, original, 1631,
                SharedConstants.getCurrentVersion().dataVersion().version());
        assertEquals("DFU must not mutate the original saved name", name, original.asString().result().orElseThrow());
        return fixed.asString().result().orElseThrow();
    }

    private static <T> List<MissingMappingsEvent.Mapping<T>> mappings(IForgeRegistry<T> active, List<Identifier> names) {
        List<MissingMappingsEvent.Mapping<T>> mappings = new ArrayList<>();
        for (Identifier name : names) mappings.add(new MissingMappingsEvent.Mapping<>(active, active, name, 100 + mappings.size()));
        var group = BusGroup.create("basepower-native-biome-mapping-test");
        var bus = EventBus.create(group, MissingMappingsEvent.class);
        bus.addListener(Priority.HIGHEST, (MissingMappingsEvent event) -> VanillaRegistryRemaps.remap(event, active));
        group.startup();
        try {
            bus.post(new MissingMappingsEvent(active.getRegistryKey(), active,
                    new ArrayList<MissingMappingsEvent.Mapping<?>>(mappings)));
        } finally {
            group.dispose();
        }
        return mappings;
    }

    private static void assertRetired(MissingMappingsEvent.Mapping<?> mapping, Identifier original, int numericId) {
        assertEquals(original.toString(), MissingMappingsEvent.Action.IGNORE, property(mapping, "action"));
        assertEquals("Saved biome name is never rewritten by the Forge index handler", original, mapping.getKey());
        assertEquals("No target acquires an obsolete numeric ID", numericId, mapping.getId());
        assertNull("Actual biome data remains under native DFU, not an arbitrary Forge object", property(mapping, "target"));
    }

    private static Object property(MissingMappingsEvent.Mapping<?> mapping, String name) {
        try {
            var field = mapping.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(mapping);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath("minecraft", name);
    }
}
