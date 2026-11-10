# Advantage Works on Forge 50

Run from this test-rig directory with a Java 21 `JAVA_HOME`. The parent
Mavenizer fixture supplies official names for compilation and runtime. The
harness manifest records the ordinary Power compile-input SHA-256, and both
launchers check it against the runtime pin. No Renamer is used. A run is
evidence for its specific profile and checks, not permission to publish.

```powershell
../gradlew.bat jar runnerJar test verifyHarnessIsolation verifyForge50UnitBootstrap
$power = (Resolve-Path ../build/libs/basePower-2.4.13.120061.jar).Path
$powerSha = (Get-FileHash -LiteralPath $power).Hash
$harness = (Resolve-Path build/gradle/libs/AdvantageWorksTestHarness-1.1.4.120061.jar).Path
$harnessSha = (Get-FileHash -LiteralPath $harness).Hash
```

Use `-PpowerAdvantageReleaseJar=<path>` for an immutable copy of the pinned
jar. To try a different Power build, also pass its
`-PworksExploratoryPowerSha256=<hash>`; that run is exploratory.

The plain JUnit agent supplies an empty Forge loader fixture and uses Forge's
native capability and registry machinery. It is never used in a real server.
The root test project already supplies the same agent and support sources.

## Live Power and Base Metals

Set `$python` to a Python 3.11+ executable with `migration/requirements.txt`,
and `$seed` to the official Forge 50.2.0 runtime-server directory. Seal the
read-only seed once, then use a fresh output path and unused localhost port.

```powershell
$inventory = "build/native1206-code-inventory.json"
& $python migration/native1206.py seal --seed $seed --inventory $inventory
$seedSha = (Get-FileHash -LiteralPath $inventory).Hash
& $python migration/native1206.py interop --seed $seed --inventory $inventory --inventory-sha256 $seedSha --java "$env:JAVA_HOME/bin/java.exe" --power $power --power-sha256 $powerSha --harness $harness --harness-sha256 $harnessSha --port 25837 --output build/native1206-interop-local
```

The live profile uses the local Base Metals shared-hook candidate and pinned
OreSpawn. It requires all original Forge Energy, tank, conveyor and callback
checks plus native Base Metals shared crushing before and after restart.
Unplaced callback checks do not prove physical chunk unload. The server run
does not establish client acceptance or migration qualification.

Add `--oil-buckets` to exercise both real oil buckets through native survival
world use and tank interaction. The offline actor records a GUI fallback
without attempting to send a packet to a client that does not exist.

## Combined Profiles

`native1206.py profile --name <name>` copies the existing station and worldgen
expectations into a new pinned profile under `build`. Pass the same Power and
harness arguments, plus `--steam`, `--electric` and their SHA-256 pins.
Mineralogy profiles also need `--mineralogy` and `--mineralogy-sha256`. Pass
`-PworksProfileFile`, `-PworksServerSeed`, `-PworksServerInventory`, and
`-PworksServerInventorySha256` to the matching Gradle task.
`verifyWorksNative1206Inputs` checks custody without opening a world.

The Python broad and endurance runners consume the same frozen family
profile. Use separate fresh output paths and non-overlapping localhost ports.

```powershell
$common = @('--profile', $profile, '--seed', $seed, '--inventory', $inventory, '--inventory-sha256', $seedSha, '--java', "$env:JAVA_HOME/bin/java.exe")
& $python migration/native1206_broad.py @common --port 25910 --output build/native1206-broad-local
& $python migration/native1206_endurance.py @common --port 25920 --seconds 1800 --output build/native1206-endurance-local
```

Broad defaults to both shared and decoupled profiles, on consecutive ports.
Endurance is fresh by default. Add `--retained` and
`--retained-receipt-sha256` for a passing upgrade on the same exact family
profile and inventory; this also uses the following port. A shorter
whole-minute run reports `SMOKE_PASS_NOT_ENDURANCE`.
Creating `hold-endurance.request` in an active endurance output directory
requests normal shutdown at the next safe boundary and records `HELD`.

## Strict Upgrade Lanes

Use `migration/native1206_upgrade.py` with the materialized family profile,
official seed, sealed inventory, Java 21, and a new output directory. For a
modern upgrade, use `--source` pointing at a stopped 1.20.1 runtime with a
passing modern upgrade receipt, pinned by `--receipt-sha256`. For a direct
historical upgrade, use `--source` pointing at the original fixture and
`--legacy-reference` pointing at its independent 1.20.1 run. The driver
requires the historical source's full fingerprint to match that receipt.

The upgrade gates compare every machine witness, inspect native on-disk
block and tile data, verify the turbine, restart without reseeding, and
exercise the conveyor, crusher, and furnace on a further copy. A log error
fails the run even when the captured state matches. Other historical
`native1201_*.py` drivers still target Forge 47. Forge 50 does not natively
retain opaque `ForgeData` on vanilla block entities, so connected migration
is not replaced with a permissive comparison.
