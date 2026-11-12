# Minecraft 26.3 Port

This branch starts from the accepted 26.2 sources. It is local and unqualified;
no 26.3 implementation or dependency fix has been pushed or published.

The compile baseline is Forge 66.0.0, official 26.3 names, MCP
20260918.230105 and Temurin 25.0.3+9. The current recommended Forge build,
66.1.5, is a separate compatibility target, not an assumed pass.

## Native Changes Under Review

- Native block-state saves now use `id` and `properties`, with compact strings
  also accepted by the state codec. The older flattening table still requires
  `Name` and `Properties`. These are separate contracts, not a global rename.
- The native data version is 5023. Server data format is 121.0, client resource
  format is 97.1 and asset index is 34. Tests check the actual engine metadata.
- The old access-transformer rule for the flattening table duplicates the
  required mutable Mixin. It is removed here; actual migration and both hook
  orders must pass before that simplification is accepted.
- Cooking fuel now uses native cooking components and the machine's loot
  context, while retaining explicit fuel overrides and Forge's burn-time event.
- Villager trades use the singular item-modifier field. Loot functions and
  predicates use their current type discriminator and explicit count providers.
  Native codec tests retain the old exchange rates, chest counts and drops.
- Registry-loaded recipes resolve pending item tags through the current
  registry lookup. Explicit condition contexts remain authoritative.
- Recipe-book criteria use native recipe lists. Each generated unlock retains
  its recipe's conditions, so inactive mode or missing-material variants do not
  leave dangling registry references. A real startup failure and native codec
  regression tests cover the old format.
- The current biome-index allowlist includes dappled forest. Saved biome data
  remains under the native data fixers rather than being replaced by a registry
  object.

The shared Base Metals migration hook and OreSpawn's retained-settings repair
passed their local dependency checks. They remain separate dependency
changes and must not be published implicitly with this port.

The earlier [26.2 qualification](../26.2/QUALIFICATION.md) is evidence for its
named jars only. New machine, progression, migration, restart, graphical,
network-unloading, moving-drill and measured endurance evidence is required.

References:
- [Official Forge downloads](https://files.minecraftforge.net/net/minecraftforge/forge/index_26.3.html)
- [Minecraft 26.3 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)
- Native `SharedConstants`, `NbtUtils`, `BlockStateData` and `StateHolder`
  sources in the pinned Forge 26.3-66.0.0 source archive.
