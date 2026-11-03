# basePower

basePower continues DrCyano's Power Advantage as a Base Metals expansion for Minecraft 1.10.2. It provides conveyors, filters, fluid pipes, tanks, oil processing and the shared power systems used by baseSteam and baseElectric. The original Advantage repositories remain available as historical references.

The new mod ID is `basepower`, with implementation packages under `zone.moddev.mc.basepower`. This is a coordinated replacement, not an add-on to the old Power Advantage jar. Read the [world migration instructions](docs/1.10/base-migration.md) before replacing mods in an existing world.

## Playing And Upgrading

basePower is available on [CurseForge](https://www.curseforge.com/minecraft/mc-mods/basepower), project `1780140`. The [player wiki](https://github.com/MinecraftModDevelopmentMods/basePower/wiki) covers installation, machines, recipes and upgrading.

Use Java 8, Forge `12.18.3.2511` or a compatible later 1.10.2 build, Base Metals and MMD OreSpawn. The maintained dependency pack was tested with Base Metals `2.5.1.110021` and OreSpawn `4.1.2.110021`.

Back up the complete world and configuration, then try the upgrade on a copy. Replace Power Advantage with basePower. Replace Steam and Electric Advantage with matching baseSteam and baseElectric builds if they are installed; do not mix the old and new families. Existing machines and their saved contents migrate without rebuilding. Check the copy again after closing Minecraft completely and restarting it.

The supported historical source is the released Cyano 1.10.2 line. Old third-party integrations which check the original mod IDs may need an update. In particular, IAF's plumbing provider detection needs its separate basePower update; the preserved API alone cannot change that check.

## Validation And Guides

The [current candidate checks](docs/1.10/base-qualification.md) list the exact tested jars, results and remaining release gates.

The [recipe progression checks](docs/1.10/recipe-progression-checks.md) explain how the test rig verifies finite crafting chains, returned buckets, trading and a connected starter plant across full restarts.

The [historical test coverage notes](TEST-COVERAGE-BACKPORT.md#final-recorded-candidate) record the earlier Advantage candidates, including their exact hashes and remaining limits. They do not qualify the renamed jars. The base migration uses fresh disposable-world, operating, restart, GUI and endurance checks. The bounded recipe audit is separate from its still-partial crafting progression graph; neither is an all-machines or all-worlds guarantee.

The maintained implementation namespace is `zone.moddev.mc.basepower`. The legacy
`cyano.poweradvantage.api` package remains the supported add-on API.

External fluid producers can offer fluid to an adjacent basePower pipe through
`cyano.poweradvantage.api.fluid.FluidNetworkApi.offerFluid(...)`. The method reports the exact
accepted amount without draining the caller's tank, allowing optional integrations to remain in
control of their own fluid state.

The [Advantage Works 1.10.2 Handbook](docs/1.10/README.md) documents every machine and component across Power, Steam, and Electric Advantage, including commissioning layouts and known investigation points. The [MMD material and fluid compatibility specification](docs/1.10/mmd-material-fluid-compatibility.md) defines OreSpawn ownership, legacy migration, crude-oil aliases, and Ore Dictionary names.

The source-controlled [Advantage Works test rig](test-rig/README.md) turns those commissioning cards into a disposable, walkable Forge server world with repeatable save/restart checkpoints. It is a separate test mod and is never packaged with basePower.

## Building Minecraft 1.10.2

The candidate versions are basePower `2.4.4.110021` and baseSteam/baseElectric `2.2.6.110021`. Local qualification includes 188 operating cases and 188 cold-state comparisons in each dependency pack, original Cyano fixtures, a disposable Sylvester upgrade, and two finite-supply Steam endurance runs. These are not a promise that every machine has run for 30 minutes or that every third-party integration has been tested.

The `master-1.10.2` build uses ForgeGradle 7.0.34 and Gradle 9.6.1. Run Gradle with Java 17; Gradle resolves the Java 8 toolchain used for compilation.

Build the pinned OreSpawn commit `5a50df1158e948c8db55814e819090b34c12d765` first, or pass its deobf jar with `-PoreSpawnDeobfJar=<path>`.

```text
gradlew.bat clean check build verifyReleaseDependencies verifyReleaseArtifacts writeReleaseChecksums
```

The separate harness and migration-contract checks are also run in CI. With Python 3.12 installed:

```text
gradlew.bat -p test-rig clean check assemble
python -m pip install -r test-rig/migration/requirements.txt
python -m unittest discover -s test-rig/migration -p "test_*.py"
```

These contract tests do not start the full native server matrix. Its copied-world, operating and endurance results are recorded in the qualification notes.

Release jars are written to `build/libs`. The deobfuscated development jar for sibling mod compilation is written to `build/libs-dev` by `deobfJar` or `build`.

For Eclipse, import the repository as an existing Gradle project and run:

```text
gradlew.bat cleanEclipse verifyEclipseProductionClasspath
```

Base Metals remains a required distribution dependency and is absent from the compile classpath. OreSpawn is a required runtime dependency whose pinned public API is used at compile time; it is never bundled. Optional RF and RebornCore integrations compile against pinned API inputs and are not bundled.

## Publishing

Source delivery and CurseForge publication are separate. basePower uses CurseForge project `1780140`; the original Power Advantage project must not be reused. Ordinary CI does not create a tag, release or upload, and assigning the project ID does not authorise another publication.

The [project-page copy](docs/curseforge-description.md) and [logo](docs/branding/README.md) are kept with the source. The logo is not part of the mod jar, so it does not change the tested artifacts.
