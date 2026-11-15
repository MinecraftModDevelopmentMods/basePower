# NeoForge 1.21.1 Test Notes

Manual testing of the native port was accepted. The public dependency stack is
being requalified on 15 November 2026 before release: NeoForge 21.1.265, Base
Metals 4.0.1.121012+Neo, MMDLib 2.0.2.121012+Neo and OreSpawn 4.1.1.121012.
Nothing is published merely because a build or an individual check passes.

The figures below record the earlier 13 November candidate and its local Base
Metals dependency. They are historical evidence, not qualification of the new
published stack. The final release checks will replace this summary once the
current run is complete.

## What Changed

The three mods use NeoForge 21.1.247, Java 21 and ModDevGradle 2.0.148. Mod IDs,
registry names, machine data and the historical `cyano.poweradvantage.api`
namespace remain in place. The machines still use the Advantage network code.
They now share MMDLib's `mmdlib:crushing` recipes with Base Metals' crackhammers.
Removing or replacing one of those recipes affects the hammer and both crushers.

The port also repairs issues found during testing:

- Invalidated machine capabilities are rebuilt when chunks return, including
  consumers which previously cached a missing capability.
- Oil buckets resolve to their source fluid, not a flowing variant.
- Muskets use the common blackpowder ammunition tag.
- The chest-parts modifier uses NeoForge's data namespace. This keeps
  Apocalyptic progression possible without overriding a player's chest loot.
- Engine-owned `ForgeData` is translated to `NeoForgeData`, without rewriting
  similarly named fields inside arbitrary item custom data.

## Candidate Jars

| Mod | Version | SHA-256 |
| --- | --- | --- |
| basePower | 2.4.19.121012 | `FEB4F3E1C3671E7E9A75A6114BD5173051A68AE9C6E688FA9B4739AC6C71720C` |
| baseSteam | 2.2.20.121012 | `9DCBD13FA3592DF07950EE86CD7C89CEA600F4D0CF50D7AAB0DCB1CA27D8D9EB` |
| baseElectric | 2.2.20.121012 | `36368F94F6441317156BE8CD799A3B84AE1BC88E35FDAAFE6EDA8B22D37AF31E` |

Use Base Metals `4.0.1.121012-development` with the local migration hook,
MMDLib `2.0.1.121012+Neo` and OreSpawn `4.1.1.121012`. The public Base Metals
4.0.0 jar is not the tested dependency. Neither the Base Metals patch nor these
three ports has been pushed or published as part of this work.

## Qualification

The current candidate passed the operating and restart matrix below. The exact
local receipt index is `final-qualification.json` in the campaign directory.

| Gate | Result |
| --- | --- |
| Power / Steam / Electric JUnit | 236 / 228 / 106 passed |
| Harness JUnit / Python regression tests | 339 / 2,337 passed |
| Base Metals hook / independent native controls | 24 / 3 passed |
| Steam / Electric GameTests | 10 / 21 passed |
| Finite machine jobs and stopped-save restart checks | 132 passed |
| Recipe unlock boots / live progression cases | 5 / 18 passed |
| Harvest and drop checks | 270 passed |
| Fresh and migrated endurance | 30 minutes each, restart and resumed processing passed |
| Independent endurance replay | 33 checkpoints and 528 station snapshots per world passed |
| Real integrated-client tank menu | Two full processes, matching menu IDs and 1,375 mB retained |
| Eclipse ECJ and independent-cache/path-with-spaces builds | Passed; all nine player/sources/Javadoc archive hashes reproduced |

The matrix covers finite machine jobs with stopped-save inspection, full process
restarts, partial network unloads, bridge unloads, moving drills in all six
directions, harvesting, oil transport, portable-tank pickup, sided capabilities,
locked inventories and Forge Energy converters. Recipe tests cover Normal, Tech
Progression and Apocalyptic modes, with and without rubber, plus data reloads and
cold restarts. Shared crushing tests use actual MMDLib hammer interactions and
both naturally ticking crushers, including recipe replacement and removal.

World-upgrade fixtures cover original Cyano 1.10, released base* 1.10 and 1.12,
and a maintained Forge 1.21.1 world. They compare saved state before running the
machines, run the connected equipment without replacing it, then compare again
after a complete restart. Separate player, Nether and End fixtures cover stored
items, wear, enchantments, attributes, passengers, ownership and entity storage.

Fresh and migrated Works worlds each receive a measured 30-minute run, followed
by a complete restart and another minute of processing. A separate replay checks
every retained station snapshot, item/fluid conservation and measured tick rate.
An animation or a lit power indicator is not a sufficient result.

Generation checks preserve provider choices, user overrides and imported old
OreSpawn files through cold restarts. Default sulfur ownership switches to
Mineralogy when it is present, with the Electric alternative still visible and
disabled. A positive normal-world sample on seed 24680 found 190 Power oil blocks
with the family alone. With Mineralogy it found 204 Power oil blocks, 2,032
Mineralogy oil blocks and 3,415 Mineralogy sulfur blocks, with no Electric sulfur.
Both samples retained their counts after restart and had zero oil-biome
violations. An earlier seed produced no Power oil; those failed samples remain
recorded. No frequency, radius, jar or expected count was changed to obtain the
positive sample.

The graphical checks rendered all 39 machine screens at nine window/scale/fill
combinations. They check 351 required caption cases and reject 355 deliberately
broken cases. Tank gauges are compared against independent fluid-sprite renders.
The client also checks all 94 items, 905 block states, 87 creative entries and
three recipe-book packet sets. Additional mutation controls protect those
oracles from weakened thresholds and missing checks.

Compilation is checked with both Gradle and Eclipse's own Java compiler, with
production/test isolation controls. Separate source snapshots in paths with
spaces and independent Gradle caches check reproducible player, sources and
Javadoc jars. These checks do not replace opening Eclipse or playing the game. A
separate native client helper also opened a real menu over the actual
client/server connection, saved normally, exited Minecraft, and reopened the
same copied world in another process without reseeding its tank. That check is
separate from the worldless rendering probe and the packaged server harness.

## Eclipse Refresh Repair

Manual Eclipse testing found duplicate `build/resources/main` entries in Power's
classpath. Electric had the same generator problem. A normal Gradle build and
the earlier ECJ compilation did not reject those duplicates, so the previous
checks were incomplete for repeated IDE refreshes.

The resource-root replacement now removes the previous processed root before
adding it back. All three projects reject duplicate entries and exercise three
successive merge/save/load cycles as part of `check` and the Eclipse audits.
The new check failed on Power's original duplicated classpath before the repair.
Independent Tooling API requests also returned stable, unique source roots on
three successive requests for each project.

After the repair, all 570 unit tests, builds and production/test ECJ compilations
passed. All nine player, sources and Javadoc archive hashes still match the
qualified candidate. Gameplay and endurance checks were not rerun for this
build-only repair. Eclipse's cached error markers may need a Gradle refresh and
project clean before they disappear.

A subsequent Buildship refresh exposed a second issue: the copied-world client
probe was attached to `prepareClientIntegrationRun`, which ModDevGradle also
executes during IDE sync. That correctly failed without an explicit disposable
world, but ordinary project refreshes should never reach that setup. Preparation
now belongs only to the actual test launch. The diagnostic run is not offered
as an IDE launch, and the explicit-world safety guard is unchanged.

The new `verifyEclipseSynchronization` gate reproduced this failure before the
repair and now executes NeoForge's synchronization tasks during `check` and the
Eclipse audits. Separate phased Tooling API checks use Eclipse detection, request
the synchronization tasks before execution, then query the project model after
execution. Three full requests passed for each project. An integration launch
without world-selection properties still fails at its safety guard before
Minecraft starts. The build, unit tests and ECJ checks passed again; the nine
qualified archive hashes remain unchanged.

## Native Entity Behaviour

Minecraft 1.21.1's own data fixer changes the old tamed-wolf fixture from health
8/maximum 20 to health 16/maximum 40. Independent NeoForge controls reproduce
this with no Advantage, Base Metals, MMDLib or OreSpawn classes, no server and no
entity ticks. The original strict comparison remains recorded as failed. A
separate assessment accepts only those exact values with the original UUID and
owner; any other loss or change still fails.

Native non-saving leash-knot helpers are checked through their real holder
lifecycle, not invented saved records. Their independent control is bound to the
actual NeoForge engine-class hashes. Historical Forge controls remain unchanged.

## Manual Checks

Import the three existing Gradle projects under `D:/PowerAdvantage/PA1211NEO`
into a separate Eclipse workspace using Java 21. From Power's Gradle Tasks view,
run `runClientAllAdvantage`. It stages the two expansion jars into
`run-all-advantage/mods` and uses the tested dependency pins. Do not add a second
copy of Power or the test harness to that directory.

Check ordinary recipe discovery, screen tooltips and slots, drill animation,
live turbine orientation/spin and the usual connected builds. A real player
login, multiplayer menu interaction, resource packs and third-party modpacks
remain manual checks. Automated geometry checks explicitly leave live turbine
motion for this stage.

Use disposable copies of worlds. The migration fixtures are not proof that
unported third-party blocks can survive removal of their owning mods. No original
Sylvester save is opened or modified by this campaign. The Metal Fluid Tank
still drops empty; the Portable Fluid Tank retains its fluid in Survival.

Local jars, failed attempts, source manifests and raw reports are kept under
`D:/PowerAdvantage/_verification/base-1211neo-port-20261113`, the ignored rig
`build` directory and the local logs directory. They do not belong in release
jars or Git commits. Publication and manual acceptance are separate steps.
