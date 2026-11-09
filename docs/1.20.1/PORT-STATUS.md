# Minecraft 1.20.1 port

This local port starts from the 1.19.4 code. The candidates are basePower
`2.4.12.120011`, baseSteam and baseElectric `2.2.13.120011`, on Forge
`47.4.10` with Java 17. See the [Works rig](../../test-rig/NATIVE1201.md) for
reproduction steps and exact jar pins. No 1.20.1 release is implied.

The port uses the current block properties, creative-tab registry and screen
drawing APIs. Saved registry names and machine data stay the same. The shared
Base Metals migration hook is still needed when upgrading legacy saves with
both mods installed. Use the pinned local Base Metals build for those tests.

Manual checks should use the combined Eclipse launch or exact packaged
candidates: inspect models, recipe discovery and menus, then open a
disposable copy of an existing world. Pay particular attention to turbine
orientation and movement. The automated client check does not open a world
or exercise a real player's death screen.
