# Minecraft 26.1.2 Port

The current candidates have completed their
[automated qualification](QUALIFICATION.md), and manual playtesting was accepted
on 12 November 2026. Publication remains a separate step. Earlier findings below retain their
original scope and candidate bindings.

## Target
- basePower `2.4.16.2601021`, Forge `64.1.5` and Java `25.0.3+9`.
- baseSteam and baseElectric `2.2.17.2601021`.
- Base Metals `3.1.2.2601021`, with the local shared migration hook, and
  OreSpawn `4.1.1.2601021`. Exact source commits and hashes are in
  `gradle.properties`. The Base Metals patch is not public yet.

## Checks So Far
The current Power code passes all 357 unit tests under both Gradle and a fresh
Eclipse compiler build. The earlier candidate06 figures below are retained
history, not qualification of the current jars.

The broader checks found two more world-generation migration cases. A new
world can have a pack-supplied OreSpawn profile before it has a `level.dat`;
that bypassed the existing-world hook until the next start. Power now prepares
that profile synchronously before OreSpawn's server-start listener. The early
hook used for existing worlds remains in place. The regression failed before
the repair and passes afterwards. Fresh server runs using the released 1.10
and 1.12 profiles now both pass first start and a full process restart. These
are scoped Power-hook checks, not final combined qualification.

The retained-profile checks also found ten new Base Metals ore rules eligible
alongside their saved legacy equivalents. This is not a harmless warning or a
reason to change the expected results. A local Base Metals repair needs
separate approval. Its failing regression tests are retained; no production
repair has been made yet. Final combined qualification and endurance are on
hold until this is resolved. Nothing here is a manual-testing-ready claim.

Candidate06 passes the full build and release audits with the new Forge API
and exact local dependency pins. All 311 unit tests pass under both Gradle and Eclipse's
compiler. The Eclipse check also rejects an incomplete test inventory: a
filtered run cannot count as the full suite. Another 37 focused resource,
trade and GUI extraction checks pass. Earlier failed runs are retained.

The first combined machine run found an old obfuscated field lookup in
container-lock handling. The fix uses the native lock API for single chests
and checks both halves of double chests. Six new regression tests include
locked, unlocked, wrapped and nested inventories; five failed before the fix.
The subsequent packaged machine and restart checks pass for candidate06.

A fuel-helper regression also showed that consuming the last lava bucket
lost its empty bucket. The helper now captures the native remainder before
emptying the stack. Ordinary fuels, larger stacks, empty and legacy null
inputs, and explicit post-burn rules keep their existing behaviour. The
failing bucket test and its passing rerun are retained separately.

The wider old-world tests also caught lost musket enchantments in dropped
items and chest minecarts. Base Metals could convert them before Power had
loaded the saved enchantment index. The shared hook now supplies an isolated
registry snapshot before conversion, regardless of hook order, and clears
old context when another world is selected. Both new Power tests and both
Base Metals callback tests failed before the repair and pass afterwards.
The rebuilt family passes the live migration and both actual hook-order checks
for candidate06, including stopped entity data and saved enchantments.

An actual client run exposed a separate atlas-registration problem: broad
legacy texture directories also pull in another mod's directly rendered
laser texture. Native 26 rejects its mip allocation before the title screen.
Each mod now registers only its own atlas sprites; candidate06 gets past the
native mip4 upload without lowering the mipmap setting. Rendered-client
qualification remains open. The test client must first enter its copied world
through Minecraft's experimental-world warning, then prove its offscreen
projection with actual GPU pixels before the GUI comparisons can count.

Candidate06 also passes 132 machine jobs, the walkable rigs, fluids and survival
bucket handling, all three recipe modes, network unloading, extended moving
drills, original/released-world machine upgrades, and two 30-minute endurance
worlds followed by full restarts and an independent saved-state replay.
Visual and interactive checks are separate from those server results.

The broader log review caught another real upgrade problem: retained OreSpawn
profiles still name old Advantage output blocks. Finding ore from a new default
does not prove those saved rules work. An output-only migration is being added
before OreSpawn loads global and selected-world profiles. It keeps rule IDs,
rates, heights, selectors and enabled choices, with backups and migration
reports. Its new regression checks reject lost settings, invalid outputs and
duplicate defaults. This requires a new frozen candidate and full rerun; the
earlier passing results are not being relabelled for the new jars.

Vanilla itself changes wolf health during some older-world upgrades. The raw
comparison retains that difference, with a separate native-only control; the
other actor fields, ownership and UUIDs remain strict checks. Optional
Mineralogy-only lanes are marked skipped, not passed without that mod.

The port keeps registry and NBT identities. It replaces the removed villager
trade event with native trade data and uses the current GUI and fluid-model
APIs. Saved world and player paths change in Minecraft 26, so the migration
suite must prove the converted files and machine state after a full restart.

## Remaining Gates
- Freeze and qualify the repaired OreSpawn-profile candidate.
- Repeat the complete release and Eclipse compiler checks, reproducibility,
  fresh-cache and path-with-spaces builds against that candidate.
- Connected-machine operation, recipe progression, fluids, networks, moving drills,
  chunk unloading and restart checks against the same candidate jars.
- Original Advantage and released base* world, player and entity upgrades,
  additional dimensions, later chunks and both actual migration-hook orders.
- Rendered models, all machine GUIs, captions, gauges, recipe discovery and login.
- Two pre-sealed 30-minute endurance worlds, cold restart and independent exact
  saved-state replay.
- Manual testing, followed by separately authorised publication.

Earlier [1.21.11 qualification](../1.21.11/QUALIFICATION.md) is retained unchanged
as historical evidence. It does not qualify this build.
