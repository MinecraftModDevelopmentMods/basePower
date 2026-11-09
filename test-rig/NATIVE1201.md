# Advantage Works on Minecraft 1.20.1

Use Java 17, Forge 47.4.10 and the exact jars listed in
[`profiles/native1201/`](profiles/native1201/). When testing upgrades with
Base Metals installed, use the pinned build with the shared migration hook.
An older Base Metals jar can convert the chunk before basePower can read it.

From the basePower checkout, build all three mods, then run:

```powershell
.\gradlew.bat -p test-rig check build runnerJar verifyHarnessIsolation verifyWorksNative1201Inputs
.\gradlew.bat prepareCombinedEclipseLaunch
```

Refresh the Gradle project in Eclipse. Use `runClientAllAdvantage.launch`
for all three mods, or `runClient.launch` for basePower alone. Neither command
opens a world. Make a separate copy before testing a world upgrade.

To walk around a disposable Works server, run
`gradlew.bat -p test-rig runWorksPackagedCurrent` and join localhost.
Use `stop` in the server console to save and exit. Earlier worlds and
results are left alone.

## Automated checks

The packaged and decoupled profiles cover the same 21 stations. The
world-generation profiles check fallback ores, Mineralogy coexistence and
the import of existing OreSpawn settings.

The Python runners in `migration/` take an installed Forge server seed, a
sealed inventory of its files, the inventory SHA-256 and a hash-pinned jar
profile. Use `native1201.py --help` for the inventory command. Keep source
saves and receipts outside the output directories.

- `native1201_legacy_world.py` and `native1201_modern_upgrade.py` compare saved
  and live state before operation and after a full process restart.
- `native1201_broad.py` commissions every station. `native1201_networks.py`
  checks partial unload, pipe reconnects, extended drills and capabilities.
- `native1201_actors.py` and `native1201_respawn.py` check saved players,
  entities, dimensions and server-side death and respawn.
- `native1201_recipes.py` checks ingredients, unlocks and survival progression
  in all three modes. Its exports also feed the
  [graphical client check](client-resources/README.md).
- `native1201_endurance.py` defaults to 30 measured minutes. Pass
  `--retained <migration run>` to exercise a migrated world alongside the
  fresh one. `audit_native1201_endurance.py` independently checks the ledgers.

Server results do not certify textures, machine screen layouts or real client
login. Keep those results separate from the graphical and manual checks.
