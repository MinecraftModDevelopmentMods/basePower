# Minecraft 1.21.11

This version has passed automated qualification and manual testing. Publication
is tracked separately from acceptance; see the
[current qualification record](QUALIFICATION.md). It targets Forge 61.1.0,
Java 21 and data version 4671.

## What Changed

The port uses native typed Forge event buses, keyed block/item properties,
value-based block-entity saves and native migration Mixins. Existing machine
names, inventories, fluid fields and the retained Cyano API remain in place.
Recipes use native string ingredients. Item definitions and the split item
atlas keep the existing artwork and model identities.

The resource pack declares server format 94.1. Forge supplies the hidden
client aggregate with format 75 metadata. Both effective native packs are
tested through real Forge discovery, rather than treating one raw metadata
file as both pack types. Forge 61.1.0's server discovery label still compares
against the client version and says TOO_NEW; the correctly typed server pack
is compatible and accessible. This platform discrepancy is recorded, not
hidden by changing the data format.

## Current Candidate

The dependency-corrected Power jar has player SHA-256
`C98D2CF163BE2480D9AFB0023BBD2D92D401AE0D50BEF73191235BF3F83774A7`.
Its full release suite passes. The qualification run passed 276 unit tests,
including fresh Eclipse compiler output. Release preparation adds a passing
277th test for the CI artifact upload path and a 278th for source-workflow branch
filters. Both pass their broken-input controls. Against the preceding registry-repair jar, only
`META-INF/mods.toml` changed; all gameplay classes and other resources are exact.
The preceding registry repair passed two additional chained 1.13 dimension
upgrades through full restart. Those earlier results and failures are retained
under their own artifact hashes, not relabelled as final combined qualification.

The current family is **manually accepted**. Its own
machine, migration, graphical and two-world endurance reruns now pass, including
independent exact stopped-save replay. The expansions pin the same Power
source. These are new results for the current hashes, not transferred C2
results or an expanded claim based on the dependency smoke test.

Base Metals is required by all three mods on both client and server, matching
their CurseForge requirements. The previous Forge-optional flags were leftover
metadata and have been corrected. The no-Base-Metals robustness run below is
not a supported installation or a release qualification requirement.

Native Forge parser tests verify the requirement in all three mods. A packaged
server starts, saves and stops with the matching Base Metals installed. Without
it, Forge reports the three missing-dependency errors before mod construction
or world creation. Minecraft handles this early failure with process exit 0,
so the negative control checks the exact dependency errors and absence of a
world rather than treating an exit code alone as proof. These are dependency
checks, not a replacement for the separate, now-completed gameplay and
endurance qualification.

## Completed C2 Checks

The earlier frozen C2 player jars have these SHA-256 checksums:

| Mod | Version | SHA-256 |
| --- | --- | --- |
| basePower | 2.4.15.121111 | `134FF55595E86D450DBFB5531F389BF0BE81FE6347F1EB03765B571A1D488B59` |
| baseSteam | 2.2.16.121111 | `25EE7784EAA180C77B7735B90928801F56A7342B5733B49222E2B0E72E5577F9` |
| baseElectric | 2.2.16.121111 | `6790C193F18928F2547ECD57EC55AF6FBFC497D67FE46862146BB0C810E6B192` |

These results refer to those bytes, not to an earlier port candidate.

- All 257 Power unit tests and the full release and Eclipse audits pass.
- A clean build in a path containing spaces, using a new Gradle cache,
  produces identical player, sources and Javadoc jars.
- Both mods' actual chunk-migration callbacks run in either order without
  cancelling the companion hook. A new regression catches the early return
  that previously prevented turbine restoration.
- Native data-fixer checks preserve the chain's axis and waterlogging, all
  boat variants and both thrown-potion types. Only their obsolete Forge
  indexes and six exact removed sound indexes are retired; unknown entries
  still fail rather than being silently accepted.
- Native resource discovery reads 261 client and 160 server files, with no
  warnings or errors. Opposite-format and missing-metadata controls remain.
- A disposable packaged Power world starts, saves and reopens in a new
  process with the exact local Base Metals migration-hook candidate.
- The new Python adapters have positive and negative controls for command
  acknowledgement, logging custody, item state and saved terrain.
- The native harness passes 379 tests and its independent Forge loader control.
- All 132 finite machine jobs pass, followed by a complete process restart
  and independent inspection of both stopped saves. The tests check actual
  products, consumed inputs and saved state, not just a spinning animation.
- Original 1.10 Advantage, released 1.10 and 1.12 base* machine fixtures,
  and maintained 1.20.6 and 1.21.1 fixtures pass conversion, full restart
  and operation without replacing the machines.
- Six-facing drill, moving drill, harvest, real chunk unload and partial-network
  checks pass. Shared crushing and both oil integrations also pass after restart.
- Recipe discovery, manufacturing progression and recipe modes pass, including
  the real server tag and recipe-book packets used by the graphical client.
- Advantage generation, legacy OreSpawn configuration import, Mineralogy
  generation and oil transport/processing pass in disposable worlds.
- Actual graphical testing of the frozen jars passes for 94 item models,
  905 block states and all 39 machine screens. It checks translated captions,
  fluid pixels and recipe-book cells, with deliberate broken controls.
  This caught and led to repairs for native block-item names and the split
  block/item atlas; the earlier failed evidence has been retained.

Legal original-1.10 and maintained-1.12 player/entity fixtures pass. Nether and
End fixtures retain the Advantage data but report a separate native difference:
Minecraft changes legacy wolf health and maximum health. An independent vanilla
data-fixer control reproduces that change. It is not presented as exact
preservation of every vanilla field. Invalid historical overstacked battery
fixtures remain failures rather than accepting Minecraft's stack-size clamp.

The Windows Forge/Netty startup probe is also run without mods. The test
launcher retains normal stack traces and all logging levels, avoiding the
extended formatter's attempt to initialize unsupported native OS classes.
Only exact, reproduced DEBUG capability probes are classified as platform
observations. ERROR events, different causes, extra exceptions and mod frames
remain failures; the original failed logs are retained.

Both fresh and original-1.10-upgraded endurance worlds complete 30 measured
one-minute batches, a cold restart and a further minute of useful work. Each
produces the exact expected item totals and 30,000 mB of distilled output.
The source world and server seed remain unchanged. Independent stopped-save
replay confirms all 66 batches and their 1,056 station snapshots, but its exact
final comparison fails because timers and buffers kept ticking before the save.
The harness now freezes ticks across that final checkpoint/save boundary.
The separate current run in the qualification record passes the corrected
protocol with a genuine pre-run seal. No retrospective source seal is claimed
to have existed before the older C2 run.

Both actual migration-transformer orders also pass conversion, restart and
operation. That test uses a bounded seventh test mod and audits the woven
callback order. It is separate from the normal six-jar profile, does not change
the production jars and is not a production launch mode.

## Historical Failures And Manual Checks

The old no-Base-Metals robustness profile failed during OreSpawn's startup tag
lookup with `IllegalStateException: Tags not bound`. Its failure is retained,
not converted to a pass. This was an unsupported dependency combination, so
repairing OreSpawn for it is not a prerequisite for the base* port. No OreSpawn
source change has been made. Current metadata must reject a missing Base Metals
dependency before constructing any of the mods.

The two additional chained 1.13 dimension fixtures exposed a biome lookup and
four old Forge registry names. Those repairs now pass strict world tests,
including all four Steam enchantments with their levels and inventory slots.
The complete enchantment index is archived, not discarded; uppercase FML and
numeric-ID archives remain untouched. Conflict, malformed-input, idempotence
and byte-exact backup controls pass. Four precisely identified removed vanilla
sound indexes are retired; unknown names still remain unresolved. The separate
current aggregate now passes the supported automated gates; the earlier failed
receipts remain unchanged.

Manual inspection is still needed for normal play and the in-world hydroelectric
turbine's animation and orientation. Its geometry checks do not replace seeing
the entity rendered in a real client world. Automated screens and recipe-book
checks likewise do not claim human acceptance.

An independent source-to-test review finds no empty or skipped finite-operation
passes: the 132 jobs contain 690 successful verify-phase assertions. It also
identifies these remaining coverage limits, rather than treating a model, recipe
or station supply chest as proof of gameplay:

- Actual rotator use and preservation of the rotated machine's contents.
- Oil immersion effects and fire callbacks.
- Timed musket loading without Rapid Reload, including cancellation.
- Turret player/team targeting and the evil turret's combat behaviour.
- Connected-player menus, shift-click, control packets and live gauge updates.
- A connected player's elevator ride and interrupted recovery aboard it.
- Fluid terminal transfer to external mods' fluid machines and faces.
- The optional BaseMinerals-present sulfur/lithium ownership tier.

These are missing witnesses, not confirmed defects. The finite machine,
save/restart and rendering results above retain their narrower, tested scope.

The Base Metals 3.1.2.121111 shared migration hook was local-only during
qualification. It must be published first; do not substitute the older
dependency. Always protect the original save and test a copy. Source merge,
CurseForge, Maven and GitHub publication have their own verification gates.
