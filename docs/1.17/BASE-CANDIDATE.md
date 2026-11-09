# Minecraft 1.17.1 Base Mods

These are local builds, not releases. The gameplay tests below use the exact
player jars listed here. The combined Eclipse client tests use the corresponding
development build. Back up the entire world and only open copies when testing
an upgrade.

| Mod | Version | Player jar SHA-256 |
| --- | --- | --- |
| basePower | 2.4.9.117011 | `A788C08163CECE1C6D33FBDC295BE6EC272DE9AF7911BE74E707D0EE9121DF73` |
| baseSteam | 2.2.10.117011 | `8A9207A89C0971501BB649FC47F0FF3CF6132FCE3EB2AE651CD8437406BA191B` |
| baseElectric | 2.2.10.117011 | `9788E9DA4DD7698D0D53FC66246ED0637FC6EB89A855B7CFCC4AFFC6EFF7559F` |
| Base Metals (local) | 3.1.1.117011 | `F3053F23D2415476FC52426A24E3B0B828198A678FBD5DD789923DB2063E0FEB` |

The local Base Metals patch moves common setup work to Forge's queued setup
thread. It fixes a startup deadlock seen when both projects initialized the
Forge handshake together. The packaged checks covered ten client/server login
and reload scenarios, including settings mismatches. OreSpawn is the public
4.1.0.117011 release.

## November 8 Baseline

These results used the previous Power jar, SHA-256
`C55782CA1B0F3B8F7B358BE3150229F5AD92F8C8CE15E3989AAAD1B4F182886F`.

- The three reproducible release builds, including clean caches and checkout
  paths containing spaces. Dependency, archive and Eclipse classpath audits
  passed. The combined client launch is `runClientAllAdvantage.launch`.
- Independent Eclipse compiler checks. basePower passed all 201 tests against
  freshly compiled Java 16 output with access restrictions still enabled.
- All 21 walkable works stations with and without Base Metals, before and after
  a full restart, including an independent audit of the assertions.
- 132 finite machine jobs and their cold-restart checks. A second campaign
  imported the 132 existing setups from 1.16.5 without reconstructing them.
- Original Advantage 1.10.2 and released base* 1.10.2 and 1.12.2 saves kept
  their machine state and turbine UUIDs, and resumed conveying, crushing and
  smelting. Chained base* fixtures from 1.13.2 through 1.16.5 kept their
  machine state across both boots. The direct 1.15.2 check compared 14 blocks,
  12 tile entities and 21 item stacks after conversion and restart.
  The original 1.10.2 and legacy 1.12.2 player books kept their enchantments
  and player UUIDs in the saved files after both boots.
- Both live Base Metals/basePower transformer orders on the original 1.10.2
  fixture. Bytecode inspection confirmed both hooks ran in the requested order;
  both resulting saves agreed after restart.
- Both 30-minute load runs (fresh and upgraded), the full server restart, and
  processing after restart. The independent audit checked raw checkpoints,
  inventory acknowledgements and unchanged inputs.
- Recipe unlocks and natural progression in all three recipe modes. The
  combined client checked recipe-book grouping, creative entries, models,
  GUI layout and required labels, including intentionally broken controls.
- Mining and drops, Forge capabilities, native tank reload, both energy
  converter directions, and all six crude/refined oil lanes including Mineralogy.
- Partial networks with the supply, machine or bridge genuinely unloaded.
  Extended drill runs covered four moves per drill, blocked output across a
  restart, then resumed work and redstone controls in fresh and upgraded worlds.
  The separate saved-progress run checked 18 drill routes per world, including
  72 observed moves and the first natural tick after each saved-state read.
- Worldgen and restart checks for the standalone, Mineralogy and legacy OreSpawn
  profiles, including preservation of user changes to generation rules.

The November 8 rerun passed all 2,186 offline checks and 826 Java tests across
the three release builds and test harness. The three rebuilt player jars and the
harness matched their pinned SHA-256 values. The combined client passed 351
caption checks and rejected all 355 deliberately broken caption controls. The
two direct respawn routes passed 24 server-side cases; no real client death
screen was part of that check.

## November 9 Metadata Fix

When a save still lists an old Advantage mod, the world reader now translates
its Forge mod-list entry and data pack selection to the installed replacement.
It does not rewrite registry snapshots, hide other missing mods, or override a
pack choice already saved under the new name. The remap runs in memory; the
next normal save records the current IDs. The artifact diff is one new metadata
class and the world-reader transformer.

The original 1.13-chained regression fixture now boots and restarts without
the old missing-mod or missing-pack messages. Its saved pack list contains
the three current IDs and retains the unrelated entries. The original 1.10.2
fixture also passes with both Base Metals/basePower transformer orders.

The new exact target manifest is `test-rig/build/base117-20261109-a-inputs/inputs.json`,
SHA-256 `F1C023D8F4784D60C2416818BE43A2A8349A422E7E90AFF5D35CA68D1DB3DD05`.
Two clean builds reproduced the Power package and development jar hashes.
Steam, Electric and the harness rebuilt to their unchanged hashes. All 2,187
offline checks and 830 Java tests passed.

- Both 30-minute endurance worlds passed 30 load batches, a full restart and
  resumed processing. The fresh world measured 37,489 ticks and the upgraded
  world 36,048. The independent replay checked 33 checkpoints per world.
- The original 1.10.2 and released base* 1.10.2 and 1.12.2 worlds converted,
  restarted and resumed machine operation. Two newly serialized Overworld
  fixtures passed strict player and entity checks. The 16 Nether and End
  routes passed the independent platform assessment with only the vanilla wolf
  difference below; all six 1.13-derived saves passed raw metadata checks.
  All 36 respawn cases passed, including the previously blocked chained route.
- Both 21-station Works profiles, the 132 fresh machine cases, and the 132
  imported machine states passed their restart checks. The drill and redstone
  reloads, harvest, Forge interoperability, six oil lanes and all three
  world-generation profiles passed.
- Recipe unlocks and all 18 progression cases passed, with 36 independently
  replayed hammer invocations. The combined client passed 351 GUI caption
  checks and rejected 355 deliberately broken controls.

To regenerate the combined Eclipse launch for this local rebuild without
changing the earlier pinned profiles:

```powershell
.\gradlew.bat prepareCombinedEclipseLaunch -PadvantageDevelopmentProfile=test-rig/build/base117-20261109-a-inputs/dev-core.json
```

## Still Open

The earlier full Python run had 120 failures and 44 errors. The offline checks
now read the original Git blobs for historical oracles and check the active
base* adapters separately. The archived checksums and failure reports are
unchanged. The checks require the pinned Git history and the three sibling 1.13
repositories for the historical recipe inventory.

The older 30-route upgrade plan and the eight original Overworld actor routes
still lack their exact source worlds. A search of 1,374 accessible saves did
not find them. All 16 Nether and End source fixtures matched their original
file inventories. Newly generated Overworld fixtures are separate coverage,
not a replacement for the missing originals.

Forge/Minecraft 1.17.1 restores a legacy tamed wolf from 8 to 20 health even
without Advantage installed. The strict comparisons retain that difference;
the separate platform assessment checks every other field without an exception.
The old failing campaign and its logs are kept. The manual walkthrough is
separate, including live turbine orientation, sounds and player controls.

Raw reports and the frozen manifest are in the ignored `test-rig/build/`
directories for the `base117-20261107` and `base117-20261108-qual` campaigns.
The November 8 reruns used the manifest with SHA-256
`1CCC81CAAA597EE2EC2AD7CC134730F1E4A582DB2A79817A07FC6E5005F7C1AB`
unless a profile explicitly adds Mineralogy or omits Base Metals.
The two 30-minute reruns measured 38,346 fresh and 36,038 upgraded game ticks.
Both passed 30 input batches, full restarts and an independent replay of
33 checkpoints each. The earlier Advantage runs and failed attempts are
retained separately. None of those files are release content.
