# Minecraft 26.3 Test Results

Publication note, 13 November 2026: manual testing is now accepted. This
document retains the original automated handoff and its exact candidate
hashes. See [release status](RELEASE.md) for later publication steps.

The three current candidates have passed the automated checks below and are
ready for manual testing, not release. Everything for 26.3 remains local; no
implementation or dependency patch has been pushed or published.

## Exact Test Kit

| Mod | Version | Player Jar SHA-256 |
| --- | --- | --- |
| basePower | 2.4.18.2603001 | `97525CE9C75509C55225E0061D43BAF9D29BDBDE6DF853E1552A64A54C14C3DE` |
| baseSteam | 2.2.19.2603001 | `770E46CF87FEE3CC1EFF73C666858BFC03D6922E866B4C07C0A093B746B2CF44` |
| baseElectric | 2.2.19.2603001 | `44ED38BFC7040D73251E523B2803718CDA2212482268A7E65BCF4681CF9A75E8` |
| Base Metals | 3.1.2.2603001 | `B03388D10E5B83046A5D2D540E7D956202A8A6CC78B2302A412D3092192CAAE8` |
| OreSpawn | 4.1.1.2603001 | `7FBAFEFF97683DA0E7ABD0035B85C3A19276B3AD9CB50DBC071F9993B33E490D` |
| Advantage Works test harness | 1.1.8.2603001 | `6DCCB4EB2EF8245DA84D731EDD1DEB0550E3754116053C002532FC8D18CE7CCA` |

Base Metals and OreSpawn are installed throughout. These are the separately
approved local migration-hook and retained-generation-settings patches, not
interchangeable public downloads. Their source pins are in `gradle.properties`.
The harness and graphical probe are test mods, never part of the player jars
or the normal Eclipse launch.

The full baseline is Forge 66.0.0, Minecraft 26.3, MCP 20260918.230105 and
Temurin 25.0.3+9. Native data version is 5023; data/client formats are
121.0/97.1. Frozen profile04 has SHA-256
`9008376EFAB5B223DAFBDFE8EA24C405F704A99AFD8B6ED158BF9E06BA0D527E`.
Its original exploratory label is retained. Qualification comes from the
completed, hash-bound results, not a rewritten input label.

## Completed Checks

- Release, dependency, bytecode, model, recipe, archive, checksum and production-only Eclipse checks for all three mods.
- Power's 385 tests, Steam's 291 tests and Electric's 119 unit plus 30 native-resource tests: 825 in total, with no failures or skips in those inventories.
- Separate Eclipse-compiler checks, genuinely empty Gradle/native SDK caches, dependency/cache paths containing spaces and independent matching player, sources and Javadoc archives.
- Harness unit, adapter and client-probe contract checks, kept separate from actual game-process and graphical evidence.
- Walkable machine stations, capability and public fluid API transfer, finite processing jobs, harvesting and stopped-save comparisons after complete restarts.
- Real chunk unloading, partially unloaded networks, bridge reconnection, all drill facings and extended drill movement.
- Ingredient-based recipe discovery and survival manufacturing in Normal, Technology Progression and Apocalyptic modes, including switching back to Normal without losing discoveries.
- Oil bucket placement, drains, pipes, tanks and distillation through full restarts, with fluid quantities checked.
- Fresh generation, editable disabled alternatives, legacy imports and retained original-1.10/released-1.10/released-1.12 generation settings.
- Eleven operating machine-upgrade routes: original Cyano 1.10; released base* 1.10 and 1.12; retained 1.19.4, 1.20.1, 1.20.6, 1.21.1, 1.21.11 and 26.1.2; and both the accepted and exact published 26.2 families. The earlier 26.2 fixture predates Electric's publication-only manifest update; the extra route uses the public `2928E1C6...` jar. Imported connected machines were operated without rebuilding or replacing them.
- Eleven player/entity routes, including Nether, End and chained conversions. Five are strict passes; six retain the exact native wolf differences described below. Both actual migration-hook orders were tested separately for machines and actors.
- A genuine 26.2 saved goat containing the retired temptation memory, converted and restarted without losing its other memory or the archived old value.
- A real graphical client, including complete process shutdown and reopening its stopped world: 39 GUIs, 351 caption checks, 26 tank cases and 1,248 fenced GPU frames on each boot. Native tooltip, blank/garbled gauge, missing-label and repeated-background controls must still reject their deliberate faults.
- A separate production-only replay of the generated combined Eclipse launch: five exact player mods, no test harness or graphical probe, and a distinct-process reopen of the same disposable world. Path relocations and native Quick Play/offline-development additions are recorded explicitly. This does not claim that Eclipse was clicked, or that Microsoft/Realms authentication was tested.
- Two real 30-minute endurance worlds, one fresh and one upgraded from original 1.10, followed by full process shutdown, restart and resumed processing. Independent frozen replay checked all 66 checkpoints, 1,056 station snapshots and 526 stopped-state rows. Minimum measured interval throughput was 19.60 and 19.31 ticks per second.
- Additional bounded dedicated-server checks on Forge 66.1.5, including 132 finite machine jobs and a stopped-save/reopen comparison. This is separate compatibility evidence, not the full 66.0.0 client/endurance suite on another Forge build.

## Repairs And Test Findings

The port uses native cooking-fuel components, trade data, pending material
tags, recipe tags and loot modifiers rather than retaining obsolete resource
formats. Whole-table loot checks compare with an independent accepted-26.2
fixture. A real portable-tank break/place test also checks retained fluid.

Recipe advancements remain available while a mode-specific recipe is inactive.
An optional native recipe tag avoids a missing recipe holder while preserving
ingredient discoveries when the mode changes. All three modes and the return
to Normal were exercised through actual server restarts.

Minecraft removed the previously saved `minecraft:is_tempted` memory index.
Only that exact obsolete index is retired. Its payload is archived under
`ForgeData.basepower:retired_brain_memories`; other brain memories remain
active. Malformed or conflicting data is not silently overwritten.

The first world-generation restart check expected a changed gamerule reply
when the value was already correct. The setup now queries the actual value;
it does not accept a failed assignment. A separate obsolete legacy-import
shortcut expected modern alternatives to disappear. The maintained contract
instead proves that complete alternatives remain visible and disabled, with
saved legacy settings authoritative. Both earlier failed results are retained.

One old actor reference captured dropped items before the source server's
last gravity tick and save. A new reference was independently derived from
the untouched stopped source, not the converted result. All original fields
remain compared, and the old reference still fails as a negative control.

The first graphical run rejected every screen's inventory tooltip because
the probe watched an older native method overload. The repaired instrumentation
forwards the current method's boolean unchanged, and a real native-render
counterfactual proves why the old hook missed the tooltip. The frame verifier
also rejected a changed probe-class identity; the probe was corrected without
widening the verifier. A saved-world load then exposed a pre-login timing race.
The probe now completes its genuinely worldless model check before asking
Minecraft's native world-opening flow to open the existing save. Fresh and
full-reopen graphical runs pass; the failed attempts remain unchanged.

## Limits And Manual Checks

Six dimension routes retain `PASS_WITH_NATIVE_DIFFERENCE` for Minecraft's own
tamed-wolf health/max-health conversion. Independent vanilla controls
reproduce only those exact changes; UUIDs, ownership, contents and every other
compared field still have to agree. Instrumented hook-order proofs do not
replace the unmodified player-jar migration runs.

This Windows host has unavailable Perflib counters and native Java-25 Netty
fallbacks. Independent controls capture the reviewed complete diagnostics.
Unexpected errors still fail; no logging category is suppressed.

Later boots omitted three native SystemReport failures that earlier boots
had emitted. Independent zero-mod controls reproduced that complete two-block
host sequence. The client policy accepts only the two independently captured
complete variants, not arbitrary counts or partial traces. The extra exact-
public-26.2 server route has a separately sealed, evidence-local adapter that
retains the stock policy and adds only the independently proven server variant.
All nine logs in the final operating run passed the unchanged stock classifier;
the fallback was not needed. Its machine assertions, raw logs, player jars and
source fixtures remain unchanged. The core engine proof and
all 464 sealed endurance inputs are unchanged; no gameplay exception was waived.

Three optional Mineralogy-specific oil assertions are explicit skips because
the matching optional mod is absent. These results do not qualify an entire
modpack, every third-party interaction or unrelated mods' world conversion.
Turbine orientation/spin, weapons and the ordinary feel of play still need
human inspection.

Use Java 25 and the generated `runClientAllAdvantage.launch` in the existing
Power project. Refresh the Gradle
project and let Eclipse finish building. Both local dependency patches and
the exact matching expansion jars must remain installed.

Back up real worlds and test disposable copies only. Check that every other
installed mod supports the target version. Never reopen a converted save in
an older Minecraft version.

## Local Evidence

Frozen jars, input seals, logs, stopped saves, failure receipts and graphical
frames are retained under
`D:/PowerAdvantage/_verification/base-263-port-20261112/` and
`test-rig/build/native263-current04/`.

`harness/endurance-current04-01-FINAL.json` records the genuine pre-run seal,
owned zero-exit stops and independently frozen replay. Its receipt SHA-256 is
`89EF39541E26109583A38243B1E6C0DEC09CAC153BCD5C823DF1220B8BEEEC5A`.
`children/actors-orders-profile04-01/actor-matrix-results-03.json` lists the
eleven routes and exact per-route receipts. Earlier failed attempts remain
unchanged.

The final client evidence is under `client-final-family04-01/`; the independent
28-test Eclipse-compiler proof is `harness/ecj-clientprobe-11-02/FINAL.json`.
The exact-public-26.2 operating rerun is
`harness/published262-upgrade-host2-03-FINAL.json`. The ordinary offline client
launch/reopen proof is `production-client-family04-01/attempt-03/results.json`.
These are separate scopes, not substitutes for the original endurance run.

`qualification-final-family04-01.json` indexes the 38 completed campaign and
supporting receipts and the 11 actor routes, and rechecks the actual local
player jars, Java class versions, 825 production tests and tracked-file hygiene.
Its SHA-256 is
`30ABF76B62590125BF7981AB6E4F396B0DE334AD3A47E3FDBE1B88EB7FE1135A`.

See [the rig instructions](../../test-rig/NATIVE263.md) to repeat the checks.
Manual acceptance, dependency publication, hosted CI, push/merge and release
remain separate steps.
