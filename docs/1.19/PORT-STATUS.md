# Minecraft 1.19.4 Port

This records the Minecraft 1.19.4 qualification. Back up a world before
upgrading and use a disposable copy for tests. Earlier versions' results do
not qualify this build.

The port targets Forge 45.4.0, official 1.19.4 mappings and Java 17. It uses
Forge's creative tab and fluid types and keeps existing registry names, tank
formats and recipe modes. When Base Metals is installed, use 3.1.2 for the
shared migration hook. Do not substitute an older build during an upgrade.

The release audit checks package contents, mappings, dependency hashes, Java
bytecode and Eclipse launch classpaths. The separate graphical probe loads
real models and textures and renders the machine screens. A server startup
cannot prove those visuals.

The Works harness uses Minecraft's flat preset and retains the 21 station
definitions. The migration runners check live and serialized machine state,
verify that the source is unchanged and refuse changed input hashes.

See [the 1.19 Works rig](../../test-rig/NATIVE119.md) and
[the client probe](../../test-rig/client-resources/README.md) for commands.
Keep local logs and provisional jars out of release artifacts and commits.

## What was tested

The test set is basePower `2.4.11.119041`, baseSteam `2.2.12.119041`, and
baseElectric `2.2.12.119041`. The Works profiles record the exact test inputs.

- The Works rig ran all 21 stations with and without Base Metals. It also
  checked harvest drops, oil and fluid transfer, partial network unloading,
  extended drill movement, recipe unlocks, and all three OreSpawn profiles.
- Copies of original 1.10, released base* 1.10 and 1.12, and maintained 1.18
  saves kept their machine state and ran again after a full restart.
- Fresh and upgraded worlds each ran for 30 minutes under measured load.
  Both resumed operation after a cold restart. An independent audit replayed
  their saved station ledgers.
- The combined client checked 94 items, 905 block states, 39 menu layouts
  and recipe-book grouping. It caught and retested missing sprites on the
  Steam drill bit and Electric turret items.

The Nether and End fixtures had one historical difference: Forge 45 also
restores the tame wolf's health without any mods installed. Its UUID,
ownership and passenger relationship were preserved. The raw comparison
and the no-mod control are kept separately.

In Eclipse use `runClientAllAdvantage.launch` from the PowerAdvantage
project. Check live animations, normal menu interaction and recipe discovery
on a copy of a world. In-world hydroturbine orientation and spin are still
manual checks.
