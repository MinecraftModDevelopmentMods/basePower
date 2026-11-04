# basePower for Minecraft 1.12.2

basePower continues DrCyano's Power Advantage. It adds conveyors, filters, fluid plumbing and the shared systems used by baseSteam and baseElectric. The 1.12.2 build uses Forge 14.23.5.2859 and Java 8.

## Moving a world from 1.10.2

You can upgrade directly from the original released Advantage mods or from basePower, baseSteam and baseElectric on 1.10.2. Back up your complete world and configuration first. Replace all installed Advantage mods together and use the matching 1.12.2 builds of Base Metals and MMD OreSpawn. Check a copy through a complete save, exit and reopen before upgrading your main world.

Block and item paths are unchanged. The new builds read both generations of tile IDs, and preserve fluid names and machine data. Keep any required third-party mods installed: this is not a migration for missing mods elsewhere in your pack. Read the [world upgrade guide](docs/1.12-upgrade.md) before moving a valued save.

[CurseForge](https://www.curseforge.com/minecraft/mc-mods/basepower) | [Player wiki](https://github.com/MinecraftModDevelopmentMods/basePower/wiki)

## Building Minecraft 1.12.2

The `master-1.12.2` build uses ForgeGradle 7.0.34 and Gradle 9.6.1. Run Gradle with Java 17; Gradle resolves the Java 8 toolchain used for compilation.

```text
gradlew.bat clean check build verifyReleaseDependencies verifyReleaseArtifacts writeReleaseChecksums
```

Release jars are written to `build/libs`. The deobfuscated development jar for sibling mod compilation is written to `build/libs-dev` by `deobfJar` or `build`.

For Eclipse, import the repository as an existing Gradle project and run:

```text
gradlew.bat cleanEclipse verifyEclipseProductionClasspath
```

Build the pinned OreSpawn 4.1.3.112021 development jar first, or pass `-PoreSpawnDeobfJar=<path>`. CI builds the exact source in `gradle.properties`. Base Metals is a distribution dependency, not a compile dependency. Optional RF and RebornCore APIs are compile-only and are not bundled. CI stages the exact historical RebornCore Maven artifact; the later CurseForge archive with the same version has different contents.

See [Checking a base workshop upgrade](docs/1.12-validation.md) for the current test procedure. The [test coverage notes](TEST-COVERAGE-BACKPORT.md) describe the preceding Advantage build.
