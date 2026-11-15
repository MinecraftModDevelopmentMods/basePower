# Independent NeoForge 1.21.1 Controls

This standalone ModDevGradle 2.0.148 project uses NeoForge 21.1.265, Java 21
and the repository's Gradle 9.8.0 wrapper. It does not depend on Advantage,
Base Metals, MMDLib or OreSpawn. Its unit-test loader requires exactly
Minecraft, NeoForge and `native1211neo_platform_controls`.

From the Power checkout, after coordinating use of its Gradle cache:

```powershell
$env:JAVA_HOME = 'C:/Users/johnb/.jdks/temurin-21.0.7+6'
$env:GRADLE_USER_HOME = 'D:/PowerAdvantage/.gradle-power1211neo'
./gradlew.bat -p test-rig/platform-controls/neo1211 test -PplatformControlRunId=<new-id> --console=plain
```

The build hashes the two historical control sources before generating narrowly
adapted Neo-specific copies under `build/generated-controls`. Historical source
files and assertions are unchanged. Only package/bootstrap APIs, the NeoForge
version/policy fields and additional provenance checks differ.

Before either control runs, loaded Entity, EntityType, LeashFenceKnotEntity and
Wolf class resources must match the native development-jar SHA-256 manifest.
The negative control rejects all three historical Forge 52 hashes and a
tampered hash. No native entity methods are stubbed, replaced or ticked. No
Minecraft server starts; the contexts are uninitialized ServerLevel allocations
used only for native load/save and vanilla DFU.

Fresh evidence is retained under `build/control-evidence/<new-id>`. The leash
contract is deterministic and may be bound into the harness only after the
controls pass. Wolf receipts distinguish ordinary same-version native
load/save from the two legacy vanilla DFU routes (data versions 512 and 1343).
These unit controls do not qualify a running Minecraft server or player world.
