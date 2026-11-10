# Minecraft 1.20.1 port

This local port starts from the 1.19.4 code. The candidates are basePower
`2.4.12.120011`, baseSteam and baseElectric `2.2.13.120011`, on Forge
`47.4.10` with Java 17. See the [Works rig](../../test-rig/NATIVE1201.md) for
reproduction steps and exact jar pins. No 1.20.1 release is implied.

The port uses the current block properties, creative-tab registry and screen
drawing APIs. Saved registry names and machine data stay the same. The shared
Base Metals migration hook is still needed when upgrading legacy saves with
both mods installed. Use the pinned local Base Metals build for those tests.
The port also maps the old vanilla pottery and sound IDs in Forge's 1.19.4
registry snapshot. Minecraft itself converts the saved pottery stacks.

## What the tests check

The broad Works sweep has 21 stations in each of two profiles, with and without
Base Metals. It checks machine output and saved state, including drill movement
and state after a full server restart. Its independent audit reads the raw
station results and fails if a profile or required checkpoint is missing.

The migration checks use copies of original 1.10.2 Advantage saves, released
1.10.2 and 1.12.2 base* saves, and a 1.19.4 save. They compare saved state and
then operate the existing machines without replacing them. Keep the original
save and all its other mods safe before trying an upgrade yourself.

The combined client check renders all 39 machine screens and checks both the
machine name and inventory caption. Deliberately missing and overlapping
labels must fail. It also checks the creative tab, models, textures and
recipe-book grouping with data from the server.

The tested jars passed a 30-minute run in both a fresh world and a migrated
1.10.2 world, including a full restart. The optional-power fixture also kept
4,000 mB in the fluid tank across a restart without Base Metals installed.
The Nether and End checks still report one vanilla difference: a tamed wolf
loaded at 20 health instead of 8. Forge reproduced that without any mods.

Manual checks should use the combined Eclipse launch or exact packaged
candidates: inspect models, recipe discovery and menus, then open a
disposable copy of an existing world. Pay particular attention to turbine
orientation and movement. The automated client check does not open a world
or exercise a real player's death screen.
