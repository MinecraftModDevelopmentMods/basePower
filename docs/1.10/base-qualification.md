# base 1.10.2 Candidate Checks

**Publication update, 3 November 2026:** basePower now has a live [CurseForge project](https://www.curseforge.com/minecraft/mc-mods/basepower), ID `1780140`, listing the 1.10.2 release. The checks and pending gates below are the preserved pre-publication record, not a statement that the project is still unassigned. This metadata update does not independently verify the downloadable file's checksum or authorise another upload.

This is the local basePower/baseSteam/baseElectric identity-migration candidate, not a published release. The old Advantage repositories remain intact. Tests use disposable copies; the original Sylvester world has not been converted.

The results below record exact-artifact qualification before source delivery. Git commits, hosted checks and CurseForge publication are separate steps. Both expansions must pin the approved basePower source commit and development-jar checksum before their guarded release checks can pass. New CurseForge project IDs remain unassigned, so publication stays blocked.

## Current OreSpawn Restart Repair

The current basePower jar repairs the combined Sylvester restart failure recorded below. Global and saved-world OreSpawn profiles have separate supported schema limits: global schema 8 and world schema 7 are accepted without downgrading either file. Unknown future schemas still stop before any write. The earlier fluid-capability repair remains included; Steam and Electric release bytes are unchanged.

| Current jar | SHA-256 |
| --- | --- |
| `basePower-2.4.4.110021.jar` | `164693E756A935A8C51085CF95AECCCAF3506390A6F0A3888E46765F29896F31` |
| `baseSteam-2.2.6.110021.jar` | `728E37A1A0551596D8167976A7022E31EF075A383640BB385470160DD9A8F07D` |
| `baseElectric-2.2.6.110021.jar` | `05F1CF02C97C6112C7EB4913DB46F65C45058CC6E818D81E159DBF5AD0F93DD1` |

This is a replacement of an unreleased candidate, not a new published version. The failed jar and its reports are retained under their original checksum.

| Requalification check | Result and limit |
| --- | --- |
| Schema regressions | Three new preservation/restart tests fail before repair and pass afterwards. A fourth test requires unknown global/world schemas to fail before any write or backup. Current settings, disabled rules, provider records, retrogen controls and exact no-op bytes are retained. |
| Build | Power's full build, dependency, archive, Javadoc and Eclipse checks pass. Steam/Electric tests and assembly pass against the new development jar, with unchanged release bytes. Their exact-source pin checks are completed separately during source delivery. |
| Unit tests | Power: 87 tests, one Windows symlink-privilege skip; Steam: 135; Electric: 70; harness: 92; Python contracts: 189. No failures in the final runs. |
| Operating rigs | All 188 natural-tick cases and all 188 cold-state comparisons pass in both the older API pack and the maintained Base Metals `2.5.1.110021` / OreSpawn `4.1.2.110021` pack. |
| Independent controls | All nine partial-network cases and six first-tick/comparator cases pass independent raw-evidence checks in each dependency pack. |
| Endurance | Both dependency packs complete 36,000 natural ticks and a full process restart. Each finite Steam network/crusher reconciles 320 input stones as 287 products and 33 remaining inputs, with 116 ticks of partial work retained and zero lost progress. All 80,000 initial steam units reconcile as 53,986.5 productive use, 15,201.4375 idle loss and 10,812.0625 remaining. No refills or rebuilding. |
| Endurance limits | All 37 checkpoints and 287 batch receipts reconcile per pack; shortened and lost-progress records are rejected. The measured first-to-last tick intervals are 1,799.946 and 1,799.950 seconds. These are two Steam network/crusher jobs, not 30-minute tests of every machine. |
| Progression | All six mode/rubber configurations pass with maintained dependencies: 24 server processes and 10,576 finite-stock actions. Independent checks reconcile hammer wear, paid trades, real oil/plastic processing and cold inventory state. The unused lithium nugget remains unproven; complete progression is still labelled `PARTIAL`. |
| Original-release fixtures | The six connected setups, all 355 passive state witnesses and the separate synthetic serializers pass again on the rebuilt jar. This route deliberately retains the original Base Metals registry; its known bucket diagnostic has fresh, exact-candidate evidence. |
| Sylvester preservation | A disposable copy of the actual world passes first load and full restart with maintained Base Metals/OreSpawn. All 1,200 Advantage tile records retain exact typed state, including heat, fluids, energy and inventories. All 87,759 saved chunk states and 18,184 stack records match the old-mod control after the documented identity aliases; all 27,686 saved entity identities and four player records remain. |
| Sylvester operation | Two 2,048-tick operating intervals, separated by a full process restart, complete without construction or refills. Machine NBT and inventory contents match exactly across that restart. Nine boilers stay hot, four already-connected steam tanks retain pressure, and the full water/crude-oil tanks retain their contents. Two pre-existing empty steam tanks remain empty. |
| Sylvester limits | Its crusher has no input and the conveyors remain idle, so this world does not prove their throughput. Three real offline player files provide 104 successful native inventory/ender-stack reads; no authenticated player login was performed. Electric is not installed in this historical world. |
| Worldgen choices | Sylvester's global schema-8 and world schema-7 profiles remain byte-for-byte unchanged across all five repaired boots, including the operating restart. The saved world remains in `legacy` geology mode. |
| Third-party controls | The exact same 275 obsolete, inventory-free BuildingBricks dirt-helper records disappear on the old-mod and repaired cold boots. The replacement Sky's Grass Slabs blocks remain unchanged. Raw strict failures stay available; the explanation is restricted to the exact matching records and rejects unmatched positions or inventory-bearing records. |
| Packaging and reproducibility | Independent source exports in paths with spaces reproduce all four Power artifacts using a shared dependency cache, not a fresh empty cache. All 12 current release/development/source/Javadoc archives pass isolation checks. Only the worldgen identity helper and its nested classes differ in the main jar; three nested classes differ only in debug positions. Public API classes and assets are unchanged. |

The original Sylvester installation and earlier failed evidence remain unchanged. Broader player login, client visuals and optional third-party integrations still need their own acceptance; these results do not turn a server-start pass into an all-worlds guarantee. The earlier GUI/world-generation profile runs below retain their older checksums.

## Earlier Fluid-Capability Candidate

This earlier basePower jar repaired `PA-110-002`: Forge capability calls use the machine's own fluid handler instead of bypassing it through the output tank. The native starter-plant test exposed this defect; its failed runs remain available. Steam and Electric release bytes were unchanged.

| Earlier jar | SHA-256 |
| --- | --- |
| `basePower-2.4.4.110021.jar` | `9941893C0F536D97E3305212F3083C663F2F0BE30EF56B12AA5E6BEE3C2AE00C` |
| `baseSteam-2.2.6.110021.jar` | `728E37A1A0551596D8167976A7022E31EF075A383640BB385470160DD9A8F07D` |
| `baseElectric-2.2.6.110021.jar` | `05F1CF02C97C6112C7EB4913DB46F65C45058CC6E818D81E159DBF5AD0F93DD1` |

The version number is unchanged because this is still the same unreleased candidate. Use the checksum to distinguish it from the earlier jar below.

| Follow-up check | Result and limit |
| --- | --- |
| Focused capability regressions | Four pass; three failed before repair. They cover input/output routing, side and fluid restrictions, simulation/capacity, and output-only draining. |
| Unit tests | Power: 83 tests, one Windows symlink-privilege skip; Steam: 135; Electric: 70; harness: 92; Python contracts: 189. No failures in the final runs. Profile JSON files are now declared Gradle test inputs. |
| Native operating suite | All 188 operating cases and all 188 strict cold-state comparisons pass against the rebuilt Power jar. Independent partial-network and Steam control audits also pass. |
| Survival progression | Normal, Tech Progression and Apocalyptic each pass with rubber equivalence both enabled and disabled: six configurations, 24 server processes and 10,592 finite-stock actions. |
| Real processing | Each configuration collects crude oil with a bucket, distils 1,000 mB into 500 mB refined oil, and produces five plastic ingots using only naturally generated hydroelectricity. A separately fuelled furnace receives boiler steam and exceeds coal-only temperature. |
| Trading and wear | The two Apocalyptic cases each execute 71 paid native trades. An independent audit reconciles merchant use counts and actual typed hammer-damage NBT with the recorded actions. |
| Restart | Crafting stock survives a process restart. The plant's exact block states and typed tile NBT survive a mid-cycle restart before ticking, and completed products and typed item state survive a final restart. No rebuilding or reseeding. |
| Coverage | All 88 registered item/block-item identities in the pinned pack have a route, a justified exclusion or a visible gap. The unused lithium nugget remains unproven; no new recipe was added for it. |
| Reproducibility and packaging | Two clean source exports in paths containing spaces reproduce all four Power artifacts. Twelve current release/development/source/Javadoc archives pass structural isolation checks. Only one main-jar class changed; resources and public API descriptors are identical. The dependency cache was shared. |
| Original connected world | Fresh rerun against these exact jars: all six released-Cyano setups preserve unticked state, then pass two 2,048-tick work intervals across a complete process restart. No machines are rebuilt or replaced. The Steam drill finishes its finite target before restart; the restarted check accounts for that exhausted target rather than inventing more work. |
| Original passive world | Fresh conversion and cold restart preserve all 355 block-state witnesses, 332 tiles, 272 inventory stacks (2,654 items) and four enchantment entries. The original fixture has no saved player or entity witnesses. |
| Additional serializers | Three synthetic native serializer cases pass again on each boot: detached player inventories, enchanted dropped items and nested item payloads. These remain separate from real player login, respawn and historical entity acceptance. |
| Migration evidence | Independent hash verification covers 904 retained raw migration/proof files. Original fixtures, test protocols and all three manual-test jars remain unchanged. The original Base Metals 2.4 registry is retained to isolate the Advantage rename; this is not also a Base Metals upgrade test. |
| Endurance rerun | The same manual-test jars complete 36,000 natural ticks in 1,799.948 measured seconds, followed by a full process restart and exact saved-state comparison. The finite Steam network supplies 35,991 paid work ticks with zero lost progress: 320 input stones reconcile as 287 finished products plus 33 remaining inputs, with 116 ticks of partial work retained. All 80,000 initial steam units reconcile as 53,986.5 productive use, 15,201.4375 idle loss and 10,812.0625 remaining. No refills or rebuilding. |
| Independent endurance audit | All 37 checkpoints and 287 batch receipts reconcile. Deliberately shortened, altered-steam and lost-progress records are rejected. This is one Steam network/crusher endurance job, not a 30-minute test of every machine. |

The progression matrix uses the pinned Base Metals `2.5.0-beta4.238` and OreSpawn `4.0.8.110021` pack. It does not establish optional Additional Loot Tables integration, every third-party ingredient provider, resource-gathering balance, villager encounter/levelling, or later Minecraft recipe advancements. Read [Recipe Progression Checks](recipe-progression-checks.md) for the exact method and limits.

The GUI images and optional-dependency/world-generation profiles below still belong to the earlier Power checksum. They are preserved evidence, not freshly rerun claims for this replacement jar. Original-release migration and the finite Steam endurance job have now been rerun as recorded above; the new 188-case suite separately checks restart preservation. Endurance timing runs from the first measured tick to the last: 36,000 observations span approximately 1,799.95 seconds at 20 ticks per second.

The remaining source-commit, publication and manual-acceptance gates still apply. The new files have not been committed, pushed or published.

## Earlier Sylvester Upgrade Failure

A separate test replaced released Cyano Power/Steam and Base Metals 2.4 in a disposable copy of the actual Sylvester world. It used the earlier fluid-capability candidate checksums above, maintained Base Metals `2.5.1.110021`, OreSpawn `4.1.2.110021` and Forge `12.18.3.2511`. Electric was not installed because it is not part of this source world. The original save and configuration remain unchanged.

The first load visits 2,346 existing chunks without ticking or rebuilding machines. All 1,200 Advantage tile records preserve their complete typed saved state, including all nine geothermal temperatures. An offline scan covers all 87,759 saved chunks. Compared with the old-mod control, block states match throughout, all 18,184 saved item-stack records match after the documented Advantage/Base Metals identity aliases, all 27,686 saved entity identities remain, and all four saved player records retain their state. Native item deserialization also preserves 104 inventory/ender-chest stacks from the three actual player files; this is not a player-login test.

Both the control and upgrade perform the same existing BuildingBricks-to-Sky's Grass Slabs conversions and HarvestCraft item-name normalizations. Their raw source-comparison failures are retained. The nine tile differences between the two runs are the already-repaired geothermal reload defect: the old Steam jar resets heat, while baseSteam retains the original values.

**That earlier candidate failed qualification.** Its full-process restart fails during basePower initialization: `LegacyWorldgenIdentity` rejects profile schemas above 6, but the selected maintained OreSpawn writes global schema 8 and world schema 7. No worldgen configuration was removed or downgraded to bypass the failure. The candidate jars and failed boot remain preserved. The replacement's fresh results are recorded at the top of this page. The old-mod operating control completed 2,048 natural ticks.

## Earlier Identity-Migration Candidate

| Jar | SHA-256 |
| --- | --- |
| `basePower-2.4.4.110021.jar` | `5F29C9D4A473F15881C782853256A8409ACFA17EC2A6C3AE2F3CD1DC64EF0405` |
| `baseSteam-2.2.6.110021.jar` | `728E37A1A0551596D8167976A7022E31EF075A383640BB385470160DD9A8F07D` |
| `baseElectric-2.2.6.110021.jar` | `05F1CF02C97C6112C7EB4913DB46F65C45058CC6E818D81E159DBF5AD0F93DD1` |

Earlier Advantage results do not qualify these jars. The native runs pin the production files, server code, fixtures and test protocol. Migration-specific harness repairs have their own inputs; the three production jars stay unchanged.

## Completed Checks

| Check | Result and limit |
| --- | --- |
| Unit tests | Power: 79 tests, one Windows symlink-privilege skip; Steam: 135; Electric: 70. No failures. |
| Test-harness contracts | 91 Java tests and 167 Python tests passed, including checks that reject incorrect migration evidence. |
| Native operating suite | All 188 cases passed, followed by 188 exact block/state/typed-NBT comparisons after a complete server restart. |
| Independent controls | All nine partial-network cases and six first-tick/comparator control cases passed independent raw-evidence audits. |
| Endurance | Steam-network job: 36,000 natural ticks, 1,799.958 measured seconds, 35,991 paid work ticks and zero lost-progress ticks. The saved result survived a full process restart. This is not a 30-minute endurance claim for every machine. |
| Harvest | 272 executed checks on each of two cold boots; version-inapplicable cases stay explicitly excluded. |
| Client GUIs | All 39 machine screens passed 351 caption cases and rejected 355 negative controls. This checks layout and text, not every world model or manual interaction. |
| Original connected world | Six released-Cyano setups preserved unticked state, then completed paid work on both converted and restarted boots without replacing machines. |
| Original passive world | All 355 block-state witnesses, 332 tile records, 272 inventory stacks (2,654 items) and four enchantment entries passed unticked conversion/restart comparisons. The source has no saved players or entities. |
| Additional serializers | Three synthetic native serializer cases passed on each boot: detached player inventories, enchanted dropped items and nested item payloads. They do not prove actual player login, respawn or historical entity spawning. |
| Packaged profile protocols | Current Base Metals, decoupled, Mineralogy oil processing, optional-power discovery, both fresh world-generation profiles and legacy OS1 import passed save/restart protocols. Station-specific `MANUAL_REQUIRED` results remain manual checks, not automatic passes. |
| Recipes | Bounded native matching, consumption and recipe-mode assertions passed. The conservative complete-progression graph remains `PARTIAL`; this is not a claim that every survival route has been proven. |
| Reproducibility | Six builds in two independent source directories containing spaces reproduced all 12 release, development, source and Javadoc jars. The Gradle dependency cache was shared, not freshly downloaded. |
| Archive isolation | Independent inspection found Java 8 classes, the expected mod IDs/assets and no embedded dependency, harness or agent content. This does not bypass the guarded release tasks. |
| Eclipse | Production classpath checks passed for all three projects. |

The passive fixture retains its exact original test-observer jar. Its startup handlers freeze ticking; building is an explicit command which the migration runner forbids. This fixes an omitted fixture dependency without changing production migration, rebuilding the fixture or hiding a missing-mod error.

## Preserved Failures

Failed logs and receipts have not been replaced. They include the initial migration/header failures, stale harness expectations and the known original Base Metals 2.4 late-universal-bucket diagnostic. The latter is classified only with fresh dependency-only boots and bucket/restart behaviour checks bound to the exact candidate. Other startup errors remain failures.

The drill's compatible new `Facing` field is compared against its original metadata, not ignored. Wrong facings, extra state changes and missing inventory remain failures. Retained musket enchantment keys are checked explicitly.

## Before Release

- Require an approved exact basePower source commit and development-jar checksum in both expansions, then run their complete guarded release suites. Local runtime results do not substitute for those source and dependency checks.
- Assign new CurseForge project IDs. The old Advantage projects must not be upload targets; `0` is an intentional publication block.
- Complete manual gameplay/visual acceptance on copies of real worlds.
- Update IAF's optional provider detection separately. Its current 1.10 plumbing bridge checks `poweradvantage`, so it does not offer fluid to `basepower` despite the retained typed API. Failed offers do not drain IAF's fluid.
- Keep the old and new Advantage jars out of the same instance. Read [the migration instructions](base-migration.md) and back up the complete world and configuration first.

No claim is made here for unported third-party mods, original player login/respawn behaviour, every possible world, or unpublished intermediate Advantage identities. Legacy read aliases must remain available for unvisited chunks and offline players in later base ports.
