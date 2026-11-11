# 1.21.1 Local Test Results

The coordinated port passed automated qualification and manual acceptance.
These results were recorded on 11 November 2026 using Forge 52.1.0 and Java 21.
Release checks still verify the merged artifact and public downloads separately.

## Frozen Candidates

| Mod | Version | Player jar SHA-256 |
| --- | --- | --- |
| basePower | 2.4.14.121011 | `F91A613EB5FAA218AAD2DC3C881DEC7B897A5EE874215282A439E34B915C1AE6` |
| baseSteam | 2.2.15.121011 | `EA305B9060BC2A84021C7299249AA79AA593EA8A37665C18D0B3483902F1046A` |
| baseElectric | 2.2.15.121011 | `CFE7EF9CDAB3BD4DB0342562B2D9CEBAA19E78119C4D4AAD4B637B5BF0A59560` |

The dependencies are OreSpawn 4.1.0.121011 and Base Metals
3.1.2.121011, which includes the shared migration hook.
The complete input hashes, source commits and receipt hashes are in
`D:\PowerAdvantage\_verification\base-1211-port-20261111\final-qualification.json`.

## What Passed

- Full release, dependency, identity, resource, bytecode, archive and Eclipse
  checks for all three mods. No dependency or harness classes enter player jars.
- Clean-cache and path-with-spaces builds reproduced each mod's player,
  sources and Javadoc jars byte-for-byte.
- 226 Power, 228 Steam, 106 Electric and 329 harness JUnit tests, with no
  failures, errors or skips. The Python suite passed 2,247 tests. Separate
  Eclipse ECJ compilation, expansion resource tests and GameTests passed.
- All 21 Works bays in shared and decoupled runtimes. The finite-operation
  suite passed 132 machine jobs, raw saved-NBT checks and cold-restart checks.
- Recipe discovery and progression in NORMAL, TECH_PROGRESSION and
  APOCALYPTIC modes, with and without rubber, before and after restart.
- Direct upgrades from original Advantage 1.10 and released base* 1.10 and
  1.12 fixtures. Each passed conversion, full restart and connected operation
  without rebuilding the machines. A retained 1.20.6 fixture passed too.
- Historical player inventories and nested items, entity inventories and
  stopped entity storage. Nether and End fixtures passed with the separately
  recorded native wolf difference described in the port record.
- Partial-network and bridge unloads, machine-only and supply-only unloads,
  extended drill travel, harvest and the public fluid API.
- Moving-machine lifecycle in all six facings: tagged items, rear output,
  five chunk cycles, progress restoration and full restart.
- Both Advantage oils and Mineralogy crude oil through bucket placement,
  drains, pipes and both tanks, including full-process restarts. Distillation,
  generation ownership, legacy OreSpawn import and converter profiles passed.
- Combined client resources: 94 items, 905 block states, 87 creative entries
  and recipe-book grouping. All 26 whole-gauge images matched independent
  native sprite renders. All 351 required-caption cases passed, and all 355
  deliberately broken controls were rejected.

## Endurance

Both a fresh world and a copy of the original-1.10 migrated fixture ran for
30 measured minutes. They then stopped completely, restarted and completed
another minute of processing. No source fixture or server seed changed.

An independent replay verified 33 checkpoints and 528 station snapshots in
each world. Sampled rates were 19.82 and 19.81 game ticks per second, above
the required 18. Each world produced 30,000 mB of distilled output, 480 items
in each conveyor/filter lane and 120 products in each measured processing
lane, with the recorded inputs and outputs reconciled.

The station cards still say `MANUAL_REQUIRED` for their human checks. That
does not mean those checks were performed automatically. The separate item,
fluid, energy, structural and restart assertions are what passed.

## Repairs And Failed Evidence

An Eclipse follow-up found that Power's default Base Metals location still
pointed at the previous version's checkout. Earlier qualification commands
supplied the correct jar explicitly, so they did not test that local default.
The default now points at the 1.21.1 migration-hook build, and ordinary
`compileJava` checks the dependency hashes before compiling. Local IDE setup
must also be checked without dependency-path overrides; an override-only pass
does not qualify the defaults. The follow-up passed the default build and
Eclipse generation without dependency overrides, both Eclipse compiler scopes,
and the combined client using Eclipse output and its bundled Java 21.0.12.
A wrong dependency was rejected before compilation. The player jar remains
byte-for-byte identical to the qualified candidate, so this build-only repair
does not replace or invalidate its machine and endurance receipts.

The port fixes the loss of old modded-entity attributes and handles the
vanilla registry changes encountered when opening 1.20.6 worlds. The old
enchantment registry index is archived rather than silently discarded.

Failed and held runs remain intact. Driver repairs included the native chunk
ticket radius, inventory-component commands and representation accessors,
LED light observation, and a tick-freeze barrier only while priming endurance
inputs. Normal processing is measured with ticks running. The mining and
conservation expectations were not reduced to make those runs pass.

## Manual And Modpack Checks

Use [the combined Eclipse launch](../../runClientAllAdvantage.launch) in the
PA1211 workspace, or the exact jars in the local `manual-test-jars` folder.
Check ordinary play, tooltips, live animations and real player/menu interaction.
The hydroturbine renderer still needs in-world visual inspection; its geometry
probe explicitly reports that gap rather than claiming a pixel pass.

The automated client uses hashed development classes and resources. Packaged
server tests use the frozen player jars. Neither qualifies third-party resource
packs or an entire modpack. No full Sylvester-world qualification was performed
in this pass. Only disposable fixture copies were loaded.

Manual acceptance is complete. Dependency publication, hosted CI, tags and
release uploads are checked separately; acceptance does not itself establish
public availability.
