# Minecraft 1.17.1 Base Mods

These are local builds, not releases. The gameplay tests below use the exact
player jars listed here. The combined Eclipse client tests use the corresponding
development build. Back up the entire world and only open copies when testing
an upgrade.

| Mod | Version | Player jar SHA-256 |
| --- | --- | --- |
| basePower | 2.4.9.117011 | `C55782CA1B0F3B8F7B358BE3150229F5AD92F8C8CE15E3989AAAD1B4F182886F` |
| baseSteam | 2.2.10.117011 | `8A9207A89C0971501BB649FC47F0FF3CF6132FCE3EB2AE651CD8437406BA191B` |
| baseElectric | 2.2.10.117011 | `9788E9DA4DD7698D0D53FC66246ED0637FC6EB89A855B7CFCC4AFFC6EFF7559F` |
| Base Metals (local) | 3.1.1.117011 | `F3053F23D2415476FC52426A24E3B0B828198A678FBD5DD789923DB2063E0FEB` |

The local Base Metals patch moves common setup work to Forge's queued setup
thread. It fixes a startup deadlock seen when both projects initialized the
Forge handshake together. The packaged checks covered ten client/server login
and reload scenarios, including settings mismatches. OreSpawn is the public
4.1.0.117011 release.

## What Passed

- The three reproducible release builds, dependency and archive audits, and
  Eclipse production classpaths. The combined client launch is `runClientAllAdvantage.launch`.
- All 21 walkable works stations with and without Base Metals, before and after
  a full restart, including an independent audit of the assertions.
- 132 finite machine jobs and their cold-restart checks. A second campaign
  imported the 132 existing setups from 1.16.5 without reconstructing them.
- Original Advantage 1.10.2 and released base* 1.10.2 and 1.12.2 saves kept
  their machine state and turbine UUIDs, and resumed conveying, crushing and
  smelting. The chained base* 1.16.5 fixture retained all 14 state witnesses.
  The original 1.10.2 and legacy 1.12.2 player books kept their enchantments
  and player UUIDs in the saved files after both boots.
- Both 30-minute load runs (fresh and upgraded), the full server restart, and
  processing after restart. The independent audit checked raw checkpoints,
  inventory acknowledgements and unchanged inputs.
- Recipe unlocks and natural progression in all three recipe modes. The
  combined client checked recipe-book grouping, creative entries, models,
  GUI layout and required labels, including intentionally broken controls.
- Mining and drops, Forge capabilities, native tank reload, both energy
  converter directions, and all six crude/refined oil lanes including Mineralogy.
- Partial networks with the supply, machine or bridge genuinely unloaded.
- Worldgen and restart checks for the standalone, Mineralogy and legacy OreSpawn
  profiles, including preservation of user changes to generation rules.

## Still Open

The full historical harness is not green. Some preservation checks still
require the pre-rename fixture and script bytes at paths now used by the active
base* tests. Their historical checksums were not updated to pass. The remaining
actor/dimension and transformer-order routes need a current run before calling
the complete upgrade matrix qualified. The manual walkthrough is separate,
including live turbine orientation, sounds and player controls.

Raw reports and the frozen manifest are in the ignored `test-rig/build/`
directories for the `base117-20261107` campaign. The earlier Advantage runs
and failed attempts are retained separately. None of those files are release
content.
