# Marked Native Client Integration

This test-only helper is separate from the worldless resource probe and from
the packaged harness. It loads a copied world through Minecraft 1.21.1's real
`Minecraft.createWorldOpenFlows().openWorld(String, Runnable)` API. It never
constructs a fake level/player, calls machine ticks, replaces entity methods,
or drives Windows UI. Backup/datapack/experimental confirmation screens are
not accepted or bypassed: an aborted or timed-out load fails qualification.

The native source inspection used `WorldOpenFlows`, `Minecraft`,
`MultiPlayerGameMode` and `PauseScreen` in the generated NeoForge 21.1.247
sources jar. `gameMode.useItemOn` sends `ServerboundUseItemOnPacket` through the
real connection. MachineGUIRegistry's real menu factory and open-menu packet
must establish matching positive client/server container IDs and a real screen.

## Evidence And Scope

The only approved source is the **closed** F-operations
`test-rig/build/neo1211-operations-f/AdvantageWorksMigration` copy. Preparation
requires its passing report, obtains its native session lock, fingerprints all
world data except the lock itself, copies into a fresh `build/client-integration`
directory, and rechecks source hashes. It never uses a live endurance or original
fixture world. Each phase is explicitly marked; previous receipts are retained.

The first process places one isolated tank/platform on the disposable copy,
seeds 1,375 mB of water and typed persistent witnesses on the real server thread,
and teleports the genuine joined player within normal interaction range. No
client tank or menu is fabricated. The helper then issues a real right-click,
checks the actual screen, menu IDs/type, server validity and synchronized client
tank reading, closes the menu, and uses the normal disconnect/save-and-quit path.

The reload process does **not** reseed the tank. Before opening its real menu,
it checks the cold-loaded typed tank compound against the initial receipt and
requires the typed persistent witnesses. Its process ID must differ. Both
phases verify recorded native runtime jars, staged mod jars, and production
class/resource hashes before and after the flow. This is dev-classpath proof,
not a claim that the player jar itself was the loaded Power code.

The disposable profile disables first-run accessibility onboarding and pause on
lost focus through normal local options. No global Minecraft settings change.
There is a 180-second in-process timeout. A FAIL or missing receipt is never PASS.

## Commands

Coordinate the Power Gradle cache before either invocation. From the Power root:

This probe is a Gradle-only diagnostic with explicit world-selection properties.
It is not an IDE launch, and Eclipse synchronization must not prepare its world.

```powershell
$env:JAVA_HOME = 'C:/Users/johnb/.jdks/temurin-21.0.7+6'
$env:GRADLE_USER_HOME = 'D:/PowerAdvantage/.gradle-power1211neo'
./gradlew.bat runClientIntegration `
  -PclientIntegrationDirectory=build/client-integration/<fresh-id> `
  -PclientIntegrationSourceWorld=test-rig/build/neo1211-operations-f/AdvantageWorksMigration `
  -PclientIntegrationPhase=initial --console=plain
```

For a second **full client process**, keep the same copied world and directory:

```powershell
./gradlew.bat runClientIntegration `
  -PclientIntegrationDirectory=build/client-integration/native-f-04 `
  -PclientIntegrationPhase=reload --console=plain
```

Both `native-f-04/client-integration-initial.json` (PID 44320) and
`native-f-04/client-integration-reload.json` (PID 68160) passed. The second full
client process cold-loaded the identical typed tank compound containing 1,375 mB
of water, established matching positive client/server menu ID 1, and completed
normal save-and-quit. Earlier compile/staging failures and the terminated
pre-world onboarding attempt are retained in
`build/client-integration-*.log`; none qualifies as gameplay proof.
