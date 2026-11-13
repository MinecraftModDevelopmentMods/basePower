# Minecraft 26.3 Port

This branch starts from the accepted 26.2 sources. The exact test kit has
passed its automated and manual checks. All three mods and their dependency
patches are published, with public downloads verified against the tested jars. See the
[release status](RELEASE.md) and
[test results](QUALIFICATION.md) for the exact versions, hashes and limits.

The compile baseline is Forge 66.0.0, official 26.3 names, MCP
20260918.230105 and Temurin 25.0.3+9. Forge 66.1.5 has separate bounded
dedicated-server compatibility evidence; it does
not replace the complete 66.0.0 baseline.

## Port Changes

These are the portability decisions assessed during the port. Their original
requirements are retained here; the completed checks are recorded above.

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
- Loot tables, pools and entries also use singular `modifier` and `condition`
  fields. A real break-and-replace run exposed that the old plural fields were
  ignored. Whole-table codec checks now retain every modifier and predicate,
  and compare with an independent fixture derived from the hash-checked 26.2
  release. Codec-only units use a one-to-one mapping to vanilla item references;
  actual mod identities, drop contents and replacement remain server gates.
- Registry-loaded recipes and advancements resolve the actual pending tag
  contents, not the unbound or previous contents of their named holders. A
  required lookup hook carries that reload's native `PendingTags` without a
  global cache. Explicit condition contexts remain authoritative. Tests cover
  initially unbound tags, player-edited empty tags and independent reloads.
- Recipe-book criteria use native recipe tags, each with exactly one optional
  recipe reference. An inactive variant has an empty tag rather than a dangling
  holder. Its ingredient advancement remains loaded, retaining discoveries
  through mode changes as before. Startup and mode-switch tests exposed why
  conditioning the advancement itself was not sufficient.
- The current biome-index allowlist includes dappled forest. Saved biome data
  remains under the native data fixers rather than being replaced by a registry
  object.
- The direct 26.2 upgrade exposed the removed `minecraft:is_tempted` memory
  index. Unlike the older retired transient indexes, this flag was serialized.
  Its exact payload is archived under entity
  `ForgeData.basepower:retired_brain_memories` before the native brain codec
  reads the remaining memories. Only that obsolete index is retired; unknown
  memories, other mods' names and conflicting archives are not silently accepted.

The shared Base Metals migration hook and OreSpawn's retained-settings repair
passed their local dependency checks. They remain separate dependency
changes and must not be published implicitly with this port.

The earlier [26.2 qualification](../26.2/QUALIFICATION.md) is evidence for its
named jars only. The new 26.3 machine, progression, migration, restart,
graphical, network-unloading, moving-drill and measured endurance results
are recorded separately in the current test report.

References:
- [Official Forge downloads](https://files.minecraftforge.net/net/minecraftforge/forge/index_26.3.html)
- [Minecraft 26.3 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)
- Native `SharedConstants`, `NbtUtils`, `BlockStateData` and `StateHolder`
  sources in the pinned Forge 26.3-66.0.0 source archive.
