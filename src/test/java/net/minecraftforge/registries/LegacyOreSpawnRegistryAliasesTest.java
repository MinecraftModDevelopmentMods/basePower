package net.minecraftforge.registries;

import static org.junit.Assert.*;

import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.Test;
import zone.moddev.mc.basepower.migration.LegacyOreSpawnRegistryAliases;

/** Package access creates isolated native registries without reflection or global ACTIVE changes. */
public class LegacyOreSpawnRegistryAliasesTest {
    private static final Identifier OLD = Identifier.parse("minecraft:quartz_ore");
    private static final Identifier CURRENT = Identifier.parse("minecraft:nether_quartz_ore");
    private static final Identifier OTHER = Identifier.parse("test:other_block");

    private static ForgeRegistry<Object> registry() {
        Identifier name = Identifier.parse("test:legacy_ore_aliases");
        return new RegistryManager("LEGACY_ORE_ALIAS_TEST").createRegistry(name,
                new RegistryBuilder<Object>().setName(name));
    }

    private static void install(ForgeRegistry<Object> registry) {
        LegacyOreSpawnRegistryAliases.register(new RegisterEvent(Registries.BLOCK, registry, null));
    }

    private static void assertSnapshotUnchanged(ForgeRegistry<Object> registry, CompoundTag before) {
        assertEquals(before, registry.makeSnapshot().write());
    }

    @Test public void oldLookupKeepsCanonicalNameNumericIdAndEntryCount() {
        ForgeRegistry<Object> registry = registry();
        Object quartz = new Object(), other = new Object();
        registry.register(17, CURRENT, quartz);
        registry.register(29, OTHER, other);
        Set<Identifier> keys = Set.copyOf(registry.getKeys());
        var ids = registry.makeSnapshot().ids;
        int count = registry.getValues().size();
        assertNull(registry.getRaw(OLD));
        install(registry);
        assertSame(quartz, registry.getValue(OLD));
        assertSame(quartz, registry.getRaw(OLD));
        assertEquals(CURRENT, registry.getKey(registry.getValue(OLD)));
        assertEquals(17, registry.getID(quartz));
        assertEquals(17, registry.getID(CURRENT));
        assertEquals(-1, registry.getID(OLD));
        assertEquals(keys, registry.getKeys());
        assertFalse(registry.getKeys().contains(OLD));
        assertEquals(count, registry.getValues().size());
        assertEquals(ids, registry.makeSnapshot().ids);
        assertEquals(CURRENT, registry.makeSnapshot().aliases.get(OLD));
    }

    @Test public void repeatedInstallIsIdempotentEvenAfterRegistryFreeze() {
        ForgeRegistry<Object> registry = registry();
        registry.register(17, CURRENT, new Object());
        install(registry);
        CompoundTag first = registry.makeSnapshot().write();
        install(registry);
        assertSnapshotUnchanged(registry, first);
        registry.freeze();
        install(registry);
        assertSnapshotUnchanged(registry, first);
    }

    @Test public void nativeSnapshotRoundTripPersistsAliasWithoutAddingAnId() {
        ForgeRegistry<Object> registry = registry();
        Object quartz = new Object();
        registry.register(17, CURRENT, quartz);
        install(registry);
        var snapshot = registry.makeSnapshot();
        var decoded = ForgeRegistry.Snapshot.read(snapshot.write());
        assertEquals(snapshot.ids, decoded.ids);
        assertEquals(snapshot.aliases, decoded.aliases);
        assertEquals(snapshot.overrides, decoded.overrides);
        assertEquals(snapshot.blocked, decoded.blocked);
        assertFalse(decoded.ids.containsKey(OLD));
        ForgeRegistry<Object> restored = registry();
        for (var entry : decoded.ids.object2IntEntrySet()) restored.register(entry.getIntValue(), entry.getKey(), quartz);
        decoded.aliases.forEach(restored::addAlias);
        assertSame(quartz, restored.getValue(OLD));
        assertEquals(CURRENT, restored.getKey(restored.getValue(OLD)));
        assertEquals(17, restored.getID(quartz));
        assertEquals(1, restored.getValues().size());
    }

    @Test public void conflictingCanonicalEntryIsRefusedWithoutChangingRegistry() {
        ForgeRegistry<Object> registry = registry();
        registry.register(17, CURRENT, new Object());
        registry.register(29, OLD, new Object());
        assertRefused(registry, "Conflicting");
    }

    @Test public void conflictingExistingAliasIsRefusedWithoutChangingRegistry() {
        ForgeRegistry<Object> registry = registry();
        registry.register(17, CURRENT, new Object());
        registry.register(29, OTHER, new Object());
        registry.addAlias(OLD, OTHER);
        assertRefused(registry, "Conflicting");
    }

    @Test public void danglingExistingAliasIsNotSilentlyOverwritten() {
        ForgeRegistry<Object> registry = registry();
        registry.register(17, CURRENT, new Object());
        registry.addAlias(OLD, OTHER);
        assertNull(registry.getRaw(OLD));
        assertRefused(registry, "Conflicting");
    }

    @Test(timeout = 5000) public void cyclicOldAliasesRefuseWithoutLookupHangOrMutation() {
        for (boolean longerCycle : new boolean[] {false, true}) {
            ForgeRegistry<Object> registry = registry();
            registry.register(17, CURRENT, new Object());
            registry.addAlias(OLD, OTHER);
            if (longerCycle) {
                Identifier third = Identifier.parse("test:third");
                registry.addAlias(OTHER, third);
                registry.addAlias(third, OLD);
                assertEquals(OLD, registry.makeSnapshot().aliases.get(third));
            } else registry.addAlias(OTHER, OLD);
            assertEquals(OTHER, registry.makeSnapshot().aliases.get(OLD));
            assertRefused(registry, "cycle");
        }
    }

    @Test public void validAliasChainIsAcceptedWithoutRewritingOrAllocatingEntries() {
        ForgeRegistry<Object> registry = registry();
        Object quartz = new Object();
        registry.register(17, CURRENT, quartz);
        registry.addAlias(OLD, OTHER);
        registry.addAlias(OTHER, CURRENT);
        CompoundTag before = registry.makeSnapshot().write();
        install(registry);
        registry.freeze();
        install(registry);
        assertSnapshotUnchanged(registry, before);
        assertSame(quartz, registry.getValue(OLD));
        assertEquals(CURRENT, registry.getKey(registry.getValue(OLD)));
        assertEquals(17, registry.getID(quartz));
        assertEquals(Set.of(CURRENT), registry.getKeys());
        assertEquals(1, registry.getValues().size());
    }

    @Test(timeout = 5000) public void missingOrNoncanonicalTargetIsRefusedWithoutAddingAlias() {
        ForgeRegistry<Object> empty = registry();
        assertRefused(empty, "Missing canonical");
        ForgeRegistry<Object> aliasTarget = registry();
        aliasTarget.register(29, OTHER, new Object());
        aliasTarget.addAlias(CURRENT, OTHER);
        assertRefused(aliasTarget, "Missing canonical");
        ForgeRegistry<Object> cyclicTarget = registry();
        cyclicTarget.addAlias(CURRENT, OTHER);
        cyclicTarget.addAlias(OTHER, CURRENT);
        assertRefused(cyclicTarget, "Missing canonical");
    }

    @Test public void wrongRegistryEventIsIgnoredAndFrozenFreshInstallRefuses() {
        ForgeRegistry<Object> registry = registry();
        registry.register(17, CURRENT, new Object());
        CompoundTag before = registry.makeSnapshot().write();
        LegacyOreSpawnRegistryAliases.register(new RegisterEvent(Registries.ITEM, registry, null));
        assertSnapshotUnchanged(registry, before);
        registry.freeze();
        assertRefused(registry, "too late");
    }

    private static void assertRefused(ForgeRegistry<Object> registry, String message) {
        CompoundTag before = registry.makeSnapshot().write();
        try {
            install(registry);
            fail("Expected registry alias refusal: " + message);
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
        assertSnapshotUnchanged(registry, before);
    }
}
