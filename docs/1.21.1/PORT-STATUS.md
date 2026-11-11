# Minecraft 1.21.1 Port

Status: local qualification in progress. This is not a release approval.

The port starts from the published 1.20.6 basePower source, commit
`d1cdc73d19551786ab4524879cb9abca8c881251`. It uses Forge 52.1.0,
ForgeGradle 7.0.34 and Java 21. The matching baseSteam and baseElectric
ports are being qualified together.

## Dependencies

- MMD OreSpawn `4.1.0.121011`, published file 9189594.
- Base Metals `3.1.2.121011`, local migration-hook commit
  `b42b92b0e6f60ac5baf07a6b74042b2fa8427904`.

The Base Metals patch is not published. Do not substitute the older public
jar for a world-upgrade test. All source worlds and earlier qualification
receipts are kept unchanged; each run uses a disposable copy.

## Port Changes

Recipes, advancement files, loot tables and item tags use Minecraft's new
singular folder names. Their registry and logical advancement names remain
unchanged. Native recipe inputs, block-entity validation and rendering APIs
replace their previous equivalents.

Minecraft 1.21 also changes entity attributes and makes enchantments
data-driven. A regression first demonstrated loss of a modded entity's swim
speed, then verified the repair through the native entity reader. Saved tool
checks now verify the loaded enchantment level, not just the stored JSON/NBT.

The 1.20.6 upgrade check exposed old Forge painting indexes and two renamed
trial-spawner sounds. The port retires only the 30 vanilla painting indexes
whose names still exist in the native data-pack registry, and maps those two
sounds to their current names. The obsolete static enchantment index is
archived in `basepower-retired-registry-indexes.dat`, without changing any
item enchantments or the pre-flattening numeric-ID archive. The original
`level.dat` is backed up before changes. Unknown painting entries are not
silently discarded.

## Validation Gates

- Power unit, dependency, identity, resource, archive and Eclipse audits: passed.
- Packaged Power fluid, shared-crushing and full-restart checks: passed.
- Combined finite machine jobs: 132 cases passed, followed by typed saved-NBT
  inspection and a full process restart. Both stopped saves are checked.
- Recipe discovery and survival progression: passed in all three recipe modes,
  with and without rubber, including reload and full restart.
- Direct original Advantage 1.10, released base* 1.10 and 1.12 upgrades: passed.
  A retained 1.20.6 fixture also passed conversion, restart and connected operation.
- Historical player and entity inventories: passed. Nether and End fixtures
  retain a separately recorded native wolf difference, described below.
- Partial networks, extended moving drills and endurance: final checks running.
- Combined client: 94 items, 905 block states, 87 creative entries and recipe-book
  grouping passed. All 26 tank-gauge images matched independent sprite renders.
  All 351 required-caption cases passed; 355 broken controls were rejected.
- Independent cache, path-with-spaces and reproducibility checks: passed for
  all three mods, with matching player, sources and Javadoc jars.
- Manual gameplay and visual acceptance: pending.

## Native Entity Difference

Minecraft's own 1.21.1 data fixer changes the old tamed-wolf fixture from
health 8 and maximum 20 to health 16 and maximum 40. Independent controls
with no Advantage classes reproduce that change for both old data versions.
The raw strict comparison remains failed in the report. A separate
`PASS_WITH_NATIVE_DIFFERENCE` assessment accepts only those two values,
with the same owner and UUID; all other fields must still match. Inventory
loss, another changed value or a changed control checksum fails the check.

## Manual Testing

Open the existing `D:\PowerAdvantage\PA1211` Eclipse workspace, refresh the
Gradle projects and use Power's `runClientAllAdvantage.launch`. It loads all
three mods with the pinned dependencies. The individual launches remain
available for isolated checks. Use Java 21 to run Minecraft.

Check the creative tab, recipe discovery during normal play, machine screens
and their tooltips, live turbine/drill animation, and your usual connected
setups. A real player login and multiplayer menu interaction are still manual
checks. The automated client uses isolated, hashed development output;
packaged server tests use the exact frozen player jars. Neither is a claim
that every third-party modpack or resource pack is compatible.

The Metal Fluid Tank still drops empty, as in the accepted earlier ports.
The Portable Fluid Tank is the one that keeps its fluid when picked up in
Survival. Do not use an original world for a manual upgrade test.

Exact input hashes, failed runs and subsequent results are retained locally
under `_verification/base-1211-port-20261111` and the rig's ignored `build`
directory. These checks qualify only their recorded inputs, not an arbitrary
modpack. Local commits are allowed for dependency pins; pushing, merging and
publication wait for manual acceptance.
