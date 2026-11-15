# basePower

**Minecraft 1.21.1 / NeoForge 21.1.265, version 2.4.19.121012.**

basePower continues DrCyano's Power Advantage. It provides shared power,
fluid handling and item transport for baseSteam and baseElectric. The historical
`cyano.poweradvantage.api` package and existing registry identities remain in use.

This is the native NeoForge edition. Do not mix it with Forge builds of the same
mods. The existing machines, recipes, world identities and legacy API remain in
place. Keep backups when upgrading worlds; a successful build alone does not
prove that an entire modpack can be upgraded safely.

## Required Dependencies

All three dependencies are required on both client and server:

| Dependency | Input For This Port | Status |
| --- | --- | --- |
| Base Metals | `zone.moddev.mc.basemetals:BaseMetals:4.0.1.121012+Neo` | Published release with the reviewed shared migration hook |
| MMDLib | `zone.moddev.mc.lib:mmdlib:2.0.2.121012+Neo` | Published input; SHA-256 recorded in `gradle.properties` |
| OreSpawn | `zone.moddev.mc.orespawn:OreSpawn:4.1.1.121012` | Published input; SHA-256 recorded in `gradle.properties` |

Base Metals **4.0.0.121012+Neo is not a substitute**. It lacks the migration
extension used to preserve old Advantage machine and player data.

The matching expansion versions are baseSteam and baseElectric
**2.2.20.121012**. They remain separate mod jars; dependencies are not embedded.
MMDLib supplies the shared crushing recipe contract, not replacement machinery.
Recipe removal remains authoritative and drill mining keeps normal block drops.

For recipe discovery, collect vanilla copper ingots as well as Base Metals steel,
lead, brass and tin. The published Base Metals copper tags use vanilla copper;
an old Base Metals copper item alone may not trigger that ingredient's unlock.

## Local Build

Use Java 21, ModDevGradle **2.0.148** and the SHA-pinned Gradle **9.8.0** wrapper.
Java 8, Java 25, ForgeGradle, NeoGradle and the old Mavenizer are not part of this
native build. Dependencies resolve from MMD Maven and are checked against their
published SHA-256 values. A verified local copy may be selected with
`-PbaseMetalsJar=<path>`; it must have the same hash.

```powershell
$env:JAVA_HOME='C:/Users/johnb/.jdks/temurin-21.0.7+6'
$env:GRADLE_USER_HOME='D:/PowerAdvantage/.gradle-power1211neo'
./gradlew.bat check build javadoc verifyReleaseDependencies verifyReleaseArtifacts `
  writeReleaseChecksums
```

Do not start a second Power Gradle process while that cache is in use. This
command is documentation, not evidence that the current candidate passed it.
The local artifact checks bind all three dependency hashes to `gradle.properties`.
Keep failed receipts, disposable worlds and frozen inputs; avoid routine `clean` during a
qualification campaign. Outputs are under `build/libs`.

## Eclipse And Client

Import this existing checkout as a Gradle project with Java 21. Use the Gradle
Tasks view for native `runClient` and `runServer`; old Forge `.launch` files and
their launcher helper are not applicable. `runClientAllAdvantage` uses the two
separate expansion jars, selected by `-PsteamAdvantageDeobfJar=<jar>` and
`-PelectricAdvantageDeobfJar=<jar>`. Always select a fresh disposable run directory
with `-PpowerAdvantageRunDirectory=<new directory>` when testing.

Run `verifyEclipseProductionClasspath verifyEclipseEcj` to check the generated
project with Eclipse's own compiler, including separate production/test outputs
and deliberately broken isolation controls. The checks also reject duplicate
classpath entries and exercise three consecutive classpath merges, as happens
during repeated Buildship refreshes. Use a new `-PeclipseEcjRunId=<id>`
for each run so earlier diagnostics are retained. The combined Gradle launch
stages the expansions as separate mods; tests are not part of the player jars.
The audits also run NeoForge's actual IDE synchronization tasks without opting
into a disposable test world. Copied-world integration tests run only through
their explicitly configured Gradle command, not during an Eclipse refresh.

The [client-resource probe](test-rig/client-resources/README.md) retains independent
full-gauge sprite comparisons, caption pixels and negative controls. Its Java
compilation and static preservation checks are not rendered-client acceptance.
Native recipe-book fixtures require a server registry-handshake export; historical
Forge registry snapshots cannot stand in for it.

## Hosted CI Bootstrap

CI and CodeQL use native ModDevGradle with one Java 21 toolchain. They read
[the three-dependency lock](.github/native-dependencies.json), verify approved
source ancestry where recorded, download exact public jars, check SHA-256 and
NeoForge metadata, and require the BM migration-hook entry. The build consumes
those verified files instead of resolving replacement copies independently.

The lock records the merged Base Metals source and the exact public Maven
artifacts. `HOSTED_INPUTS_READY` means those dependency inputs can be reproduced;
it is not gameplay or release approval. The offline guard tests are:

```powershell
./.github/test-native-dependencies.ps1
./.github/verify-native-dependencies.ps1 -ValidateOnly
```

Both commands must pass before hosted builds. Heavy
packaged gameplay and historic-world qualification stay local. Release-tag
validation additionally rejects `release_player_sha256=UNQUALIFIED`; publication
still requires explicit approval and the guarded default-branch release workflow.

## Saved Worlds

Back up the entire save and work only on disposable copies. Keeping names, NBT,
inventory data and migration hooks is necessary, not proof that a route is safe.
The native port translates engine-owned `ForgeData` into `NeoForgeData` while
preserving its payload, including nested block-entity item data. Attribute remaps,
fluid components, locks and capability lifetimes require the same frozen candidate
as the packaged and restart tests. Unknown third-party registry mappings require
their owning compatible mod rather than silent deletion.

The older [migration guide](docs/1.13/MIGRATION.md) and
[Forge 1.21.1 results](docs/1.21.1/TEST-RESULTS.md) are historical context only.
They are not acceptance receipts for this checkout or an entire modpack.

See the [NeoForge test and manual-check notes](docs/1.21.1-neoforge/TEST-RESULTS.md)
for this candidate. Heavy tests run locally; no publication or manual acceptance
is implied by a successful build.
