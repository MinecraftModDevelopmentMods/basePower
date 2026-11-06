# Upgrading a World to basePower, baseSteam and baseElectric 1.13.2

The three 1.13.2 builds continue DrCyano's Advantage mods under the base* names.
The migration reads the saved Forge block registry before Minecraft converts
old numeric block states. It accepts the original Advantage 1.10.2 IDs and the
released base* IDs from 1.10.2 and 1.12.2. The old and new jars should not be
installed together.

## Before opening a world

1. Set up a separate instance with Minecraft 1.13.2, Forge 25.0.223 and Java 8.
2. Install matching basePower, baseSteam and baseElectric builds with MMD
   OreSpawn 4.1.0.113021 and Base Metals 3.0.2.113021. The older Base Metals
   3.0.1 download does not contain the
   [leaves conversion fix](https://github.com/MinecraftModDevelopmentMods/BaseMetals/pull/525).
3. Check the rest of the modpack separately. Keep the original world and config
   offline and open a copy. Don't accept a missing-registry warning just to get
   into the world.
4. Inspect a few machines before changing anything. Check their facing,
   inventories, tank levels, charge, temperature, progress and filters. Confirm
   their existing pipes or wiring still supply them. Run a conveyor, a steam
   machine and an electric machine without replacing any blocks.
5. Save, close Minecraft completely, and reopen the same copy. Check those
   machines again. Keep the logs and the world migration report if anything
   looks wrong.

## What the automated tests check

- Each of the three source versions is loaded from a disposable archived copy.
  The checks compare saved values before conversion, after conversion, and
  after a separate server process opens the result. They include inventories,
  fluid quantities, stored power, orientation, filled buckets and the saved
  turbine's identity. Connected machines are then run without rebuilding them.
- A larger released 1.12.2 workshop compares 41 installed machines. This caught
  a flattening gap that discarded sugar cane from custom machine inventories.
  The regression checks preserve the item counts and slots, not just the machine
  blocks or a successful server launch.
- The native machine, client, recipe, world-generation, restart and endurance
  runs are separate checks on the candidate. A visual pass cannot stand in for
  processing output, and automated checks cannot stand in for manual play.

These tests do not qualify unrelated mods, custom resource packs, or every
possible layout. Earlier Advantage test results are retained in the
[historical qualification notes](CURRENT-QUALIFICATION.md), separate from
the current base* candidate.
