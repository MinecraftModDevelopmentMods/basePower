# Minecraft 1.17.1 Pre-Rename Test Record

This record is for the earlier Advantage-named jars. See the
[base* candidate record](BASE-CANDIDATE.md) for the current jars.

This is a local candidate, not a published release. **Qualification is incomplete
and it is not yet ready for manual acceptance.** A passing older jar, an isolated
successful rerun or a completed subset does not close a failed campaign.

## Candidate

| Mod | Version | Release SHA-256 |
| --- | --- | --- |
| Power Advantage | 2.4.8.117011 | `336D9A11F5488EBD63F208554A039A53A398ABC58F632880B8420AF10F5AFBF0` |
| Steam Advantage | 2.2.9.117011 | `BA3488817313101329AC6FE6D371514E8697FFFBD490B7E2C98B4CA705290569` |
| Electric Advantage | 2.2.9.117011 | `E1B4A7C65E09B6EE218F52328F0CD569A61B37E8646A46AC6D17EB3948D60EE4` |

Production source checkpoints are Power `5b9abe5`, Steam `3348ebf` and Electric
`9e6be35`. Packaged tests use published Base Metals `3.1.0.117011` and OreSpawn
`4.1.0.117011`; oil tests also use published Mineralogy `6.1.4.117011`.
Minecraft runs on Java 16; Gradle runs on Java 17.

Evidence is retained locally under
`D:/PowerAdvantage/_verification/advantage-117-port-20261031/` and
`test-rig/build/`. Generated worlds, dependency jars and reports are not release
content or intended for Git. Frozen manifests and failed reports are never
overwritten by a later test-harness revision.

## Completed Scope

These results use the production jars above. Most use the frozen O harness;
the separately frozen P harness changes test observation, not production jars.

| Check | Result and boundary |
| --- | --- |
| Release audits | Power 201, Steam 199 and Electric 96 tests pass, with dependency, archive, bytecode, resource and Eclipse checks |
| Independent compiler and reproducibility | ECJ checks and two independent source exports per mod; all four artifacts match, including fresh-cache and spaced-path builds |
| O machine operation | 132 measured setups pass, with exact cold inspection and independent raw-save replay |
| Imported 1.16 operating setup | 132 layouts preserved across conversion and full restart without reconstruction; final stopped terrain also matches independent raw replay |
| O connected upgrades | 20 operating routes pass at a fixed 1,024-tick interval; 40 raw-save windows have no differences |
| Fluid interoperability | 22 operating and two restored cases pass |
| Oil | Six lanes, three boots and two full restarts pass; correct identities and amounts for Advantage and Mineralogy oils |
| Harvesting | 271 actual tool/tier/drop cases pass on each boot |
| Actors and player storage | Eight actor routes and six player-layout routes pass, with independent saved-data checks |
| P respawn | All 72 Nether/End server-handler cases and independent audits pass using immutable per-case requests; startup reliability and recipe qualification remain separate |
| P world generation | Six profiles, twelve boots and 20,004 sampled chunks pass independent owner, biome, dimension, duplicate and ticket-cleanup audits; 36 clean logs retained |
| Q instrumented recipes | Five discovery boots and eighteen manufacturing checks pass, with 36 single hammer invocations independently replayed from exact native query, selector, action, debit and output telemetry |
| Transformer order | Six worlds and 12 launches pass semantics; individual automatic backups depend on callback order, so an independent whole-world backup is required |
| Partial-network unloading | Wide bridge and extended-drill layouts pass in both worlds, with actual unload events, both load orders, finite work, restart and independent raw replay |
| P drill lifecycle | Seven groups of twelve cases pass in each world; independent audits corroborate 48 actual native callbacks across seven boots, without manually ticking machines |
| Endurance | Two worlds complete thirty one-minute batches, full process restart and resumed processing; independently replayed raw checkpoints |
| Redstone-held drills | Nine cases pass; eight held drills do no work or energy debit and one unheld control advances for sixteen native ticks; both stopped terrain boundaries match |
| Combined client | Current jars pass 94 items, 905 block states, 351 caption cases and 355 broken controls; independent comparison binds all 542 loose Power entries and four runtime dependencies to their pins |
| Walkable Works | All 21 stations exercised in current and no-Base-Metals profiles before and after restart; 21 manual checklists per phase remain open |
| Harness controls | The P checkpoint passes 315 Java and 871 native-adapter Python controls; the instrumented Q source passes 327 Java and 876 native-adapter Python controls. Fourteen shared controls also pass. Native scope is recorded separately |

The no-Base-Metals profile still includes required OreSpawn. It is not an
absent-OreSpawn startup test. Endurance uses fresh equipment and rebuilt Works
inside a disposable migrated world, not thirty minutes of every original legacy
machine. Imported-layout preservation is not credited as another operating test.

## Open Failures

- **Startup deadlock:** the O End-dimension run captured two thread dumps showing
  Base Metals' `ContentModeNetwork.register` initializing `FMLHandshakeHandler`
  while Forge initializes `FMLNetworkConstants` on another loading thread. Each
  waits for the other's class initialization. That route remains failed.
  Three other O loading stalls have no thread dumps and are not automatically
  assigned the same cause. Forty-eight startup-control boots and a later
  instrumented import passed, but do not repair or explain those failures.
- **Frozen migration coverage:** O completed 14 of 16 routes. Two routes failed
  during mod loading. Available raw windows match, but missing windows are not
  passes.
- **Dimension coverage:** O completed all eight Nether routes. The End campaign
  stopped during its first route's restart; only 25 of the required 48 boots
  completed across both dimensions. Earlier complete matrices used a different
  Steam jar and do not close this gap.
- **Recipes:** five discovery boots passed, but the operating recipe campaign
  failed its live hammer check after resource reload in APOCALYPTIC mode.
  Thirteen of eighteen manufacturing checks completed. Native item-query
  visibility is a hypothesis, not an established gameplay diagnosis. A finite
  P control subsequently passed all five discovery boots and eighteen
  manufacturing checks without changing the hammer protocol. Q then added
  exact native query/selector observation and fail-closed setup gates. All
  original action and output assertions remain; its 36 invocations pass
  independent replay. This proves the new run's conditions and bounded
  behaviour, not the cause of the earlier uninstrumented failure. The historical
  investigation remains open; no production repair is claimed.

The earlier world-generation sampler blocked on synchronous FULL-chunk loading
and hit the native watchdog. P uses asynchronous native requests with the same
sample counts, biome checks and deadlines. All six profiles now pass, including
Mineralogy, legacy import and the combined Base Metals installation. All 47,016
requests completed and their tickets were released. The original failure remains.

O's changed-target guard failed on seven Steam drill rows without proving the
first eligible callback. P now records that actual native callback and passes
both worlds with the original state, quantity and work assertions unchanged.
Its general raw-evidence audit and separate callback audit pass; all 1,043
guarded files remained unchanged. The original O failure is retained. This is
an observer repair, not a new Steam production fix or a startup-deadlock fix.

O's End respawn attempt failed while replacing a live fixture file. P avoids
that replacement with immutable requests; all 72 cases and independent audits
now pass. The original failed attempt remains retained. Server adapters do not
prove real-client death screens or resolve the separate startup deadlock.

The O dimension handoff is
`dimensions-native117-o-failed-handoff.json` in the local evidence root.
Machine, recipe, harvest and interoperability receipts are indexed by
`final-o/handoff.json`; its overall recipe failure must not be overlooked.
The frozen P input is `test-rig/build/native117-target-p.json`. A frozen input
is not a qualification certificate.

The Q hammer input and its independent invocation replay are retained under
`hammer-observer-q/` in the local evidence root. The same instrumented Java
source rebuilt in the main checkout produces byte-identical harness jars.
The source profiles use the new pins, with no machine-specific provenance paths;
they remain explicitly unqualified.

Resolve the dependency startup deadlock and complete the missing frozen/End
routes before manual acceptance. A successful boot of another fixture does not
fill a missing route or prove startup reliability.

## Repairs And Evidence Rules

Production repairs during this port cover native ingredient predicates, dirty
machine state after autosave, exact vanilla path/sound aliases and nine Steam
recycling recipes whose external serializer needs a Base Metals presence guard.
No recipe ratio or gameplay balance change is intended.

Test repairs remain separate. The connected observation window was fixed at
1,024 ticks because some drills require 576 powered steps. Non-mutating entity
observers avoid allocating empty `ForgeData`. Empty NBT is not broadly ignored.
The natural-tick observer transparently wraps the native delegate, forwards it
once and records its first callback; it never manually ticks a machine.

Strict tamed-wolf health comparison reports 8 becoming 20. A separate native
control proves that exact platform difference; no other entity change is allowed.
Non-persistent leash helpers receive lifecycle evidence, not raw-storage UUID
persistence credit. Incomplete or invalid short bridge layouts remain failed.

## Manual Boundary

The current combined-client evidence is
`run-client-resource-runs/20261031-101716-9e75e592/`, with the independent
`client-final-production-o-binding.json` receipt in the local evidence root.
This is a worldless resource, caption and geometry check, not live-world or
manual visual acceptance.

Manual checks still include normal Eclipse startup, visual appearance, recipe
book interaction, live turbine/drill animation, elevator rides, turret display
and multiplayer interaction. All three mods share Power Advantage's creative
tab. See the [manual checklist](MANUAL-TESTING.md).

Use disposable copies. These fixtures do not qualify the entire Sylvester
modpack or unrelated mods without target-version releases. No 1.17 port code
has been pushed, merged, tagged or published.
