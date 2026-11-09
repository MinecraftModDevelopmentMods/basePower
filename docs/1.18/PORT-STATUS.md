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
The combined client probe checked the machine screen labels and layouts,
oil rendering and recipe-book grouping. The packaged Works tests exercised
all 21 stations with and without Base Metals and checked them again after
restarting. The mining tests exercised the actual survival drop path with
correct and incorrect tools, Silk Touch, and every wire and scaffold
connection mask.

## Upgrading Older Worlds

Use the matching Base Metals 3.1.2 build if Base Metals is installed.
The earlier build rewrote old chunks before basePower could translate
Advantage data. In a saved-world test, a musket kept its ID and enchantment
but lost its wear. The shared migration hook now lets basePower translate
legacy item data and decode machine facings before vanilla flattens the
chunk. Neither mod replaces the other's registry identities.

The legacy fixtures use the original 1.10.2 Advantage releases and the
released 1.10.2 and 1.12.2 base mods. The upgrade checks compare both live
and saved state, then start the same world in a new server process and run
its existing conveyor, steam crusher and electric furnace without replacing
any blocks. Always keep a backup of a real world before upgrading.

## Running the local checks

Use Java 17, an installed Forge 40.3.0 dedicated server, and a separate
inventory sealed by `native118.seal`. The Python runners under
`test-rig/migration` take a pinned profile, the seed directory, the
inventory and its SHA-256, the Java executable, a new output path below
`test-rig/build`, and a free port. Run each script with `--help` for its
options:

- `native118_broad.py` exercises both packaged Works profiles.
- `native118_recipes.py` checks unlocks, the real recipe book and survival progression.
- `native118_machines.py` runs moving drills, mining and oil transport.
- `native118_networks.py` checks partial networks, unloaded pipe bridges and Forge capabilities.
- `native118_legacy_world.py` checks live and serialized state against a protected historical fixture, then runs its existing machines.
- `native118_modern_upgrade.py` checks the same witnesses when stepping from 1.17.1 to 1.18.2.
- `native118_actors.py` checks player inventories, nested items, ownership and entity saves using protected fixtures in the Overworld, Nether and End.
- `native118_respawn.py` checks inventory retention and drops, forced spawns, beds and missing beds through the native server handler, including after restart.
- `native118_wolf_control.py` measures native wolf loading in a fresh Forge world with no mods, separate from the strict upgrade checks.
- `native118_endurance.py` measures repeated inputs and outputs over 30 minutes and checks resumed operation after a full restart. A shorter smoke run is not endurance evidence.
- `audit_native118_endurance.py` replays the saved station checkpoints without reopening the worlds.

No manual acceptance or publication is claimed.
