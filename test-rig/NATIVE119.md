# Advantage Works on Minecraft 1.19.4

The 1.19.4 rig uses Java 17 and Forge 45.4.0. Use the pins in
[`profiles/native119/`](profiles/native119/) for the three mods, OreSpawn
and the local Base Metals migration build. The public Base Metals build
is not a substitute for that migration pin.

## Build and inspect

From the basePower checkout, build the three mods first, then run:

```powershell
.\gradlew.bat -p test-rig check build runnerJar verifyHarnessIsolation
.\gradlew.bat -p test-rig verifyWorksNative119Inputs
.\gradlew.bat prepareCombinedEclipseLaunch
```

Refresh the Gradle project in Eclipse and use `runClientAllAdvantage.launch`
for all three mods, or `runClient.launch` for basePower alone. Use Java 17
for the launches. Neither command opens a world. Make a separate world copy
for any manual upgrade test.

For a disposable walkable Works server, run
`gradlew.bat -p test-rig runWorksPackagedCurrent`. Use the console
`stop` command to save and close it. The run folder records its inputs
and does not replace earlier results.

## Automated checks

The packaged profiles cover the same 21 stations with and without Base
Metals. The dedicated world-generation profiles check Advantage fallback
ores, Mineralogy coexistence and import of an existing OreSpawn profile.

The scripts in `migration/` accept a Forge server seed, a sealed code
inventory, the inventory's SHA-256 and a hash-pinned jar profile. Seal an
installed server with `native119.py --seed <server> --inventory <new-file>`
before using the migration runners.
Keep the source saves and their receipts outside the output directory.

- `native119_legacy_world.py` and `native119_modern_upgrade.py` compare
  saved and live state before operation and after a cold restart.
- `native119_broad.py` commissions every station; `native119_networks.py`
  tests partial unload, bridge reconnects, longer drill runs and Forge
  capabilities.
- `native119_recipes.py` checks the three recipe modes, ingredient sources
  and unlocks. Its recipe-book exports also feed the
  [graphical client check](client-resources/README.md).
- `native119_endurance.py` defaults to 30 minutes of measured operation.
  Give it a passing migration receipt from the same jar profile with
  `--retained <run>` to exercise a migrated world alongside the fresh one.
  Use `audit_native119_endurance.py` to replay the station ledgers.

Server success does not certify textures, GUI layouts or a real client's
login. Run the graphical check and keep manual acceptance separate.
