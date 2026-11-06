# Upgrading To basePower 1.14.4

The three base* mods continue DrCyano's Advantage mods under new project names.
Install basePower and the expansions your world used. Do not leave an old
Advantage jar alongside its replacement. Back up the world and config first,
then open a copy with Forge 28.2.26, Java 8, and the matching 1.14.4 dependencies.

The upgrader reads the old registry snapshot before Minecraft flattens chunks.
It maps old block states, items, tiles, fluids and entities to their matching
base* identities. This preserves the machine's saved data rather than replacing
the machine with a new one. Worlds already using base* keep those identities.

## What The Tests Check

- Original Advantage 1.10.2 and released base* 1.10.2 and 1.12.2 copies retained
  machine state on import and after a full restart. The imported machines then
  ran without rebuilding them. A chained copy from the patched 1.13.2 candidate
  also matched its saved state after conversion and restart.
- The measured machine suite checked 132 operations and 132 cold-restart
  comparisons. The 21-station runs covered both the Base Metals and decoupled
  profiles. Chunk tests checked actual unload and reload events, drill movement,
  partial networks and interruptions in a pipe-only bridge.
- Fresh and migrated-copy endurance worlds ran 30 one-minute production batches
  and resumed processing after a full restart. The rig was built on the
  migrated copy for this test; this does not mean every original machine ran
  for 30 minutes.
- The combined client checked one shared tab, 39 machine screens and native
  recipe-book grouping. Separate survival checks covered all three recipe modes,
  harvesting, oil transfer and Forge energy/fluid interoperability.
- A retained 1.13 player/entity fixture passed the server-side state, live-tick
  and restart checks. This did not exercise logging in with a real client.
- The OreSpawn checks compare generated ores and oils, user overrides and
  imported legacy settings across restart. The normal-world sampler splits
  work across ticks so the full Mineralogy sample can run with the watchdog on.

These are fixture tests, not a guarantee for other mods in the same world.

For a manual check, use the combined Eclipse launch, open a disposable world
and look for a single **basePower** creative tab. Try crafting from raw materials,
open the machine screens and check that inventories, fluids, power and direction
survive quitting Minecraft and starting it again.

The [older 1.14 qualification](BROADER-QUALIFICATION.md) is kept for reference.
Its results apply to the pre-rename builds only. Test saves, logs and receipts
for the new port are kept locally, not included in the release jars.
