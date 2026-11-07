# basePower, baseSteam and baseElectric for Minecraft 1.16.5

Use basePower `2.4.8.116051`, baseSteam `2.2.9.116051` and baseElectric
`2.2.9.116051` together. The machines and parts share the **basePower**
creative tab. Use Forge `36.2.34`, Base Metals `3.1.1.116051` and
OreSpawn `4.1.0.116051`. The results below are from the release candidates.

## Upgrading a world

Back up the whole world and its configuration, and try the upgrade on a copy.
Replace the previous Advantage or base* jars instead of installing both
families together. Check that your other mods have compatible 1.16.5 builds.

The migration checks loaded disposable worlds from original 1.10.2 Advantage,
released 1.10.2 and 1.12.2 base*, and the accepted 1.15.2 base* build. They
compared saved machine state after conversion and after stopping and restarting
the server. The direct-upgrade fixtures also ran their original conveyor,
steam crusher and electric furnace without rebuilding them. The tests cover
these fixtures, not every possible modpack or the original Sylvester world.

## What the automated checks measure

- The machine campaign runs 132 jobs with finite inputs, including blocked and
  unpowered controls. A second process checks the saved machines without
  rebuilding them.
- Both packaged profiles run all 21 Works stations, with and without Base
  Metals. The independent audit reads the raw station records and verifies the
  expected skips and drill movement.
- The fresh and migrated-copy workshops each ran 30 measured one-minute
  batches. Both retained their inventories and fluid totals through a full
  server restart and processed another batch afterward.
- The drill and network tests measure actual chunk load and unload events.
  Removing a ticket without unloading its chunk is not treated as a pass.
- Two archived player fixtures retained all stored book enchantments, including
  Steam enchantments under their new names, after conversion and restart.
- Recipe discovery and manufacturing are checked in all three recipe modes.
  The client probe also checks the recipe-book layout, models, creative tab,
  and 351 required captions. It rejects deliberately hidden or misplaced text.
- Oil tests check fluid identity and exact amounts through drains, pipes and
  tanks, including Mineralogy oil. Worldgen tests check the fallback rules,
  Mineralogy compatibility, and import of old OreSpawn configuration.

## Manual walkthrough

In Eclipse, use a separate workspace from 1.15.2 and import the three
existing Gradle projects. Refresh the Gradle projects, run **Project > Clean**,
then launch `runClientAllAdvantage.launch` from PowerAdvantage. The normal
`runClient.launch` is still Power-only. To regenerate the combined launch
after rebuilding the three development jars, run
`gradlew.bat prepareCombinedEclipseLaunch` in PowerAdvantage.

Start on a disposable world. Check the shared tab, discover recipes from
ingredients, and open representative tanks, boilers and electrical machines.
Check live turbine and drill rendering, turret effects and sound, then close
Minecraft completely and reopen the same save. The automated probes don't
replace these player-facing checks.

The automated tests do not cover a real multiplayer login, a full Sylvester
modpack upgrade, or every custom-dimension setup. Earlier Advantage-named
test reports remain separate.
