# Minecraft 1.20.6 Port

This branch is a local port of the accepted 1.20.1 sources. It is not a release.
Results from older versions do not qualify this candidate.

The 1.20.6 build uses Forge 50.2.0 and Java 21. The old item data is read into
native item components; block, item and block-entity IDs stay the same.
Base Metals' shared pre-flattening hook is on a separate local branch and must
be present when testing upgrades with Base Metals installed.

Before handing back for manual testing, verify the three exact jars together:

- Startup on the packaged dedicated server and the combined client.
- Recipe modes, survival progression, harvest and loot, including both tank drops.
- Connected machines, chunk reload, full restarts and the timed endurance runs.
- Original Advantage and released base* worlds, using copies, with inventory,
  machine state, item components and nearby entity state checked after migration.
- The Eclipse production classpath and release artifact contents.
