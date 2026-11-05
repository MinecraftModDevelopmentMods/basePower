# basePower for Minecraft 1.12.2

basePower continues DrCyano's Power Advantage. It adds conveyors, filters, fluid plumbing and the shared systems used by baseSteam and baseElectric. The 1.12.2 build uses Forge 14.23.5.2859 and Java 8.

## Moving an existing world

You can upgrade from the original Advantage mods or the base mods on 1.10.2. If you're already using the old Advantage mods on 1.12.2, you can replace them too. Back up your world and config, replace the old mods together, then try the upgrade on a copy. Save, close Minecraft completely and reopen the copy before moving your main world.

Use Forge 14.23.5.2859 or newer, with the 1.12.2 builds of Base Metals, MMDLib and MMD OreSpawn 4.1.3 or newer in the 4.x line. Existing MMDLib crusher recipes keep their saved IDs. If Forge still warns about missing entries, cancel and check what's missing before proceeding. See the [world upgrade guide](docs/1.12-upgrade.md).

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
