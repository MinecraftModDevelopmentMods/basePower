# Minecraft 1.20.6 Port

This branch is a local port of the accepted 1.20.1 sources. It is ready for
manual checks, not a release. Results from older versions do not qualify
this candidate.

The 1.20.6 build uses Forge 50.2.0 and Java 21. The old item data is read into
native item components; block, item and block-entity IDs stay the same.
Base Metals' shared pre-flattening hook is on a separate local branch and must
be present when testing upgrades with Base Metals installed.
The pinned fix runs the legacy player-inventory migration before Minecraft
converts item tags to components, so custom names and enchantments can still
be read in their original form. The published older Base Metals build is not
the dependency used for these upgrade checks.

## Automated checks

These checks used basePower `2.4.13.120061`, baseSteam and baseElectric
`2.2.14.120061`, Forge `50.2.0` and Java 21. The shared profile used
Base Metals `3.1.2.120061` with the local migration fix and OreSpawn
`4.1.0.120061`.

- The 21-station rig passed with and without Base Metals, skipping only
  integration checks for unavailable mods. The connected
  checks covered full restarts, partial chunk unloading, network bridges
  and moving drills, including saved drill stock, energy and orientation.
- Original 1.10.2 Advantage, released 1.10.2 and 1.12.2 base*, and
  1.20.1 base* machine fixtures passed conversion, a full restart and
  operation without rebuilding the machines. All source copies stayed
  unchanged. This does not certify the other mods in a world.
- Old player inventories passed on fixtures with legal stack sizes. The
  original batteries were already limited to one per stack; the deliberately
  overstacked test remains a separate negative case. In the Nether and End
  fixtures Minecraft changes a wolf's health from 8/20 to 16/40. A native
  control without basePower reproduced that exact change.
- Recipe unlock and survival progression passed all three recipe modes
  with and without rubber. The combined Eclipse client checked recipe book
  grouping, assets and captions on all 39 machine screens.
- Tank drops, chest loot and player datapack overrides passed. The oil tests
  covered desert and ocean deposits, legacy OreSpawn profiles, buckets,
  fluid transfer and distillation. Forge Energy converter tests passed.
- The release jars contain no bundled dependencies or test code. The Power
  release, sources and Javadoc jars matched on a clean build from a path
  with spaces and a fresh Gradle cache.
- A fresh world and a migrated 1.20.1 world each ran for 30 minutes,
  then passed a full restart and resumed operation. The separate replay
  matched 33 checkpoints and 528 station snapshots per world, including
  item outputs and oil conservation.

## Manual checks

The follow-up gauge fix is client-side only. Both tanks now pass a
whole-gauge pixel comparison using water, lava, crude oil and refined oil.
The earlier layout check could pass a corrupted fill because it did not
compare those pixels. The full migration and endurance receipts describe
the earlier jar; the gauge fix has its own native client and build checks.

Refresh the Gradle project in Eclipse, clean the project and start
`runClientAllAdvantage.launch` with Java 21. Start with a new world or a copy
of an existing world. Keep the original closed and backed up.

- Check the shared creative tab, then collect raw ingredients and check that
  recipes unlock in the right mode.
- Open a metal tank with water in it, check the caption and gauge, and compare
  the reading after closing Minecraft completely and reopening the world.
- Check the installed hydroturbine's orientation and spin in a running world.
  The client resource probe checks its registered renderer and texture;
  server tests prove that the turbine operates, not what the animation looks
  like on screen.
- A metal tank still drops empty. A portable tank keeps its fluid when broken
  and placed again. Both keep stored fluid across a normal save and restart.
