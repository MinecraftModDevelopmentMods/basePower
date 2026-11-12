# basePower

**Minecraft 26.1.2 (Forge), version 2.4.16.2601021, in development.**

This branch is a local port, not a released or qualified build. The
[26.1.2 port record](docs/26.1.2/PORT-STATUS.md) separates completed checks from
work still to do. The accepted [1.21.11 qualification](docs/1.21.11/QUALIFICATION.md)
is retained as historical evidence; it does not qualify this port.

The combined Eclipse launch will load matching baseSteam and baseElectric
candidates once their exact jars and development profile have been prepared.

basePower continues DrCyano's Power Advantage. It provides shared power,
fluids and item transport for baseSteam and baseElectric.

## Testing On 26.1.2

- Use MMD OreSpawn `4.1.0.2601021` on both client and server.
- This candidate requires the local Base Metals `3.1.2.2601021` migration-hook
  build on both client and server. It has not been published; the public
  `3.1.1` jar is not a substitute for the upgrade tests.
- baseSteam and baseElectric are separate mods. Use only their matching
  26.1.2 builds. Additional Loot Tables is not needed for the built-in loot.

**Back up the entire world before upgrading. Test only disposable copies.**
The port keeps existing registry names and machine data. The upgrade checks
will use disposable copies of original Advantage and released base* saves,
including connected machines, and check them after a full restart. Check that
the other mods in your world support 26.1.2 before attempting an upgrade.
The accepted [1.14 results](docs/1.14/BROADER-QUALIFICATION.md) are historical
evidence, not qualification of this port or a whole modpack.

Recipe-book discovery now follows relevant ingredients. See the [all-Advantage material checklist and automated discovery tests](docs/1.13/RECIPE-DISCOVERY.md). To regenerate the checked-in unlock JSON after changing recipes, run `node tools/generate-recipe-unlocks.js`; `check` rejects missing or stale ingredient criteria.

The [manufacturing progression audit](docs/1.13/MANUFACTURING-PROGRESSION.md) traces survival parts from raw resources, including configured material aliases, processing, loot-only parts and automated restart checks.

## Building Minecraft 26.1.2

The build uses Forge 64.1.5, official `26.1.2` names,
MCP `20260409.101008`, ForgeGradle 7.0.34 and Gradle 9.6.1.
Use Java `25.0.3+9` for Gradle, compilation, Mavenizer and Minecraft. Pass its home using
`-Porg.gradle.java.installations.paths=<java25>` and disable
automatic discovery and downloads for a reproducibility check.

Use the pinned Base Metals `3.1.2.2601021` and OreSpawn `4.1.0.2601021` jars.
The source pins and checksums are in `gradle.properties`.
Forge 64 also needs the Mavenizer compatibility jar in the pinned OreSpawn
checkout's `ci-fixtures/tools/` directory. This is the same checksum-verified
source preparation tool used by OreSpawn; its manifest makes no source changes
for this target. It is not part of the mod or game runtime.
Set `-Pforge64MavenizerJar=<path>` when that checkout is in another location.
Use the pinned Base Metals 3.1.2 build for both new worlds and upgrades. It lets
both mods preserve old items and machines before Minecraft converts the
chunk. An older Base Metals jar is not a substitute for this upgrade path.

Then run from the repository root:

```text
gradlew.bat clean check build javadoc genEclipseRuns eclipse isolateEclipseProductionRuns verifyReleaseDependencies verifyReleaseArtifacts writeReleaseChecksums verifyEclipseProductionClasspath
```

The default dependency jars are:

```text
D:\BaseMetals\BaseMetals 2612\BaseMetals\build\libs\BaseMetals-3.1.2.2601021.jar
D:\MinecraftMineralogy\OS2612\OreSpawn\build\libs\OreSpawn-4.1.0.2601021.jar
```

Other layouts can pass `-PbaseMetalsDeobfJar=<path>` and `-PoreSpawnDeobfJar=<path>`.
Dependencies are never embedded in PowerAdvantage artifacts.

Dependency checksums and source commits are recorded in `gradle.properties`.
Unknown artifacts fail the dependency audit. The release audit also rejects a
player jar that differs from the frozen test kit. Hosted CI requires the Base
Metals pin to be on its upstream `master-26.1.2` branch; a locally tested pin
does not qualify a hosted build.

Release jars are written to `build/libs`. Forge 64 uses the same mod jar for play
and development; `deobfJar` is kept as a task alias for older build scripts.

For Eclipse, import this repository as an existing Gradle project and run:

```text
gradlew.bat cleanEclipse verifyEclipseProductionClasspath
```

Refresh the project in Eclipse, allow its Java build to finish, and launch the generated `runClient.launch`. Eclipse launches discover the mod from `bin/main`; command-line Gradle launches use `build/forge-run-mod-classes/main`. The Eclipse audit checks that discovery points at the same production output Eclipse compiles into, preventing a client that starts without PowerAdvantage.

Eclipse copies production resources from `build/resources/main`, not directly from `src/main/resources`. This prevents its builder from overwriting the expanded `mods.toml` with raw `${version}` placeholders. After updating this setup, use **Gradle > Refresh Gradle Project**, then **Project > Clean** before relaunching. After editing resources or version properties, run `processResources` or refresh the Gradle project before the Eclipse build. See the [metadata regression notes](docs/1.13/ECLIPSE-METADATA.md).

To prepare a combined client, build the baseSteam and baseElectric `2.2.17.2601021` jars,
run `gradlew.bat prepareCombinedEclipseLaunch`, refresh Eclipse, then launch
`runClientAllAdvantage.launch`. Inputs must match
`test-rig/profiles/native2612/dev-core.json`; alternative layouts can pass
`-PsteamAdvantageDeobfJar=<path>` and `-PelectricAdvantageDeobfJar=<path>`.
The ordinary `runClient.launch` remains Power-only. Both use the `run` directory;
preparing a launch does not copy, open or qualify a save.

`check` and the Eclipse audit verify that Java files are in the directories
matching their packages. They also read every development runtime jar entry
and check its size and CRC to catch damaged cached dependencies.

Keep Eclipse's access-restriction checks enabled and use Java 25 for its project
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
The [Advantage Works 1.20.1 guide](test-rig/NATIVE1201.md) describes the prior
qualification and the checks carried forward into later ports.

The historical 1.10 handbook remains on the [1.10.2 branch](https://github.com/MinecraftModDevelopmentMods/PowerAdvantage/blob/master-1.10.2/docs/1.10/README.md).
