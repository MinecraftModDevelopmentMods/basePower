# Minecraft 26.2 Test Results

The three local candidates have passed the automated checks below. They are
ready for manual testing, not release. No 26.2 implementation or dependency
fix has been pushed or published, and hosted CI has not qualified these pins.

## Exact Test Kit

| Mod | Version | Player Jar SHA-256 |
| --- | --- | --- |
| basePower | 2.4.17.2602001 | `7E456DE1A45140E86415A701D186FEDE08C4AB63D53C6B7848935DB70CBC68B1` |
| baseSteam | 2.2.18.2602001 | `EDD10D6ED4A623F6EAA364D21480FDA3B777FB15C2195D437882DB09C0F415AB` |
| baseElectric | 2.2.18.2602001 | `A7D12A66CA91A6BACCFEFD2CED9D3EC962F5FEB73BF632E1683F99084384F938` |
| Base Metals | 3.1.2.2602001 | `15CBD0C8DD95BBA846969929B8767887BA4B8152DFB10C9C58FB314AD39FB9E1` |
| OreSpawn | 4.1.1.2602001 | `BC0658DF2E149CC23E65E5771F3FF62E2BB13FFDA4E316AB632EB92EF7FA5A1F` |
| Advantage Works test harness | 1.1.7.2602001 | `9914D684980E3980257CB2097F972F7128A670F3B814EBEB019AF73D7A2E563E` |

Base Metals and OreSpawn are installed throughout. Both include approved
local fixes, not public replacements: Base Metals' shared migration extension
and OreSpawn's retained-provider-settings repair. Their exact source pins are
in `gradle.properties`. The harness is a separate server test mod, never
included in any player jar or normal Eclipse launch.

The target is Forge 65.1.0, official 26.2 names, MCP 20260616.103818 and
Temurin 25.0.3+9. Native data version is 4903; data/client formats are
107.1/88.0. Frozen profile03 has SHA-256
`A98D4F9B54A0AE47392D9A3CF81335F5E228F718C7D322DF9838A94AD27D9736`.
Its pre-run exploratory label is unchanged. Completed evidence supplies the
verdict; sealed inputs and earlier failures were not relabelled.

## Completed Checks

- Release, dependency, class-version, model, recipe, archive, checksum and production-only Eclipse audits for all three mods.
- Power's 357 unit tests and fresh Eclipse compiler run; Steam's 276 tests and Eclipse run; Electric's 117 unit tests, 28 native checks and Eclipse run. No test failures or skips in these inventories.
- Independent empty Gradle/native SDK caches and paths containing spaces, with matching player, source and Javadoc archives. The independent SDK's complete entry payloads match; ZIP container headers are not treated as game-code differences.
- The 389-test Java harness, 265 current Python adapter tests and 24 worldless client-probe tests, kept separate from actual gameplay and GPU evidence.
- All 21 walkable works bays and 12 drill witnesses, capability/API automation, finite machine jobs, harvesting and stopped-save comparisons after full restarts.
- Physical chunk unloading, partially unloaded networks, bridge reconnection and extended moving-drill scenarios.
- Recipe discovery and survival manufacturing progression in all three modes, with rubber enabled and disabled; drain, pipe, tank and oil-processing lanes across full restarts.
- Fresh generation, legacy imports and retained original-1.10/released-1.10/released-1.12 profiles, including new-chunk sampling and saved user settings.
- Nine operating machine-upgrade routes: original Cyano 1.10, released base* 1.10 and 1.12, retained 1.19.4, 1.20.1, 1.20.6, 1.21.1 and 1.21.11, plus the newly promoted 26.1.2 kit. No rebuilding or replacing the imported machines was needed.
- Nine player/entity routes including Nether, End and chained conversions, with source immutability and stopped-data comparisons. Both actual migration-hook orders were tested separately for machines and entities; instrumented ordering proofs are not substituted for unmodified player-jar campaigns.
- A real graphical client: 39 screens, 351 caption checks, 26 tank-gauge cases, three window/GUI scales and 1,247 fenced GPU frames. Recipe-book groups, world oil rendering, resource reloads and deliberate negative controls were exercised.
- Two genuine 30-minute endurance worlds, one fresh and one upgraded from original 1.10. Both saved, stopped completely, restarted and resumed processing. Independent replay validated all 66 checkpoints, conservation, interval clocks and final raw saved state. Minimum measured interval throughput remained above 19 ticks per second.

## Findings And Limits

No additional machine-balance repair was needed during these campaigns.
The port translates the current native GUI/rendering interfaces and adds the
new sulfur-caves biome to saved-registry validation. The test harness uses
the new GPU submission contract before reading its fenced frames.

Six dimension fixtures retain `PASS_WITH_NATIVE_DIFFERENCE`. Their strict
comparisons still record Minecraft's own tamed-wolf health/max-health scaling.
Independent controls reproduce exactly that difference without Advantage;
ownership, UUIDs and all other compared fields must still agree.

This Windows host has unavailable Perflib process counters. Independent
worldless native controls reproduce the exact OSHI diagnostics and Netty
Java-25 capability fallbacks. Only reviewed complete log sequences are
classified; changed levels, threads, frames and unrelated exceptions still
fail. No log category is suppressed and the host has not been repaired.

Three Mineralogy-only oil assertions are explicit skips because that optional
mod is absent. This test kit is not qualification of an entire modpack or of
unrelated mods' migration. Automated child-model geometry does not replace
the remaining in-world turbine orientation/spin check.

## Manual Checks

Use Java 25 and the generated `runClientAllAdvantage.launch` in the existing
Power project. Both local dependency patches and the matching child jars must
remain installed. Refresh the Gradle project, let Eclipse finish building,
then check the shared creative tab, normal screens, recipe discovery, world
models, turbines, weapons and the ordinary play experience.

Back up real worlds and test disposable copies only. Check other installed
mods before attempting an upgrade. Do not reopen a converted world on an
older Minecraft version.

## Local Evidence

Original logs, command records, native controls, frozen jars, checkpoints and
client frames are retained under
`D:/PowerAdvantage/_verification/base-262-port-20261112/` and
`test-rig/build/native262-current03/`. Player/entity and ordering results are
under `children/actors-orders-profile03-16/` and its referenced rig outputs.

The genuine endurance input seal is `endurance-prerun-seal-03.json`.
`endurance-independent-replay-03.json` records the stopped, separately frozen
replay and confirms that the seal predates setup with no custody gap.
Failed setup, SDK-transition and native-platform diagnostic attempts remain
separate from the final passing runs.

See [the rig instructions](../../test-rig/NATIVE262.md) to repeat the checks.
Manual acceptance, public dependency review, push/merge, tags and publication
remain separate steps.
