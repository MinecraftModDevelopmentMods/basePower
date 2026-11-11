# Minecraft 1.21.11

This is a local integration candidate, not a released version or a completed
qualification. It targets Forge 61.1.0, Java 21 and data version 4671.

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

The latest local registry repair has player SHA-256
`D5FFE26E4BD6432E1DED087B83DCC4DA2E1717048CA2BFCC998D4F1AC6ED4747`.
Its full release suite and all 274 unit tests pass, including fresh Eclipse
compiler output. Two additional chained 1.13 dimension upgrades now pass their
payload and full-restart checks. Earlier failed attempts are retained.

This is still **not ready for manual acceptance or release**. The OreSpawn
startup fault below needs a separate local repair, followed by final dependency
pins and the full combined machine, client and endurance rerun. The expansions
currently retain their separately checked intermediate Power pin. Do not
transfer C2's results to this newer jar or describe that intermediate pin as
the final dependency set.

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
The harness now freezes ticks across that final checkpoint/save boundary; the
corrected protocol still needs a new run. No retrospective source seal is
claimed to have existed before the completed run.

Both actual migration-transformer orders also pass conversion, restart and
operation. That test uses a bounded seventh test mod and audits the woven
callback order. It is separate from the normal six-jar profile, does not change
the production jars and is not a production launch mode.

## Still Required

The no-Base-Metals profile currently fails during OreSpawn's startup tag lookup
with `IllegalStateException: Tags not bound`. The normal Base Metals profile
passes, but that does not qualify the optional-dependency case. The failed
startup is retained and must be resolved or explicitly reported before calling
the whole suite green.

The two additional chained 1.13 dimension fixtures exposed a biome lookup and
four old Forge registry names. Those repairs now pass strict world tests,
including all four Steam enchantments with their levels and inventory slots.
The complete enchantment index is archived, not discarded; uppercase FML and
numeric-ID archives remain untouched. Conflict, malformed-input, idempotence
and byte-exact backup controls pass. Four precisely identified removed vanilla
sound indexes are retired; unknown names still remain unresolved. The final
aggregate remains pending the separate OreSpawn and full-suite gates.

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

The Base Metals 3.1.2.121111 shared migration hook is local-only. Do not
publish this candidate against the older public dependency or describe it
as ready for release. Always protect the original save and test a copy.
