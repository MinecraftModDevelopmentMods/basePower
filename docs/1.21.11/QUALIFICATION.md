# Minecraft 1.21.11 Qualification

The supported Base Metals-installed test set passed automated qualification.
Manual testing was subsequently accepted on 2026-11-11 and publication was
authorized in dependency order. The results below remain tied to their exact
inputs; acceptance is not a claim that publication has completed. The Base
Metals migration-hook patch was local-only during qualification and must be
published before the three base* releases.

## Tested Jars

The tests use Forge 61.1.0 and Temurin 21.0.7+6. These are the player jars,
not loose development classes or jars from a previous candidate.

| Mod | Version | SHA-256 |
| --- | --- | --- |
| basePower | 2.4.15.121111 | `C98D2CF163BE2480D9AFB0023BBD2D92D401AE0D50BEF73191235BF3F83774A7` |
| baseSteam | 2.2.16.121111 | `F23DDC4ABB36A1C4C7A4205151FBB6DDFD25397E86909E1C0B146C4089B747E0` |
| baseElectric | 2.2.16.121111 | `54662C9ACA83D6145D5795B1D9E9C252DB5DA085C1EC8CA1C9895B02F2BA0770` |
| Base Metals | 3.1.2.121111 | `598D3FB0FF956EBCC67FE477A01CE393C75D0013AF0CE00A79E2072B63E9F2BA` |
| OreSpawn | 4.1.0.121111 | `DE39929057288CEA110196C9E9D708E129B80BC360A2E53976337FFA6A84A67B` |

The server harness is `1.1.5.121111`, SHA-256
`CA244B363E4691D3A3E53032A79B09510626F6EB58C933C6602814D9BA9D118F`.
The six-jar profile SHA-256 is
`BB365614A79AF7DDA0F8C35A8B32FD962E9BA59586272A062BEFF477F2D744D6`.
Mineralogy tests add its separately verified `6.1.4.121111` jar. The
Power-only interface test deliberately omits the two expansions. Neither
variant is mistaken for a normal six-jar family run.

## Completed Checks

- All 132 finite machine jobs pass actual operation, a complete server
  restart and independent stopped-save checks. Products and consumed inputs
  are measured; an animation or a stocked supply chest is not a pass.
- The walkable 21-station works, real chunk unloading, partial networks,
  bridge reconnection, extended moving drills, harvest/drop checks, fluid
  interfaces and lifecycle tests pass their automated assertions.
- Five machine-upgrade routes pass conversion, full restart and operation
  without rebuilding: original Advantage 1.10, released base* 1.10 and 1.12,
  and maintained 1.20.6 and 1.21.1 fixtures. Each retains 14 machine witnesses.
- Three player/entity routes pass strict checks. Six Nether/End routes pass
  with one separately proven vanilla difference: wolf health changes from
  8 to 16 and maximum health from 20 to 40. Other fields remain checked.
- Both actual migration-hook orders pass. That diagnostic uses a bounded
  seventh test mod, verifies the woven callbacks and rejects 21 mutations;
  it is not a production launch mode.
- Recipe unlocks and manufacturing progression pass in all three recipe
  modes, including full restart. The graphical client uses the current
  server's real recipe-book and tag packets, not older exports.
- Default generation, legacy OreSpawn import and Mineralogy generation
  pass restart checks. Both crude oils pass six transport lanes and four
  processing circuits, including two cold restarts and exact fluid accounting.
- Actual graphical checks pass for 94 item models, 905 block states,
  87 creative entries, 39 screens and 26 whole-gauge pixel comparisons.
  The screens have 351 required-caption checks and deliberate broken controls.
  Child renderers pass 25 geometry cases; live turbine animation is separate.
- Clean-path builds pass 276 Power, 250 Steam and 112 Electric unit tests,
  with no failures or skips. All nine player, sources and Javadoc jars match
  independent builds. Power also builds using a new Gradle cache.
  Steam's fresh Eclipse compiler output also executes all 250 tests without
  failures, ignored tests or assumptions.
- Four current-source Steam and four Electric native GameTests pass. Those
  supplement the packaged machine tests; they do not replace their evidence.

## Endurance And Saved Data

A fresh world and a disposable original-1.10-upgraded world each complete
30 measured one-minute batches, a full server process restart and a further
minute of useful processing. The same frozen jars run throughout.

Each world produces 480 conveyor items, 480 items through each of three
filter lanes, 120 products from each of five processing lanes, and 30,000 mB
of distilled output during the measured half hour. Inputs and outputs are
reconciled at every batch. The protected source and runtime seed stay unchanged.

The candidate, Java executable and complete imported test/oracle closure
were sealed before setup. Independent replay checks all 66 checkpoints and
1,056 station snapshots. Exact stopped-save comparison passes for 263 rows
across 27 chunks in each world, including ticking buffers and timers. Ticks
are frozen across the final observation, save and shutdown; the earlier
failed timing-gap evidence remains preserved under its original jar hashes.

The seal is `7ADC960AD1A8077ABD0E747C25C7FA5E99E370E0CD55FC133FC85443AA43B990`.
The endurance receipt is
`3EB37683A737148793119C4E185BC73AE9E2A505AB411CA565964D61C0A6ABA2`.
The independent replay receipt is
`D712C553D6498D7F0011A4FBF1D7031071AB583A963EB3D931A81EEAC88C5FEC`.

## Repairs And Evidence Limits

Release preparation also adds a CI upload-path regression. It first rejects
the stale 2.4.14 filenames, then passes with an upload glob following the
audited current version. A second regression catches the wrapper workflow's
old branch filter and rejects missing/wrong event targets, including misleading
comments. The full source suite now has 278 tests; the player jar remains C98D.
This does not relabel the earlier 276-test receipt.

No further production change was needed during this rerun. An isolated build
caught a Steam test that ignored the configured Power jar and used a fixed
sibling path. It now receives the verified build input and rejects missing
paths or changed bytes. Its original failure and both new negative controls
are retained. The Steam player jar remains byte-identical.

A separate oil preflight rejected a noncanonical dependency filename before
starting Minecraft. A fresh profile with the same approved bytes under the
canonical filename passed; the failed preflight was not overwritten.
Independent coverage review verifies 31 completed receipts and 37 staged
runtimes, preserving their narrower scopes and manual/skip results.
Historical whole-region hashes from before the finite-job restart cannot be
rehash-verified against the later region files; their retained raw chunk
payloads and typed state are checked separately, without claiming that missing
whole-region archives exist. The endurance replay above completes the gate
that was still pending when the coverage review was captured. None of this
converts manual checks into passes.

## Manual Checks

Build the Power project in Eclipse, then use `runClientAllAdvantage.launch`.
Java 21, both launch classpaths, all five production jars and 4,593 assets
have been checked. Preparing the launch does not open or modify a world.
Always use a disposable world copy.

The remaining checks include normal play, connected-player menus and
shift-click/control packets, live gauges, actual rotator use while retaining
contents, oil immersion/fire effects, timed musket loading and cancellation,
turret player/team targeting, riding and recovering an elevator, and turbine
orientation/spin in a real world. External-mod fluid-machine faces and the
BaseMinerals-present ownership tier also lack current runtime witnesses.
The rig's `MANUAL_REQUIRED` and `SKIP` results remain explicit. This is not
qualification of every modpack, every entity or every possible layout.

Detailed reports are retained locally under the
`native12111-qualified-metals-20261111` run prefix. Worlds, logs, dependency
jars, reports and agent notes remain outside the committed release content.
