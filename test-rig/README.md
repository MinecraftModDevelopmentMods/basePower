# Advantage Works Test Rig

## Native 26.1.2: Local And Unqualified

> **Current-instruction boundary:** Only this native26 section describes the
> current port. Every section from **Retained 1.21.1 Reference** onward is
> historical, not current native26 launch or qualification instructions.
> In particular, the Java8/17 commands and decoupled-without-BaseMetals profiles
> below must not be used for native26. **Base Metals is mandatory for native26**;
> there is no supported native26 decoupled qualification profile.

The native26 adapters target Minecraft **26.1.2**, Forge **64.1.5**, MCP
**20260409.101008**, and the hash-pinned **Temurin 25.0.3+9** runtime. They are
additive: do not replace historical `native*` drivers, profiles, source readers,
or expectations with a global version substitution.

**Final qualification profile: PENDING.** Candidate07 is awaiting the retained
OreSpawn profile-migration and client gates. Earlier candidate05/06 machine
and endurance receipts are historical, scoped evidence, not a final family
pass. No filename or hash in this section stands in for the future final
profile. Never retarget an old receipt after rebuilding any input.

### Strict Inputs And Build

The server core accepts only an explicitly materialized JSON profile with
`mode: packaged`, `minecraftVersion: 26.1.2`, `forge.version: 26.1.2-64.1.5`,
and `qualificationStatus: EXPLORATORY_UNQUALIFIED`. A future "final" profile
still needs this supported input status; individual `PASS` receipts do not
authorize publication or imply overall acceptance.

All six ordinary inputs are mandatory. Every `mods` row must provide the exact
`id`, `version`, `file`, `path`, and full SHA-256 of the actual frozen jar:

| Mod ID | Required version |
| --- | --- |
| `basepower` | `2.4.16.2601021` |
| `basesteam` | `2.2.17.2601021` |
| `baseelectric` | `2.2.17.2601021` |
| `basemetals` | `3.1.2.2601021` |
| `orespawn` | `4.1.1.2601021` |
| `advantageworkstest` | `1.1.6.2601021` |

Missing, duplicate, unexpected, wrong-version or changed inputs fail closed.
Relative mod paths resolve from `test-rig/`, not the shell's working directory.
Freeze independent, read-only copies outside compiler outputs and caches; do not
point a qualification profile at jars another build can overwrite.
The harness manifest must pin the exact staged ordinary Power jar through
`Power-Input-SHA256`, with the selected `Built-On` and unqualified status.
Build the harness against that actual Power artifact, not a stale deobf jar
or an invented hash. `test-rig/gradle.properties` owns `works_power_sha256`.

From the repository root, with the selected Java25 installation:

```powershell
$env:JAVA_HOME = '<Temurin-25.0.3+9-home>'
./gradlew.bat -p test-rig check build
if ($LASTEXITCODE -ne 0) { throw 'Harness build/check failed' }
```

Compiler outputs remain in `test-rig/build/gradle/`; `clean` is restricted to
that directory. Do not run concurrent Gradle builds against the same project
cache. A build, worldless unit bootstrap, or preflight is not a real-server
qualification.

Use a separately sealed, fresh **code-only** Forge64 SDK seed: only the selected
shim jar and `libraries/`, with no worlds or user configuration. The core checks
the Java executable, shim, native binary, official launch arguments and full
code inventory. It does not reuse an older-version seed. `native2612.py seal`
creates this SDK inventory; it is **not** the endurance pre-run seal.

Ordinary qualification launches require a free loopback TCP port, disabled empty-server pausing,
and no inherited `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` or
`CLASSPATH` overrides. An occupied port is an error, never permission to kill
another process. Use an explicit Python executable with `nbtlib` and a reviewed
dependency closure. Endurance custody also pins the interpreter, base executable,
imported NumPy code and native libraries. Keep the same Python/PYTHONPATH closure
for seal, producer, freeze and replay; do not bypass alias or hard-link guards.

### Native26 CLI

Run these examples from `test-rig/migration/`. Replace every angle-bracket
placeholder with a verified input or a **new**, disjoint output path. The port
numbers are examples: check ownership and availability immediately before launch.
Stop on any failure and retain its original output.

```powershell
$Python = '<explicit-reviewed-python-executable>'
$Profile = '<PENDING-final-native2612-profile.json>'
$ProfileSha = '<actual-profile-SHA256>'
$Seed = '<sealed-Forge64-code-only-seed>'
$Inventory = '<native2612-code-inventory.json>'
$InventorySha = '<actual-inventory-SHA256>'
$Java = '<Temurin-25.0.3+9-home>/bin/java.exe'
$Inputs = @('--profile', $Profile, '--seed', $Seed,
    '--inventory', $Inventory, '--inventory-sha256', $InventorySha, '--java', $Java)

if ($ProfileSha -notmatch '^[A-Fa-f0-9]{64}$' -or
    (Get-FileHash -LiteralPath $Profile -Algorithm SHA256).Hash -ne $ProfileSha) {
    throw 'Frozen profile hash differs; do not launch'
}
& $Python -B native2612.py verify @Inputs
if ($LASTEXITCODE -ne 0) { throw 'Native input preflight failed' }
& $Python -B native2612_operations.py @Inputs --group all `
    --output '<new-finite132-runtime>' --port 25620
if ($LASTEXITCODE -ne 0) { throw 'Finite operation/restart/raw audit failed' }
```

Preflight opens no world. `--group all` requires all 132 finite cases, an actual
cold restart, both stopped raw-storage audits and unchanged seed custody.
Smaller groups are focused checks, not the full finite-operation gate.

Other native26 entry points have their own required inputs; inspect their
`--help` rather than copying historical runner defaults:

| Entry point under `migration/` | Separate scope |
| --- | --- |
| `native2612_broad.py`, `native2612_interop.py` | Walkable Works/lifecycle and real cross-mod interoperability |
| `native2612_machines.py`, `native2612_networks.py` | Harvest, movement, physical unload/reload orders and extended drill/network controls |
| `native2612_upgrade.py` | Explicit historical source route, native conversion, operation, restart and raw saved witnesses |
| `native2612_actors.py`, `native2612_actor_transformer_order.py` | Player/entity/dimension authority and actor-inclusive real transformer orders; not replaced by machine-only migration |
| `native2612_resources.py` | Real ingredient acquisition, recipe unlock and manufacturing progression |
| `native2612_worldgen.py`, `native2612_retained_worldgen.py`, `native2612_oil.py` | Normal/retained generation semantics and bounded oil processing/transport, not superflat station coverage |
| `native2612_client.py` | Separate real-client rendering evidence; server or unit results do not replace it |

Native destination readers use DataVersion 4790, explicit
`dimensions/<namespace>/<path>/` terrain/entity storage, `players/data/`, and
the separate worldgen SavedData. Historical source layouts remain version-specific.
Do not infer destination dimensions from a legacy `DIM*` basename or discard
unknown NBT to make a comparison pass.

### Genuine Endurance, Freeze And Replay

Before **any** endurance setup, finish the full finite gate and a new full
`original110` machine migration against the same frozen profile. A hook-only,
provisional, old-candidate or machine-only result cannot stand in for a separately
required actor/player campaign. The retained endurance input must be the stopped
runtime and exact successful full `original110` receipt, including operation.

The reviewed engine receipt is a separate required input. It binds the actual
core/classifier and primary native source/binary proof; `seal` does not manufacture
that review. Keep the complete transitive oracle/runtime closure unchanged.

```powershell
$EngineReceipt = '<reviewed-engine-proof.json>'
$EngineSha = '<actual-engine-receipt-SHA256>'
$Retained = '<stopped-current-profile-original110-runtime>'
$RetainedReceipt = '<current-profile-original110-upgrade-results.json>'
$RetainedSha = '<actual-retained-receipt-SHA256>'
$Seal = '<new-pre-run-seal.json>'
$Run = '<new-two-world-endurance-output>'
$Frozen = '<new-stopped-freeze-directory>'

& $Python -B native2612_endurance_replay.py seal --profile $Profile `
    --profile-sha256 $ProfileSha --specification '../src/main/resources/assets/advantageworkstest/works/works-1.13.2.json' `
    --inventory $Inventory --inventory-sha256 $InventorySha --java $Java `
    --engine-receipt $EngineReceipt --engine-receipt-sha256 $EngineSha `
    --retained-receipt $RetainedReceipt --retained-receipt-sha256 $RetainedSha --output $Seal
if ($LASTEXITCODE -ne 0) { throw 'Preseal failed; do not begin setup' }
$SealSha = (Get-FileHash -LiteralPath $Seal -Algorithm SHA256).Hash

& $Python -B native2612_endurance.py @Inputs --seal $Seal --seal-sha256 $SealSha `
    --retained $Retained --retained-receipt $RetainedReceipt `
    --retained-receipt-sha256 $RetainedSha --seconds 1800 --port 25621 --output $Run
if ($LASTEXITCODE -ne 0) { throw 'Endurance incomplete; retain failure or hold receipt' }

$Receipt = Join-Path $Run 'native2612-endurance-results.json'
$ReceiptSha = (Get-FileHash -LiteralPath $Receipt -Algorithm SHA256).Hash
& $Python -B native2612_endurance_replay.py freeze --run $Run --seal $Seal `
    --seal-sha256 $SealSha --receipt-sha256 $ReceiptSha --output $Frozen
if ($LASTEXITCODE -ne 0) { throw 'Stopped freeze failed' }
$FreezeSha = (Get-FileHash -LiteralPath (Join-Path $Frozen 'freeze.json') -Algorithm SHA256).Hash
& $Python -B native2612_endurance_replay.py replay --frozen $Frozen `
    --freeze-sha256 $FreezeSha --seal-sha256 $SealSha --output '<new-independent-replay.json>'
if ($LASTEXITCODE -ne 0) { throw 'Independent stopped replay failed' }
```

The producer requires two adjacent free ports and runs separate fresh and retained
worlds with independent native clocks. Each needs at least 1,800 measured seconds,
30 natural-tick batches, at least 18 TPS in every measured interval, a full process
restart, and at least 60 seconds of resumed processing. A below-threshold resource
throughput failure remains a failure, not a waived gameplay pass. A
`hold-endurance.request` produces `HELD`, never qualification.

After final tick-frozen observations and owned save/stop, `freeze` requires stopped
worlds and exact source/copy inventories. Independent replay checks all 66 ledger
checkpoints, every batch, interval clocks, feed conservation and exact typed raw
native4790 machine state/NBT. It launches no Minecraft process and does not prove
client appearance, player conversion, every worldgen rule or manual gameplay.
`--retrospective` is diagnostic only: its replay is explicitly
`PASS_RETROSPECTIVE_REPLAY_NOT_PRE_RUN_CUSTODY`, not a genuine presealed pass.
Record the full profile, seal, producer, freeze and replay hashes plus process/port
closure. Never overwrite a failed attempt or substitute later jars into its proof.

### Retained Failures And Negative Controls

Known historical failures are evidence, not runtime ignore rules:

| Retained observation | Required distinction |
| --- | --- |
| Obsolete InventoryLocks SRG field lookup; nullable Steam fuel remainder; last-fuel container loss | Real production failures with retained RED evidence; new jars need new full input pins and campaigns |
| Saved Steam enchantment IDs flattened as vanilla curses before selected-world context preparation | Machine-only migration/endurance could pass while actor preservation failed; require the independent actor/order scope |
| Global atlas directories importing foreign low-resolution sprites | A dedicated-server pass does not qualify native mip-level client startup or geometry |
| Active legacy OreSpawn profiles retaining obsolete Power/Electric output IDs | Preserved JSON and valid new defaults do not establish equivalent retained generation; final profile-migration qualification remains pending |

Focused controls reject wrong Java or shim bytes, modified launch arguments,
missing/duplicate mods, harness/Power pin mismatch, inherited JVM overrides,
occupied ports, empty-server pausing, incomplete cold stop and tampered evidence.
A crash can exit 0: exit code alone is insufficient. Native identity and `Done`
must precede an owned stop and the final post-stop dimension-save witness; an
earlier startup/autosave cannot replace the final flush.

The Forge64 logging adapter changes exactly two source-hash-bound `%xEx` tokens
to `%ex`; it does not disable DEBUG, filters or diagnostics. The strict Netty
classifier recognizes only the reviewed, source/binary-bound Java25 capability
probe sequence followed by successful Java25 cleaner availability. Raw logs are
retained. Changed counts, levels, messages, frames or missing success are errors,
not a blanket exception whitelist or a reason to re-enable Unsafe.

---

> **Historical references below, not native26 instructions.** Their Java versions,
> profiles, optional-mod rules and example commands belong to their stated older
> targets. Native26 requires all six pinned inputs, including Base Metals; use
> only the native26 section above for the current port.

## Retained 1.21.1 Reference

The retained target is Minecraft 1.21.1, Forge 52.1.0 and Java 21. Start with
the [native test instructions](profiles/native1211/README.md) and the
[port record](../docs/1.21.1/PORT-STATUS.md). Use only the current hash-pinned
inputs in disposable worlds. Earlier results do not qualify a rebuilt jar.

The rig checks real machine operation, saved state, migration, recipes,
partial network unloading, moving drills and endurance. Client rendering
checks are separate. Manual gameplay and visual acceptance remain required.

## Retained 1.18.2 Reference

Use Java 17, Forge 40.3.0 and the exact jars in
[`profiles/native118/`](profiles/native118/). These profiles exercise
the 21 Works stations in a disposable world. The current profile uses
the local Base Metals 3.1.2 build for old-world upgrades; the decoupled
profile exercises the same machines without Base Metals.

Start with the [1.18.2 port and test guide](../docs/1.18/PORT-STATUS.md).
It lists the runners for migration, recipe progression, real machine
operation, network unloading, mining, oil, and the 30-minute test.
The automated results do not replace the manual gameplay checklist.

## Retained 1.16.5 Reference

The earlier target is Minecraft 1.16.5 / Forge 36.2.34, official mappings
1.16.5, MCP 20210115.111550, Java 8 bytecode and harness
`1.1.0.116051`. Read [the native116 runner and qualification limits](NATIVE116.md)
before using this checkout. Build success is not runtime or migration acceptance.

The 1.16.5 destination profiles live only in `profiles/native116/`. The original
profiles, station specification and legacy migration source adapters remain
unchanged. New profiles explicitly reference the historical PA113 source root;
the native runner refuses to launch an old profile as a native116 destination.
The native114 scripts/profiles and [their qualification record](NATIVE114.md)
remain historical evidence, together with the unchanged [native115 record](NATIVE115.md).
Missing 116 artifact pins fail before runtime creation. No earlier pass is a 116
runtime qualification. New flat fixtures require the harness-only
`advantageworkstest:test_flat` world preset; it saves as vanilla `minecraft:flat`.

## Retained 1.13.2 Protocol Reference

The remaining text describes the retained **1.13.2 source qualification**, not
1.14.4 results. Its paths and old artifact versions are historical. Use the
original source checkout at `D:/PowerAdvantage/PA113/PowerAdvantage` for that
qualification and its documentation. Do not execute copied Python defaults
from PA114 or alias `target114` to `target113`.

> **Commissioning notice:** This harness builds disposable works. It is not a gameplay mod, registers no content, and must never be shipped inside a PowerAdvantage jar.

The rig turns the 20 cards from the Advantage Works 1.10 handbook plus a public-API fixture into one walkable superflat server world. Power Works lies west of the central concourse, Steam Works north, Electric Works east, and the drill, lift, turbine, pumping, musket, and turret proving grounds south. See the [1.13 migration guide](../docs/1.13/MIGRATION.md) for this port's qualification status.

Use the [current qualification and remaining-tests checklist](../docs/1.13/CURRENT-QUALIFICATION.md)
to distinguish structural bay checks from measured machine operation and manual
acceptance. Building every bay does not prove every behaviour on its card.

The [connected legacy migration runner](../docs/1.13/CONNECTED-MIGRATION.md)
builds and runs real assemblies under old jars, then resumes copied equipment
without replacing machines or refilling supplies. Its intermediate 1.12 failure
is retained separately from successful 1.13 operation.

For non-interactive recipe checks, see [Recipe Discovery](../docs/1.13/RECIPE-DISCOVERY.md) and [Manufacturing Progression](../docs/1.13/MANUFACTURING-PROGRESSION.md). The progression runner starts from raw resources, records every item/recipe path, runs live processing witnesses and checks all modes, resource reloads and full restarts. It uses a separate marked disposable world, not the walkable viewer save.

## Safety interlocks

The `/advworks` command refuses to build or clear unless both conditions hold:

- the level name begins with `AdvantageWorks`;
- the server working directory contains `advantage-works.allow`.

Each station has declared bounds. Reset clears blocks only inside those bounds and removes only entities tagged by that station. Generated saves, staged jars, logs, reports, Forge server files, caches, and local settings are ignored by Git.

## Operator controls

Every bay has clickable start, stop, reset, and check signs plus a wool status lamp. Console operators can use:

```text
/advworks build [all|power|steam|electric]
/advworks reset <station|all>
/advworks start <station>
/advworks stop <station>
/advworks check <station|all>
/advworks status [station]
/advworks checkpoint <station> <name>
/advworks sample-worldgen <chunk-radius>
```

Before a player uses the clickable controls, grant that tester operator permission with `op <player>` at the server console. The harness intentionally keeps `/advworks` at permission level 2 as a second interlock beyond the marked disposable world.

The definition at `src/main/resources/assets/advantageworkstest/works/works-1.13.2.json` owns station origins, bounds, placements, setup/start/stop actions, assertions, required mods, and manual checks. Station IDs are the handbook card IDs `W-01` through `E-08`, plus `P-API-01` for the stable fluid-network API.

## Result meanings

| Result | Meaning |
| --- | --- |
| `PASS` | Every automated assertion passed and no manual observation remains. |
| `FAIL` | An ordinary assertion failed. Automated runners stop with failure. |
| `EXPECTED_FAIL` | A declared handbook bug reproduced; the assertion names its bug ID. |
| `UNEXPECTED_PASS` | A declared bug no longer reproduced and needs review before expectations change. |
| `SKIP` | A required mod or integration is absent. |
| `MANUAL_REQUIRED` | Structural assertions passed, but gameplay observations remain on the station card. |

Animation and GUI appearance are never sufficient proof. Use checkpoints and reconcile bounded items, fluids, and energy.

The focused [oil-transfer protocol](../docs/1.13/OIL-TRANSFER.md) adds four isolated remote lanes: crude/refined oil through a drain and descending/horizontal pipes into each container type. It uses real bucket placement, exact fluid identity/volume assertions, two complete process restarts, and a second fill without replacing machinery. This closes a gap in `W-01`/`W-02`, whose drain assertions alone did not establish bounded oil conservation.

## Build and development run

Run Gradle with Java 17. The harness and all Minecraft code compile to Java 8 bytecode.
Compiler outputs and harness jars live in `build/gradle/`. Pinned dependencies,
disposable saves and reports remain in sibling directories under `build/`.
The harness `clean` task is restricted to `build/gradle/`; it must never clear the
shared fixture and evidence root.

```text
gradlew.bat -p test-rig clean test assemble
gradlew.bat -p test-rig verifyWorksDev
gradlew.bat -p test-rig runWorksDev
```

On Linux or macOS use `./gradlew` with the same arguments. `verifyWorksDev` builds all stations, runs each bounded start/stop sequence, saves, stops the server process, starts it again, checkpoints the retained state, and reruns the structural assertions. `runWorksDev` leaves the disposable server open at `localhost:25565` for a human inspector.

Long `worksRunSeconds` holds do not replenish finite inputs and may mostly observe
idle equipment. For thirty minutes of measured repeat batches, use the separate
[endurance runner](migration/README.md#repeated-load-endurance) and read its
[qualification limits](../docs/1.13/ENDURANCE.md).
For genuine chunk unload/reload and moving-drill tests, use the separate
[chunk and drill protocol](../docs/1.13/CHUNK-AND-DRILLS.md). Its results distinguish
inventory and network recovery from the separate movement-cost, rear-output
and unfinished-progress acceptance checks, with before/after repair evidence.
The [extended lifecycle protocol](../docs/1.13/PARTIAL-NETWORKS-AND-EXTENDED-DRILLS.md)
adds separate supply/consumer unloading, reload orders, repeated moves, vertical
tracks, junctions, blocked chests, low energy and redstone controls.
The ordinary lifecycle runner gives `W-03` at least sixty seconds to finish its
2,000 mB oil batch; its exact circuit-wide yield assertion cannot finish in the
five-second quick-check default used for other stations.

Limit an automated run to selected lifecycle bays, or turn one into a 30-minute endurance run, without changing its profile:

```text
gradlew.bat -p test-rig verifyWorksDev -PworksStations=W-04
gradlew.bat -p test-rig verifyWorksPackagedCurrent -PworksStations=W-04,S-02 -PworksRunSeconds=1800
gradlew.bat -p test-rig verifyWorksPackagedCurrent -PworksStations=E-01 -PworksServerPort=25566
```

Every machinery profile still builds and checks the complete works; the selection controls which stations are started, stopped, and checkpointed around the full process restart. Normal-world generation profiles deliberately skip station construction. The configured run duration is also used as a post-restart settle interval before assertions, allowing an eight-tick distribution cycle to complete. Use `worksServerPort` when another local server occupies the profile's default port. The effective station list, duration, and port are recorded in `run-manifest.json`.

The development profile uses current PowerAdvantage source plus the sibling SteamAdvantage and ElectricAdvantage deobf jars. The normal development runtime also includes the pinned Base Metals and OreSpawn deobf jars required by the 1.13 data-driven crushing and provider APIs. Use the packaged decoupled profile to prove behavior without Base Metals.

## Run matrix

The separate [original-release/chained migration matrix](../docs/1.13/MIGRATION-MATRIX.md)
freezes source-version machines, compares their serialized state through conversion,
and checks a full server restart. Its [runner instructions](migration/README.md)
describe the source fixtures, safety interlocks, and pinned input profiles.

| Profile/task | Purpose |
| --- | --- |
| `verifyWorksDev` | Current Power source and sibling deobf extensions with pinned Base Metals and OreSpawn 4 |
| `verifyWorksPackagedCurrent` | Reobfuscated Advantage jars with BaseMetals `3.0.1.113021` and pinned OreSpawn `4.1.0.113021` |
| `verifyWorksPackagedDecoupled` | Reobfuscated Advantage jars with OreSpawn 4 but without BaseMetals/Mineralogy |
| `verifyWorksPackagedMineralogy` | Reobfuscated Advantage and Mineralogy jars; exercises both crude oils through distillation, transfer, persistence, and boilers |
| `verifyWorksPackagedOptionalPower` | Preserved RF- and Tech Reborn-labelled converter identities through Forge Energy |
| `verifyWorksTechProgression` | Registration/build audit under `TECH_PROGRESSION` |
| `verifyWorksApocalyptic` | Registration/build audit under `APOCALYPTIC` |
| `verifyWorksWorldgenAdvantage` | Normal world: Electric fallback sulfur/lithium, Power desert oil, user-disable persistence |
| `verifyWorksWorldgenMineralogy` | Normal world: Mineralogy sulfur/ocean oil, Electric lithium fallback, user-enable persistence |
| `verifyWorksWorldgenLegacyImport` | Normal world: OS1 files migrate once, remain authoritative, and do not duplicate modern rules |

Profiles pin filenames, mod IDs, versions, and SHA-256 values. Most workspace-built harness jars are identified and version-checked without a source-dependent hash; `packaged-current` also pins the harness used for the endurance qualification. Refresh that hash deliberately after rebuilding changed harness sources. The packaged runner downloads only the pinned official Forge installer, verifies it, installs a fresh server into the disposable runtime, and launches Minecraft with Java 8.

Set these only for profiles that need them:

```text
ADVANTAGE_WORKS_CURRENT_MODS=<directory containing BaseMetals-3.0.1.113021.jar>
ADVANTAGE_WORKS_JAVA8_HOME=<Java 8 home, when it is not in the default Gradle toolchain cache>
ADVANTAGE_WORKS_GRADLE_JAVA_HOME=<Java 17 home, when the runner itself is launched on Java 8>
```

## Station register

| District | Cards |
| --- | --- |
| Power Works | `W-01` fluid persistence; `W-02` drain/discharge reach; `W-03` distillation; `W-04` conveyors and all filters |
| Power API Works | `P-API-01` public fluid offer, transfer limit, full consumer, invalid target, and input immutability |
| Steam Works | `S-01` boilers; `S-02` topology; `S-03` storage/scarcity; `S-04` furnace/crusher/still |
| South proving grounds | `S-05` drill; `S-06` pump; `S-07` elevator; `S-08` musket; `E-01` generators/turbine; `E-06` drill; `E-07` light/turret |
| Electric Works | `E-02` batteries/distribution; `E-03` processors; `E-04` fluids; `E-05` growth; `E-08` isolated assembler diagnostic |

Ore generation is intentionally excluded from the superflat works. The three `verifyWorksWorldgen*` tasks create separately named disposable normal worlds, locate nearby desert and ocean biome samples, generate bounded chunk squares, count the target blocks, check oil biome placement, and repeat the same scan after a full process restart.

## OreSpawn profile checks

The normal-world profiles inspect `<world>/serverconfig/orespawn-worldgen.json` after first shutdown and after restart. They verify default ownership, edit a persisted `enabled` flag to simulate a user choice, and require OreSpawn to preserve that choice. The legacy profile stages the retained OS1 Power/Electric files under `config/orespawn/`, requires their migration report and provider files, and rejects simultaneous modern duplicate IDs.

`worldgen-mineralogy` covers Mineralogy-over-Electric sulfur ownership and both oil biomes. BaseMinerals does not yet have a 1.13 provider release, so its ownership combinations remain statically covered by ElectricAdvantage's provider tests and are an explicit pending runtime matrix item.

The sampler records block counts, sampled biome centers, any deposit-producing chunks outside their declared biome selector, and informational per-block spill at biome boundaries. OreSpawn selects deposits by chunk-centre biome. Existing chunks are sampled again after restart; disabled rules affect only terrain generated later and therefore must not erase or alter the recorded blocks. Solid ore counts must remain exact. The legacy profile excludes only `poweradvantage:crude_oil` from exact count comparison because OS1 represented it as an exposed ore vein and the fluid can continue flowing between observations; its migrated rule, enabled state, and positive presence are still asserted on both runs.

## Confirmed-bug regression witnesses

| Station | Automated witness | Manual remainder |
| --- | --- | --- |
| `S-01` | Seeds both `Tempoerature` and `Temperature`, saves canonically, and requires retained heat after a full process restart (`SA-110-002`). | Compare the geothermal GUI gauge with the saved value. |
| `S-04` | An isolated unpowered crusher with one cobblestone must report comparator level `1` before and after restart (`SA-110-003`). | Check intermediate and full stack levels when commissioning redstone automation. |
| `S-06` | An isolated pump must retain exactly 1,000 mB of water after restart (`SA-110-001`). | Confirm the pump GUI agrees with the server NBT. |
| `E-01` | Requires an active hydroelectric generator and charge in its directly connected battery after restart (`EA-110-003`). | Open the hydroelectric GUI and confirm a normal-width active bar. |

## Release isolation

`verifyHarnessIsolation` opens the PowerAdvantage release jar and rejects any `zone/moddev/mc/advantageworks` class or `assets/advantageworkstest` resource. The normal PowerAdvantage build never includes the nested `test-rig` source set.
