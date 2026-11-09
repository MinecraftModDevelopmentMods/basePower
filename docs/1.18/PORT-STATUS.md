# Minecraft 1.18.2 Port

This is a local candidate, not a released build. Keep original saves and test
upgrades on copies.

Forge 40 registers fluids, blocks and items during their registry events.
Machines use native block-entity saves and update packets. Existing machine
IDs and NBT keys are unchanged.

Legacy chunk migration restores entities into 1.18's terrain layout while
leaving separate entity files in their native format. The terrain guard
protects the old block heights only, so 1.18 can generate the new underground.
The original numeric registry stays available for chunks that have not yet
been visited after an upgrade.

Local build, unit tests, dependency and archive audits, and the Eclipse
production classpath check pass. Power, Base Metals and OreSpawn also booted
and reopened a disposable fresh world on a packaged Forge server.
Combined machine, recipe, migration and endurance qualification is still in
progress. No manual acceptance or publication is claimed.
