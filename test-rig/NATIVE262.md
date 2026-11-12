# Advantage Works On Minecraft 26.2

This is the local Forge 65 port of the existing rig. Station IDs, expected
machine behaviour, conservation checks and stopped-save comparisons are kept
from the previous version. Base Metals and OreSpawn are required inputs.
There is no supported without-Base-Metals profile.

## Inputs

Use Temurin 25.0.3+9, Forge 65.1.0 and the exact six jars in
`native262-local-candidate-03.json`. The profile SHA-256 is
`A98D4F9B54A0AE47392D9A3CF81335F5E228F718C7D322DF9838A94AD27D9736`.
Its original pre-run `EXPLORATORY_UNQUALIFIED` label remains unchanged; final
qualification is a separate receipt, not an edit to a sealed input.

The local evidence directory is
`D:/PowerAdvantage/_verification/base-262-port-20261112/`. It contains the
frozen jars, code-only server seed, code inventory, command records, negative
controls and original failures. Generated worlds and reports are not tracked.

The player versions are basePower 2.4.17.2602001 and baseSteam/baseElectric
2.2.18.2602001. Base Metals 3.1.2.2602001 and OreSpawn 4.1.1.2602001 are
local fixes, not public releases. Do not replace them with older downloads.

## Build And Run

Build Power, the two expansions, then `test-rig` using Java 25. The rig's
`check build` tasks verify that no harness content enters the player jar.
Clear inherited `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` and
`CLASSPATH` for qualification. Use ordinary frozen copies of the jars, not
mutable files in another build's output directory.

Drivers live in `test-rig/migration`. Each accepts `--help`; use `native262`
drivers only for this target. Common inputs are the sealed profile, code-only
server seed, code inventory with its SHA-256, Java executable, a new output
directory and an unused port. Existing result directories must not be reused.

| Driver | Purpose |
| --- | --- |
| `native262_broad.py` | Walkable bays and drill witnesses |
| `native262_interop.py` | Capabilities, automation and API transfer |
| `native262_operations.py --group all` | Finite measured machine jobs |
| `native262_machines.py` | Harvesting and chunk lifecycle |
| `native262_networks.py` | Partial unloading, bridges and extended drills |
| `native262_resources.py` | Recipe modes and manufacturing progression |
| `native262_oil.py` | World fluids, drains, pipes, tanks and restarts |
| `native262_worldgen.py` | Fresh provider generation |
| `native262_retained_worldgen.py` | Imported generation settings and new chunks |
| `native262_strict_migration.py` | Operating old-world conversions |
| `native262_actors.py` | Player, entity and dimension state |
| `native262_transformer_order.py` | Actual machine migration-hook order |
| `native262_actor_transformer_order.py` | Actual entity migration-hook order |
| `native262_client.py` | Native GUI, oil rendering and recipe-book frames |
| `native262_endurance.py` | Fresh and retained worlds under measured load |

The test-world marker, station bounds and operator checks still apply. Do not
point a driver at a real player's save. Source fixtures must be hash-checked
before and after every disposable migration.

## Endurance Evidence

Create the `native262_endurance_replay.py seal` before starting either world.
It binds the jars, Java, specification, server inventory, retained source,
native platform proof and transitive oracle files. Run for 1,800 real seconds,
then save, shut down completely, restart and check resumed processing.

After both worlds stop, use the replay driver's `freeze` and `replay` commands
against new paths. Qualification requires an intact pre-run seal, all 66
checkpoints, per-interval throughput and conservation checks, and agreement
with the final raw region data. A retrospective seal is explicitly insufficient.
The current independent result is `endurance-independent-replay-03.json`.

## Native Controls And Remaining Scope

Native 26.2 changes tamed wolves' health through vanilla data fixing and loading.
Six dimension fixtures therefore retain `PASS_WITH_NATIVE_DIFFERENCE`, with
their original strict failures and independent no-Advantage control retained.
All other player/entity fields must still match.

Windows' missing Perflib counters also affect this machine's native OSHI
statistics. Worldless native controls reproduce the exact diagnostics.
The server and client classifiers accept only the reviewed whole sequences;
unrelated errors, changed frames, levels or threads still fail. The host has
not been repaired, and no log category is suppressed.

Mineralogy-only oil assertions remain explicit skips when that optional mod is
absent. Geometry assertions do not replace manually checking the turbine's
orientation and spin in a real world. See the family qualification record for
the completed checks and the manual-testing handoff.
