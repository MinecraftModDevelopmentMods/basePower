# Advantage Works 1.10.2 Test Rig

The current targets are basePower, baseSteam and baseElectric. Station IDs and operator commands stay unchanged. Original-release Advantage fixtures are read-only migration sources, not interchangeable runtime dependencies. See [Moving From Advantage To basePower](../docs/1.10/base-migration.md) before testing an existing world.

See [Recipe Progression Checks](../docs/1.10/recipe-progression-checks.md) for finite-material native crafting, hammer and merchant transactions, and the connected oil/plastic and steam starter plant. This is separate from the older recipe-registration audit, whose partial results are retained.

> **Commissioning notice:** This harness builds disposable works. It is not a gameplay mod, registers no content, and must never be shipped inside a basePower jar.

The rig turns the 20 cards in the [Advantage Works handbook](../docs/1.10/README.md) plus a public-API fixture into one walkable superflat server world. Power Works lies west of the central concourse, Steam Works north, Electric Works east, and the drill, lift, turbine, pumping, musket, and turret proving grounds south.

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

The definition at `src/main/resources/assets/advantageworkstest/works/works-1.10.2.json` owns station origins, bounds, placements, setup/start/stop actions, assertions, required mods, and manual checks. Station IDs are the handbook card IDs `W-01` through `E-08`, plus `P-API-01` for the stable fluid-network API.

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

## Build and development run

Run Gradle with Java 17. The harness and all Minecraft code compile to Java 8 bytecode.

```text
gradlew.bat -p test-rig clean test assemble
gradlew.bat -p test-rig verifyWorksDev
gradlew.bat -p test-rig runWorksDev
```

On Linux or macOS use `./gradlew` with the same arguments. `verifyWorksDev` builds all stations, runs each bounded start/stop sequence, saves, stops the server process, starts it again, checkpoints the retained state, and reruns the structural assertions. `runWorksDev` leaves the disposable server open at `localhost:25565` for a human inspector.

Limit an automated run to selected lifecycle bays, or turn one into a 30-minute endurance run, without changing its profile:

```text
gradlew.bat -p test-rig verifyWorksDev -PworksStations=W-04
gradlew.bat -p test-rig verifyWorksPackagedCurrent -PworksStations=W-04,S-02 -PworksRunSeconds=1800
gradlew.bat -p test-rig verifyWorksPackagedCurrent -PworksStations=E-01 -PworksServerPort=25566
```

Every machinery profile still builds and checks the complete works; the selection controls which stations are started, stopped, and checkpointed around the full process restart. Normal-world generation profiles deliberately skip station construction. The configured run duration is also used as a post-restart settle interval before assertions, allowing an eight-tick distribution cycle to complete. Use `worksServerPort` when another local server occupies the profile's default port. The effective station list, duration, and port are recorded in `run-manifest.json`.

The development profile uses current basePower source plus the sibling baseSteam and baseElectric deobf jars. basePower supplies the pinned OreSpawn 4 development runtime; BaseMetals and Mineralogy remain absent so their integrations are visibly skipped.

## Run matrix

| Profile/task | Purpose |
| --- | --- |
| `verifyWorksDev` | Current Power source and sibling deobf extensions with pinned OreSpawn 4, without BaseMetals/Mineralogy |
| `verifyWorksPackagedCurrent` | Reobfuscated Advantage jars with BaseMetals `2.5.0-beta4.238` and pinned OreSpawn `4.0.8.110021` |
| `verifyWorksPackagedLegacy` | Reobfuscated Advantage jars with BaseMetals `2.4.0.11` and pinned OreSpawn `4.0.8.110021` |
| `verifyWorksPackagedDecoupled` | Reobfuscated Advantage jars with OreSpawn 4 but without BaseMetals/Mineralogy |
| `verifyWorksPackagedMineralogy` | Reobfuscated Advantage and Mineralogy jars; exercises both crude oils through distillation, transfer, persistence, and boilers |
| `verifyWorksPackagedOptionalPower` | RF API and RebornCore converter discovery with OreSpawn 4 |
| `verifyWorksTechProgression` | Registration/build audit under `TECH_PROGRESSION` |
| `verifyWorksApocalyptic` | Registration/build audit under `APOCALYPTIC` |
| `verifyWorksWorldgenAdvantage` | Normal world: Electric fallback sulfur/lithium, Power desert oil, user-disable persistence |
| `verifyWorksWorldgenMineralogy` | Normal world: Mineralogy sulfur/ocean oil, Electric lithium fallback, user-enable persistence |
| `verifyWorksWorldgenLegacyImport` | Normal world: OS1 files migrate once, remain authoritative, and do not duplicate modern rules |

Profiles pin filenames, mod IDs, versions, and SHA-256 values. Workspace-built harness jars are identified and version-checked but not pinned to a source-dependent hash. The packaged runner downloads only the pinned official Forge installer, verifies it, installs a fresh server into the disposable runtime, and launches Minecraft with Java 8.

Set these only for profiles that need them:

```text
ADVANTAGE_WORKS_CURRENT_MODS=<directory containing BaseMetals-1.10.2-2.5.0-beta4.238.jar>
ADVANTAGE_WORKS_REBORNCORE_110=<full path to reborncore-237903-2425028.jar>
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

`worldgen-mineralogy` covers Mineralogy-over-Electric sulfur ownership and both oil biomes. A current BaseMinerals 1.10 artifact is not present in this workspace, so the BaseMinerals ownership combinations remain statically covered by ElectricAdvantage's provider tests and are an explicit pending runtime matrix item. Do not represent that combination as runtime-verified until a filename, mod version, and SHA-256 can be pinned.

The sampler records block counts, sampled biome centers, any deposit-producing chunks outside their declared biome selector, and informational per-block spill at biome boundaries. OreSpawn selects deposits by chunk-centre biome. Existing chunks are sampled again after restart; disabled rules affect only terrain generated later and therefore must not erase or alter the recorded blocks. Solid ore counts must remain exact. The legacy profile excludes only `poweradvantage:crude_oil` from exact count comparison because OS1 represented it as an exposed ore vein and the fluid can continue flowing between observations; its migrated rule, enabled state, and positive presence are still asserted on both runs.

## Confirmed-bug regression witnesses

| Station | Automated witness | Manual remainder |
| --- | --- | --- |
| `S-01` | Seeds both `Tempoerature` and `Temperature`, saves canonically, and requires retained heat after a full process restart (`SA-110-002`). | Compare the geothermal GUI gauge with the saved value. |
| `S-04` | An isolated unpowered crusher with one cobblestone must report comparator level `1` before and after restart (`SA-110-003`). | Check intermediate and full stack levels when commissioning redstone automation. |
| `S-06` | An isolated pump must retain exactly 1,000 mB of water after restart (`SA-110-001`). | Confirm the pump GUI agrees with the server NBT. |
| `E-01` | Requires an active hydroelectric generator and charge in its directly connected battery after restart (`EA-110-003`). | Open the hydroelectric GUI and confirm a normal-width active bar. |

## Release isolation

`verifyHarnessIsolation` opens the basePower release jar and rejects any `zone/moddev/mc/advantageworks` class or `assets/advantageworkstest` resource. The normal basePower build never includes the nested `test-rig` source set.

## Migration Evidence

The native migration runners distinguish three kinds of evidence:

- Connected original-release worlds prove saved-state preservation and paid work without replacing machines, followed by a full server restart.
- The larger passive original-release fixture checks saved blocks, metadata, inventories and typed NBT before machinery can tick.
- Newly constructed nested inventory and entity fixtures test additional migration cases. They are synthetic tests, not evidence that a historical player used that exact arrangement.

Each run pins the candidate jars, server code, fixture and test protocol. Failed receipts remain available. A known dependency warning may only be classified after a fresh, hash-bound dependency-only control and candidate behaviour check; it is not a blanket permission to ignore startup errors. Endurance means a measured 30-minute finite-resource operating run, not a shortened smoke run.
