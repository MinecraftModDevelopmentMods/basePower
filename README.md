# basePower

**Minecraft 1.20.1, Forge 47.4.10, Java 17.**

This branch prepares basePower `2.4.12.120011`. It is not a download announcement.

See the [1.20.1 port record](docs/1.20.1/PORT-STATUS.md) for the current checks.
Results from earlier versions do not qualify this build.

basePower continues DrCyano's Power Advantage. It provides shared power,
fluids and item transport for baseSteam and baseElectric.

## Playing On 1.20.1

- Install [MMD OreSpawn 4.1.0.120011](https://www.curseforge.com/minecraft/mc-mods/mmd-orespawn/files/9175609) on both client and server.
- Base Metals is optional for a new basePower world. For a world that already
  uses Base Metals, keep it installed and use the matching Forge 1.20.1 build
  `3.1.2.120011`. Older Base Metals builds do not have the shared upgrade hook.
- baseSteam and baseElectric are separate mods. Use only their matching
  1.20.1 builds. Additional Loot Tables is not needed on 1.20.1.

**Back up the entire world before upgrading. Test only disposable copies.**
The port keeps existing registry names and machine data. The upgrade checks
use disposable copies of original Advantage and released base* saves, including
connected machines, and check them again after a full restart. Check that the
other mods in your world support 1.20.1 before you upgrade.
The accepted [1.14 results](docs/1.14/BROADER-QUALIFICATION.md) are historical
evidence, not qualification of this port or a whole modpack.

Recipe-book discovery now follows relevant ingredients. See the [all-Advantage material checklist and automated discovery tests](docs/1.13/RECIPE-DISCOVERY.md). To regenerate the checked-in unlock JSON after changing recipes, run `node tools/generate-recipe-unlocks.js`; `check` rejects missing or stale ingredient criteria.

The [manufacturing progression audit](docs/1.13/MANUFACTURING-PROGRESSION.md) traces survival parts from raw resources, including configured material aliases, processing, loot-only parts and automated restart checks.

## Building Minecraft 1.20.1

The build uses Forge 47.4.10, official `1.20.1` mappings,
MCP `20230612.114412`, ForgeGradle 7.0.34 and Gradle 9.6.1.
Use Java 17 for Gradle, compilation and Minecraft. Mavenizer also
needs Java 25. CI pins these JDKs and Java 8 for the legacy renamer. Pass the installed homes using
`-Porg.gradle.java.installations.paths=<java8>,<java17>,<java25>` and disable
automatic discovery and downloads for a reproducibility check.

Build the pinned Base Metals `3.1.2.120011` and OreSpawn `4.1.0.120011` deobf jars first.
The source pins and checksums are in `gradle.properties`.
Forge 47 also needs the Mavenizer compatibility jar in the pinned OreSpawn
checkout's `ci-fixtures/tools/` directory. It fixes decompiled Forge sources
on a clean build and uses Java 25. It is not part of the mod or game runtime.
Set `-Pforge47MavenizerJar=<path>` when that checkout is in another location.
If Base Metals is installed, use the pinned 3.1.2 build when testing an upgrade. It lets
both mods preserve old items and machines before Minecraft converts the
chunk. An older Base Metals jar is not a substitute for this upgrade path.

Then run from the repository root:

```text
gradlew.bat clean check build javadoc genEclipseRuns eclipse isolateEclipseProductionRuns verifyReleaseDependencies verifyReleaseArtifacts writeReleaseChecksums verifyEclipseProductionClasspath
```

The default dependency jars are:

```text
D:\BaseMetals\BaseMetals 1201\BaseMetals\build\libs-dev\BaseMetals-3.1.2.120011-deobf.jar
D:\MinecraftMineralogy\OS1201\OreSpawn\build\libs-dev\OreSpawn-4.1.0.120011-deobf.jar
```

Other layouts can pass `-PbaseMetalsDeobfJar=<path>` and `-PoreSpawnDeobfJar=<path>`.
Dependencies are never embedded in PowerAdvantage artifacts.

Dependency checksums and source commits are recorded in `gradle.properties`.
Unknown artifacts fail the dependency audit. The release audit also rejects a
player jar that differs from the frozen test kit. Hosted CI requires the Base
Metals pin to be on its upstream `master-1.20.1` branch; a locally tested pin
does not qualify a hosted build.

Release jars are written to `build/libs`; `deobfJar` writes the sibling-development jar to `build/libs-dev`.

For Eclipse, import this repository as an existing Gradle project and run:

```text
gradlew.bat cleanEclipse verifyEclipseProductionClasspath
```

Refresh the project in Eclipse, allow its Java build to finish, and launch the generated `runClient.launch`. Eclipse launches discover the mod from `bin/main`; command-line Gradle launches use `build/forge-run-mod-classes/main`. The Eclipse audit checks that discovery points at the same production output Eclipse compiles into, preventing a client that starts without PowerAdvantage.

Eclipse copies production resources from `build/resources/main`, not directly from `src/main/resources`. This prevents its builder from overwriting the expanded `mods.toml` with raw `${version}` placeholders. After updating this setup, use **Gradle > Refresh Gradle Project**, then **Project > Clean** before relaunching. After editing resources or version properties, run `processResources` or refresh the Gradle project before the Eclipse build. See the [metadata regression notes](docs/1.13/ECLIPSE-METADATA.md).

To prepare a combined client, build the baseSteam and baseElectric `2.2.13.120011` deobf jars,
run `gradlew.bat prepareCombinedEclipseLaunch`, refresh Eclipse, then launch
`runClientAllAdvantage.launch`. Inputs must match
`test-rig/profiles/native1201/dev-core.json`; alternative layouts can pass
`-PsteamAdvantageDeobfJar=<path>` and `-PelectricAdvantageDeobfJar=<path>`.
The ordinary `runClient.launch` remains Power-only. Both use the `run` directory;
preparing a launch does not copy, open or qualify a save.

`check` and the Eclipse audit verify that Java files are in the directories
matching their packages. They also read every development runtime jar entry
and check its size and CRC to catch damaged cached dependencies.

Keep Eclipse's access-restriction checks enabled and use Java 17 for its project
JRE and Minecraft launches. Check visuals, menus and recipe discovery against the
same jars used for the automated machine tests.

## Compatibility

The implementation namespace is `zone.moddev.mc.basepower`. The historical
`cyano.poweradvantage.api` package remains available. The port keeps basePower's
registry names and saved machine data and maps the older Power Advantage names.
The test record distinguishes state preservation, real operation and changes
made by Minecraft itself.

The upgrade path prepares stored items before vanilla flattening and converts
entities individually, so a mod entity cannot prevent a nearby pet's UUID and
ownership from being migrated. Old vanilla registry names are remapped explicitly;
unknown mod mappings still require their own compatible mod rather than being
silently discarded.
Legacy Forge swimming speed, name-tag distance, gravity and player reach use
their native registry names without discarding saved values or modifiers.

The port uses native dimension registry keys, block-entity factories and tickers,
capabilities and menus. Config-dependent material aliases are additive server
data-pack tags. The shared pre-flattening migration hook remains necessary;
its presence is not proof that an upgrade is safe.

The [legacy migration guide](docs/1.13/MIGRATION.md) describes the earlier protocols.
The [Advantage Works 1.20.1 guide](test-rig/NATIVE1201.md) lists the automated checks.

The historical 1.10 handbook remains on the [1.10.2 branch](https://github.com/MinecraftModDevelopmentMods/PowerAdvantage/blob/master-1.10.2/docs/1.10/README.md).
