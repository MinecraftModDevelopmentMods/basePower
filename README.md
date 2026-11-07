# basePower

**Minecraft 1.17.1, version 2.4.9.117011. Local test candidate.**

See the [1.17 port record](docs/1.17/PORT-STATUS.md) for this candidate's
validation status. The retained 1.16 and earlier reports are historical evidence,
not passes for 1.17. Manual testing follows the current automated qualification.

basePower continues DrCyano's Power Advantage. It provides shared power,
fluids and item transport for baseSteam and baseElectric.

**Back up the entire world before upgrading. Test only disposable copies.**
The port retains existing registry names and legacy migration code. Upgrade tests
use disposable original and maintained saves, including connected machines.
Check every other mod's target-version support too.
The accepted [1.14 results](docs/1.14/BROADER-QUALIFICATION.md) are historical
evidence, not qualification of this port or a whole modpack.

Recipe-book discovery now follows relevant ingredients. See the [all-Advantage material checklist and automated discovery tests](docs/1.13/RECIPE-DISCOVERY.md). To regenerate the checked-in unlock JSON after changing recipes, run `node tools/generate-recipe-unlocks.js`; `check` rejects missing or stale ingredient criteria.

The [manufacturing progression audit](docs/1.13/MANUFACTURING-PROGRESSION.md) traces survival parts from raw resources, including configured material aliases, processing, loot-only parts and automated restart checks.

## Building Minecraft 1.17.1

The `master-1.17.1` build uses Forge 37.1.1, official `1.17.1` mappings,
MCP `20210706.113038`, ForgeGradle 7.0.34 and Gradle 9.6.1.
Run Gradle with Java 17 and compile and run production code with Java 16. Mavenizer also
needs Java 25. CI pins these JDKs and Java 8 for the legacy renamer. Pass the installed homes using
`-Porg.gradle.java.installations.paths=<java8>,<java16>,<java17>,<java25>` and disable
automatic discovery and downloads for a reproducibility check.

Build the pinned Base Metals `3.1.1.117011` and OreSpawn `4.1.0.117011` deobf jars first.
The Base Metals startup fix is local while we test this port. The source pins
and checksums are in `gradle.properties`.

Then run from the repository root:

```text
gradlew.bat clean check build javadoc verifyReleaseDependencies verifyReleaseArtifacts writeReleaseChecksums verifyEclipseProductionClasspath
```

The default dependency jars are:

```text
D:\BaseMetals\BaseMetals 117\BaseMetals\build\libs-dev\BaseMetals-3.1.1.117011-deobf.jar
D:\MinecraftMineralogy\OS117\OreSpawn\build\libs-dev\OreSpawn-4.1.0.117011-deobf.jar
```

Other layouts can pass `-PbaseMetalsDeobfJar=<path>` and `-PoreSpawnDeobfJar=<path>`.
Dependencies are never embedded in PowerAdvantage artifacts.

Dependency checksums and source commits are recorded in `gradle.properties`.
Unknown artifacts fail the dependency audit. Archives use reproducible ordering,
LF text and no empty directories, so fresh checkouts produce the same bytes.

Release jars are written to `build/libs`; `deobfJar` writes the sibling-development jar to `build/libs-dev`.

For Eclipse, import this repository as an existing Gradle project and run:

```text
gradlew.bat cleanEclipse verifyEclipseProductionClasspath
```

Refresh the project in Eclipse, allow its Java build to finish, and launch the generated `runClient.launch`. Eclipse launches discover the mod from `bin/main`; command-line Gradle launches use `build/forge-run-mod-classes/main`. The Eclipse audit checks that discovery points at the same production output Eclipse compiles into, preventing a client that starts without PowerAdvantage.

Eclipse copies production resources from `build/resources/main`, not directly from `src/main/resources`. This prevents its builder from overwriting the expanded `mods.toml` with raw `${version}` placeholders. After updating this setup, use **Gradle > Refresh Gradle Project**, then **Project > Clean** before relaunching. After editing resources or version properties, run `processResources` or refresh the Gradle project before the Eclipse build. See the [metadata regression notes](docs/1.13/ECLIPSE-METADATA.md).

To prepare a combined client, build the baseSteam and baseElectric `2.2.10.117011` deobf jars,
run `gradlew.bat prepareCombinedEclipseLaunch`, refresh Eclipse, then launch
`runClientAllAdvantage.launch`. Inputs must match
`test-rig/profiles/native117/dev-core.json`; alternative layouts can pass
`-PsteamAdvantageDeobfJar=<path>` and `-PelectricAdvantageDeobfJar=<path>`.
The ordinary `runClient.launch` remains Power-only. Both use the `run` directory;
preparing a launch does not copy, open or qualify a save.

`check` and Eclipse preparation also run `verifyDevelopmentRuntimeArchives`,
which reads every development runtime jar entry and checks its size and CRC.
This catches damaged cached dependencies before the next test launch.

Client-resource and recipe-book checks must use the exact 1.17 candidate.
See the [client test instructions](test-rig/client-resources/README.md).
Manual visual and interaction acceptance remains separate. The retained Java 8
Eclipse compiler script is historical tooling, not a 1.17 verification command.
Keep Eclipse's access-restriction checks enabled and use Java 16 for its project
JRE and Minecraft launches.
The [native Eclipse compiler check](tools/verification/native117/README.md)
compiles fresh production and test outputs, then runs the complete unit-test
inventory on Java 16 without replacing the built mod jars.

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
Use the [native Advantage Works guide](test-rig/NATIVE117.md) for this checkout.
The [1.17 status](docs/1.17/PORT-STATUS.md) tracks this port's qualification.

The historical 1.10 handbook remains on the [1.10.2 branch](https://github.com/MinecraftModDevelopmentMods/PowerAdvantage/blob/master-1.10.2/docs/1.10/README.md).
