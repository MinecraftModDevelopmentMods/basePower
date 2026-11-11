package zone.moddev.mc.basepower.migration;

import com.mojang.serialization.Dynamic;
import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.MissingMappingsEvent;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native12111RegistryMigrationTest {
    @BeforeClass public static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test public void chainAliasesMatchNativeBlockAndItemDataFixers() {
        Identifier target = id("iron_chain");
        assertEquals(target, VanillaRegistryRemaps.replacement(id("block"), id("chain")));
        assertEquals(target, VanillaRegistryRemaps.replacement(id("item"), id("chain")));
        assertTrue(ForgeRegistries.BLOCKS.containsKey(target));
        assertTrue(ForgeRegistries.ITEMS.containsKey(target));
        CompoundTag block = new CompoundTag();
        block.putString("Name", "minecraft:chain");
        CompoundTag properties = new CompoundTag();
        properties.putString("axis", "x");
        properties.putString("waterlogged", "true");
        block.put("Properties", properties);
        var fixed = DataFixers.getDataFixer().update(References.BLOCK_STATE,
                new Dynamic<>(NbtOps.INSTANCE, block), 3839, 4671);
        assertEquals("minecraft:iron_chain", fixed.get("Name").asString().result().orElseThrow());
        assertEquals(new Dynamic<>(NbtOps.INSTANCE, properties), fixed.get("Properties").result().orElseThrow());
        var stack = DataFixers.getDataFixer().update(References.ITEM_STACK,
                new Dynamic<>(NbtOps.INSTANCE, item("chain", 7)), 3839, 4671);
        assertEquals("minecraft:iron_chain", stack.get("id").asString().result().orElseThrow());
        assertEquals(7, stack.get("count").asInt(-1));
        assertNull(VanillaRegistryRemaps.replacement(id("entity_type"), id("chain")));
        assertNull(VanillaRegistryRemaps.replacement(id("item"), Identifier.parse("other:chain")));
    }

    @Test public void splitEntityIndexesRetireWithoutCollapsingActualBoatOrPotionData() {
        for (String name : new String[] {"boat", "chest_boat", "potion"}) {
            assertAction("entity_type", name, MissingMappingsEvent.Action.IGNORE);
            assertFalse(ForgeRegistries.ENTITY_TYPES.containsKey(id(name)));
        }
        for (String wood : new String[] {"oak", "spruce", "birch", "jungle", "acacia", "cherry", "dark_oak", "mangrove", "bamboo"}) {
            for (String old : new String[] {"boat", "chest_boat"}) {
                CompoundTag entity = witness(old);
                entity.putString("Type", wood);
                String target = wood.equals("bamboo") ? (old.equals("boat") ? "bamboo_raft" : "bamboo_chest_raft")
                        : wood + "_" + old;
                assertFixedEntity(entity, target);
            }
        }
        for (String potion : new String[] {"splash_potion", "lingering_potion"}) {
            CompoundTag entity = witness("potion");
            entity.put("Item", item(potion, 1));
            assertFixedEntity(entity, potion);
        }
        assertAction("entity_type", "cow", MissingMappingsEvent.Action.DEFAULT);
        assertAction("entity_type", "unknown", MissingMappingsEvent.Action.DEFAULT);
        assertAction("item", "boat", MissingMappingsEvent.Action.DEFAULT);
        assertAction("entity_type", "other:boat", MissingMappingsEvent.Action.DEFAULT);
    }

    @Test public void removedNeutralParrotImitationsDoNotBlockHistoricalRegistrySnapshots() {
        for (String mob : new String[] {"enderman", "polar_bear", "wolf", "zombie_pigman"}) {
            String name = "entity.parrot.imitate." + mob;
            assertFalse("No native sound target exists for " + name, ForgeRegistries.SOUND_EVENTS.containsKey(id(name)));
            assertAction("sound_event", name, MissingMappingsEvent.Action.IGNORE);
            assertAction("item", name, MissingMappingsEvent.Action.DEFAULT);
            assertAction("sound_event", "other:" + name, MissingMappingsEvent.Action.DEFAULT);
        }
        assertAction("sound_event", "entity.parrot.imitate.unknown", MissingMappingsEvent.Action.DEFAULT);
        assertAction("sound_event", "entity.parrot.imitate.creeper", MissingMappingsEvent.Action.DEFAULT);
    }

    @Test public void onlyExactRemovedSoundIndexesAreRetired() {
        for (String name : new String[] {"entity.goat.screaming.horn_break", "entity.leash_knot.break",
                "entity.leash_knot.place", "entity.puffer_fish.ambient", "entity.wolf.howl", "item.goat_horn.play"}) {
            assertFalse(ForgeRegistries.SOUND_EVENTS.containsKey(id(name)));
            assertAction("sound_event", name, MissingMappingsEvent.Action.IGNORE);
        }
        assertAction("sound_event", "entity.goat.horn_break", MissingMappingsEvent.Action.DEFAULT);
        assertAction("sound_event", "unknown", MissingMappingsEvent.Action.DEFAULT);
        assertAction("sound_event", "other:entity.wolf.howl", MissingMappingsEvent.Action.DEFAULT);
        assertAction("item", "entity.wolf.howl", MissingMappingsEvent.Action.DEFAULT);
    }

    private static CompoundTag item(String name, int count) {
        CompoundTag stack = new CompoundTag();
        stack.putString("id", id(name).toString());
        stack.putInt("count", count);
        return stack;
    }

    private static CompoundTag witness(String name) {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", id(name).toString());
        entity.putInt("AdvWorksOpaqueWitness", 121111);
        return entity;
    }

    private static void assertFixedEntity(CompoundTag entity, String target) {
        var fixed = DataFixers.getDataFixer().update(References.ENTITY,
                new Dynamic<>(NbtOps.INSTANCE, entity), 3839, 4671);
        assertEquals(id(target).toString(), fixed.get("id").asString().result().orElseThrow());
        assertEquals(121111, fixed.get("AdvWorksOpaqueWitness").asInt(-1));
        assertTrue(ForgeRegistries.ENTITY_TYPES.containsKey(id(target)));
    }

    @SuppressWarnings("unchecked")
    private static void assertAction(String registry, String name, MissingMappingsEvent.Action expected) {
        var key = ResourceKey.<Object>createRegistryKey(id(registry));
        IForgeRegistry<Object> active = (IForgeRegistry<Object>) Proxy.newProxyInstance(
                IForgeRegistry.class.getClassLoader(), new Class<?>[] {IForgeRegistry.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getRegistryKey" -> key;
                        case "getRegistryName" -> id(registry);
                        case "containsKey" -> false;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        var mapping = new MissingMappingsEvent.Mapping<>(active, active,
                name.contains(":") ? Identifier.parse(name) : id(name), 42);
        VanillaRegistryRemaps.remap(new MissingMappingsEvent(key, active, List.of(mapping)), active);
        try {
            var field = mapping.getClass().getDeclaredField("action");
            field.setAccessible(true);
            assertEquals(registry + "/" + name, expected, field.get(mapping));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Identifier id(String name) { return Identifier.fromNamespaceAndPath("minecraft", name); }
}
