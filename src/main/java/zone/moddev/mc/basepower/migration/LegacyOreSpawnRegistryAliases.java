package zone.moddev.mc.basepower.migration;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegisterEvent;

/** Keeps OreSpawn's initial legacy import valid until its profile is canonicalised. */
public final class LegacyOreSpawnRegistryAliases {
    private static final Identifier OLD_QUARTZ = Identifier.parse("minecraft:quartz_ore");
    private static final Identifier QUARTZ = Identifier.parse("minecraft:nether_quartz_ore");

    private LegacyOreSpawnRegistryAliases() {}

    public static void register(RegisterEvent event) {
        if (!event.getRegistryKey().equals(Registries.BLOCK)) return;
        if (!(event.getForgeRegistry() instanceof ForgeRegistry<?> registry)) {
            throw new IllegalStateException("Legacy ore aliases require Forge's block registry");
        }
        install(registry, OLD_QUARTZ, QUARTZ);
    }

    static <T> void install(ForgeRegistry<T> registry, Identifier oldId, Identifier currentId) {
        if (!registry.getKeys().contains(currentId)) {
            throw new IllegalStateException("Missing canonical legacy ore target: " + currentId);
        }
        T target = registry.getRaw(currentId);
        if (target == null || !currentId.equals(registry.getKey(target))) {
            throw new IllegalStateException("Missing canonical legacy ore target: " + currentId);
        }
        var aliases = registry.makeSnapshot().aliases;
        var seen = new java.util.HashSet<Identifier>();
        Identifier resolved = oldId;
        while (!registry.getKeys().contains(resolved) && aliases.containsKey(resolved)) {
            if (!seen.add(resolved)) throw new IllegalStateException("Conflicting legacy ore alias cycle: " + oldId);
            resolved = aliases.get(resolved);
        }
        if (aliases.containsKey(oldId) && !registry.getKeys().contains(resolved)) {
            throw new IllegalStateException("Conflicting dangling legacy ore alias: " + oldId);
        }
        T existing = registry.getRaw(oldId);
        if (existing != null) {
            if (existing != target) throw new IllegalStateException("Conflicting legacy ore alias: " + oldId);
            return;
        }
        // Forge aliases change lookup only, without adding a block, item or numeric ID.
        registry.addAlias(oldId, currentId);
    }
}
