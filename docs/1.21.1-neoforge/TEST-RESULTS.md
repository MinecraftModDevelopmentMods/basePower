# NeoForge 1.21.1 Test Notes

The native port passed manual testing. The published dependency stack was then
requalified on 15 November 2026 before release. The Forge 1.21.1 results in the
neighbouring folder belong to a different build and are not substituted here.

## Tested Versions

| Input | Version |
| --- | --- |
| Minecraft / NeoForge | 1.21.1 / 21.1.265 |
| Java / Gradle / ModDevGradle | 21 / 9.8.0 / 2.0.148 |
| Base Metals | 4.0.1.121012+Neo |
| MMDLib | 2.0.2.121012+Neo |
| OreSpawn | 4.1.1.121012 |

All three dependencies are public releases, not development substitutions. The
Base Metals CurseForge, Maven and GitHub downloads have identical bytes and
include the reviewed shared migration hook.

| Player Jar | SHA-256 |
| --- | --- |
| basePower 2.4.19.121012 | `E9ED662E15C3510E2A48CE85448D3D39C6EF2667208F29EB281791200507F77E` |
| baseSteam 2.2.20.121012 | `AD4BACD5158252F4AE257DF694098E47E7C309B21BFD9F583AF6A18E569FABB9` |
| baseElectric 2.2.20.121012 | `BC866F83F55FAD289EC36808D33A71B2602B8062EB71FC2E80C9CE3B0495F6D8` |

## Results

| Check | Result |
| --- | --- |
| Power / Steam / Electric unit tests | 236 / 231 / 110 passed |
| Test-harness Java / Python regression tests | 339 / 2,338 passed |
| Steam / Electric native GameTests | 10 / 21 passed |
| Independent native controls | 3 passed |
| Finite machine jobs and saved-state restart checks | 132 passed |
| Recipe unlock boots / live progression cases | 5 / 18 passed |
| Partial networks, bridge unloads and extended moving drills | Passed |
| Harvesting, lifecycle, oil transport and Mineralogy oil | Passed |
| Fresh and upgraded endurance worlds | 30 minutes each, then restart and resumed processing |
| Independent endurance replay | 33 checkpoints and 528 station snapshots per world |
| Rendered client | 94 items, 905 block states and 87 creative entries |
| Machine screens | 39 screens, 351 caption checks and 355 rejected negative controls |
| Fluid gauges / recipe-book modes | 26 pixel cases / all 3 modes passed |
| Real integrated-client menu and complete client restart | Passed; 1,375 mB retained without reseeding |
| Eclipse compilation and production/test isolation | Passed for all three projects |
| Independent-cache builds and archive checks | Passed; player hashes retained |

The endurance replay checks actual inventories, fluids and processing output,
not animation or a lit power indicator. Both worlds ran at about 19.96 measured
ticks per second during the sampled period. No earlier candidate's endurance
result is used to qualify these player jars.

Shared crushing uses Base Metals' registered iron crackhammer and the real
MMDLib handler, alongside naturally ticking Steam and Electric crushers. Recipe
replacement, removal, restoration, tool wear and full-process restarts passed.
The test no longer asks MMDLib to register a hammer item of its own.

The native recipe checklist includes vanilla copper. All recipe modes were
tested, including Apocalyptic progression with and without rubber. Saved recipe
books and three independently exported server registry/recipe packets were
checked in the real client.

## World Upgrades

Disposable fixtures cover original Cyano 1.10, released base* 1.10 and 1.12,
and maintained Forge 1.21.1 worlds. Each route compares machine state before
allowing work, operates the connected equipment without rebuilding it, then
saves and performs a complete process restart. The original sources and server
seed are fingerprinted and remain unchanged.

Separate player, Nether and End fixtures cover item quantities, wear,
enchantments, ownership, passengers, attributes and entity storage. Minecraft's
own data fixer changes the old tamed-wolf fixture from health 8/maximum 20 to
health 16/maximum 40. Independent NeoForge controls reproduce this without the
Advantage family or its dependencies. The strict differences remain recorded;
only those exact native changes are accepted by the separate assessment.

This is not proof that an entire third-party modpack can be upgraded after
removing mods which own blocks or items in that world. Back up the complete save
and upgrade a copy first. No original Sylvester world is opened or altered.

## Generation And Persistence

Provider choices, disabled alternatives, user overrides and imported legacy
OreSpawn settings survive cold restarts. Positive normal-world samples use the
retained seed `24680`, with separate desert and ocean observations. Power oil,
lithium and the selected sulfur source are present; oil biome violations are
zero and counts are unchanged after restart. With Mineralogy installed, its
sulfur rule owns generation and Electric sulfur stays disabled.

Zero-oil samples from seed `12345` were retained as failed generation evidence,
not accepted as a pass. The positive witness uses the established sample seed;
generation settings and production code were not changed to force deposits.

## Evidence And Limits

The local release record under
`D:/PowerAdvantage/_verification/base-1211neo-release-20261115` binds the exact
jars, profiles and passing receipts. Failed attempts remain alongside the final
results. The hammer fixture correction changes only three test-harness class
files; a separate hash-pinned profile covers that check. The initial Electric
player jar was
`61457EDF8B1C097AE17805DCC26CB7E0F4F661208B777A0B53C56BB383EEDA85`.
Its first hosted archive audit correctly rejected a Windows/Linux difference:
62 recipe JSON files had CRLF rather than LF endings. Resource processing now
uses UTF-8 and LF on both platforms. All 95 class files, TOML and other
resources are byte-identical; all 331 JSON documents have identical values.
The final player digest is the `BC866...` value above. Its sources and Javadoc
archives are unchanged, and all three archives match hosted Linux builds.

The final Electric jar received fresh packaged machine, upgrade, recipe,
unloading, drilling, generation and endurance checks under the `lf-a` run names.
The two new endurance worlds also passed independent checkpoint replay after
complete process restarts. Earlier receipts and the rejected jar are retained;
they are not substituted for these new headless runs.

The rendered development-client results above predate this recipe-packaging
correction. They are retained evidence for unchanged client code, models,
textures, language and GUI resources, not a fresh packaged-jar client run.
The current launcher still uses loose Power output, so no new exact-jar-bound
visual or integrated-client receipt is claimed. Packaged dedicated-server
restart and saved-state checks were repeated on the final jars.

Real-player interaction, live turbine motion, resource packs and third-party
modpacks still benefit from ordinary playtesting. The Metal Fluid Tank retains
its historical empty drop; the Portable Fluid Tank keeps its contents when
picked up in Survival. Local saves, dependency jars, logs and raw reports are
not included in Git or the release jars.
