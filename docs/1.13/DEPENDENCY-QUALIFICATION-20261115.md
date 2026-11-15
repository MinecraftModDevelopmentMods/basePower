# 1.13.2 Dependency Update Checks

## Candidate

This check is for basePower `2.4.7.113021`, baseSteam `2.2.8.113021` and
baseElectric `2.2.8.113021`. It does not replace the records for earlier jars.
Power needs a new patch number because `2.4.6.113021` is already public.

The required release inputs are public Base Metals `3.0.3.113021` and OreSpawn
`4.1.7.113021`. Optional Mineralogy checks use public `6.0.2.113021`.
The downloaded Base Metals and OreSpawn jars match the checked inputs.
No Base Metals source changes are included in this update.

| Release jar | SHA-256 |
| --- | --- |
| `basePower-2.4.7.113021.jar` | `E7140E83D55D9FF71F63CD24FCBC09A6F95D24F800C93E83BD6CD60F6A63C74C` |
| `baseSteam-2.2.8.113021.jar` | `2A7C74108B5C2E426C00E27748DA1DC07F52156BA667984244641AC73E524C7F` |
| `baseElectric-2.2.8.113021.jar` | `96227C99DB329721E43067D4A0D7F8F51738747F600A087917E68E2584A80567` |

## Automated Checks

The local automated qualification completed on 15 November 2026. No new gameplay
repairs were needed for these candidate jars.

| Check | Result |
| --- | --- |
| All three builds, unit tests, Javadocs, dependencies, archive and Eclipse audits | PASS |
| Independent rebuild with build caching disabled | PASS: all twelve release, deobf, sources and Javadoc hashes match |
| Actual Eclipse JDT compilation and combined native client | PASS: 94 items, 905 block states, 87 creative entries |
| All 39 machine GUI layouts and captions | PASS: 351 caption cases; 355 deliberately broken controls rejected |
| Measured machine operation and exact saved-state cold restart | PASS: 132 jobs and 132 restart comparisons |
| Original Cyano 1.10, released base* 1.10 and released base* 1.12 upgrades | PASS: saved-state conversion, continued operation and full restart |
| Both Base Metals/Power transformer orders, with player/entity fixtures | PASS |
| Complete Works, with and without Base Metals | PASS: server assertions before and after restart; manual procedures remain separate |
| Survival harvest tools, drops and block properties | PASS before and after restart |
| Recipes, ingredient unlocks and manufacturing progression | PASS in all three recipe modes, with and without rubber availability |
| Water and oil bucket/drain/pipe/tank lanes | PASS: exact identity and volume through two full restarts |
| First-tick redstone interlocks | PASS: nine naturally earned mid-work seeds and sixteen real reload ticks |
| Whole-chunk unload/reload and six-facing moving drills | PASS in fresh and migrated-copy worlds |
| Extended drills, separate endpoint unloads and reload orders | PASS in fresh and migrated-copy worlds, including blocked output, cold restart and redstone recovery |
| Pipe-only network interruption | PASS: three genuine bridge unload/reload cycles with resumed processing |
| Thirty-minute repeat-batch endurance | PASS in both fresh and migrated-copy worlds, followed by cold restart and resumed processing |
| Independent endurance endpoint replay | PASS: 66 raw checkpoints; four missing-evidence controls rejected |
| OreSpawn providers, biome samples and persisted user choices | PASS: Advantage, Mineralogy and legacy-import profiles, each across restart |
| Mineralogy oil processing and Forge Energy converter profiles | PASS across restart |

The mod suites passed 246 tests, and the harness passed 72 tests.
The Python suite ran 391 tests with one documented transport-protocol skip.
That skip is not counted as a pass. Forge 1.13 uses the explicitly flushed
checkpoint protocol exercised by the native runs.

The harness isolation check now reads Power's version from `gradle.properties`
instead of keeping its own jar version. Old profile assertions and dependency
paths were refreshed without changing the machine or GUI expectations.

## Scope And Release Order

The endurance runs rebuild the declared Works stations in fresh and migrated-copy
worlds. They prove repeated bounded work and restart recovery, not thirty minutes
of every original machine in a player's workshop. Separate upgrade runs compare
saved legacy state and operate imported equipment without rebuilding it.
The lifecycle runner's inherited `original110-chained113` label uses the retained
released-base* 1.12-to-1.13 source copy in this campaign.

The legacy-import fixture deliberately keeps its old unrestricted oil-vein
settings. Desert-only generation applies to fresh Power defaults, not to an
existing explicit configuration. The imported fluid vein can keep flowing;
its oil count is the declared exception to exact restart terrain counts.

Manual movement, combat, general visual acceptance and arbitrary modpack worlds
are not covered merely because a server assertion passes. The native GUI checks
render real windows but do not replace every in-world interaction. This pass did
not rerun the entire Sylvester modpack or a new cold-cache/Linux build.

All source fixtures and earlier failed attempts are retained. Raw reports, jar
copies and logs are held locally under
`_verification/advantage113-public-qualification-20261115` outside the projects;
generated worlds and test output must not be committed.

Nothing is committed, pushed or published by this qualification pass. Release
Power first, record its real released source commit and CurseForge file ID in
Steam and Electric, then recheck their release guards and archive hashes. Their
`UNRELEASED` pins deliberately block publication until that step is complete.
