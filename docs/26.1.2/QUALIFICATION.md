# Minecraft 26.1.2 Test Results

The current basePower, baseSteam and baseElectric candidates have completed
their automated checks and manual playtesting. Publication is being prepared
separately; a passing test record does not mean a release is already public.

## Tested Versions

| Mod | Version | Player Jar SHA-256 |
| --- | --- | --- |
| basePower | 2.4.16.2601021 | `23ABCC1FEFDAA6988BF709437EB165C8948C757E773ADAB738092EB93CA8D2B0` |
| baseSteam | 2.2.17.2601021 | `4A4E7D8C3704DCF17F46EBB55A7A908A6E1B547FCAB93E3B6782EE32E49301CB` |
| baseElectric | 2.2.17.2601021 | `E2929ED56B4CEE1AD50B6B0D2BF1AF000AA15F2C9D66E5DFB3679B53FBDED9FC` |

These checks used Forge 64.1.5, Temurin 25.0.3+9, the local Base Metals
3.1.2.2601021 migration-hook build, and published OreSpawn 4.1.1.2601021
(CurseForge file 9572926). Base Metals and OreSpawn were installed throughout.
The Base Metals hook build is still local; an older public jar is not a
substitute for this test kit.

OreSpawn's player jar was downloaded anonymously and verified as
`810284ECB32D5249381F246498CEFA57E70FFF7CCC40110F799FC276CBC42BF7`,
from source commit `101fd12b598934c353e417c57edc5c2bbefd5f53`.

## Completed Checks

- Release, dependency, Java bytecode, resource, checksum and Eclipse classpath audits for all three mods.
- Independent clean-cache builds in paths containing spaces, with matching distributable jars.
- Power's 357 unit tests and fresh Eclipse compiler run; Steam's 276 tests and Eclipse run; Electric's 117 unit tests, 28 native checks and Eclipse run.
- The 384-test harness, 267 Python adapter tests and 24 worldless client-probe tests.
- All 21 walkable works bays, 12 drill witnesses, capability/automation checks and 132 finite machine jobs, including full process restarts and stopped-save comparisons.
- Harvesting, chunk lifecycle, physical partial-network unloading, bridge reconnection and extended moving-drill scenarios.
- Recipe discovery and manufacturing progression in all three recipe modes, with rubber enabled and disabled; six oil lanes across two cold restarts.
- Fresh generation, legacy imports, and original 1.10 plus released 1.10/1.12 generation profiles in new chunks. Saved choices remain authoritative, and replacement rules do not become duplicate active placements.
- Eight operating machine-upgrade routes: original Cyano 1.10, released base* 1.10 and 1.12, and retained 1.19.4, 1.20.1, 1.20.6, 1.21.1 and 1.21.11 saves.
- Nine actor/dimension routes, including Nether, End and chained upgrades, with stopped entity/player data checks. Both actual Power-first and Base-Metals-first migration-hook orders were exercised separately.
- A real graphical client: 39 machine screens, 351 caption checks, fluid gauges, recipe-book cells, model/resource checks, reloads and 1,247 fenced GPU frames, including negative controls.
- Two measured 30-minute endurance worlds: one fresh and one upgraded from the original 1.10 fixture. Both saved, stopped, restarted and resumed processing. Independent replay checked all 66 checkpoints and raw stopped saves; minimum interval throughput remained above 19.5 ticks per second.

The resumed run did not require another Advantage gameplay repair. The changes
were dependency pins, the required OreSpawn minimum, and test/setup corrections.
Failed attempts remain separate from the new passing results.

## Limits And Manual Checks

Six dimension fixtures retain `PASS_WITH_NATIVE_DIFFERENCE`, not a rewritten
strict pass. Their only differences are independently controlled native
Minecraft wolf health/max-health scaling. Original strict comparisons remain
available; all other fields match.

Three Mineralogy-only oil assertions are explicitly skipped because that
optional mod is not in the six-jar test kit. This does not qualify an entire
modpack or unrelated mods' saved data.

Check the normal play experience, particularly turbine orientation/spin,
weapon handling, and machines in a copy of a real world. Automated geometry
checks do not replace the remaining world-dependent turbine visual check.

## Local Evidence

The immutable family profile is `native2612-local-candidate-12.json`, SHA-256
`C42AAE0ED4B1E56854991E45FD67B495E1CA16AC1C639E894FD190414D2E3CC8`.
The local evidence pack is under
`D:/PowerAdvantage/_verification/base-2612-port-20261112/resume-orespawn411-20261112/`.
It contains original logs, failed setup attempts, source/input hashes, raw
save comparisons, client frames, the genuine pre-run endurance seal and
independent stopped-save replay. Older candidate results were not relabelled.

Manual testing was accepted on 12 November 2026. Source delivery and publication
follow the dependency order: Base Metals, basePower, baseSteam, then baseElectric.
The exact test jars and original automated evidence remain unchanged.
