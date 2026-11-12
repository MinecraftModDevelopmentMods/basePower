# Minecraft 26.1.2 Port

This is a local work in progress, not a qualified release. Nothing from the
26.1.2 feature branches has been published.

## Target
- basePower `2.4.16.2601021`, Forge `64.1.5` and Java `25.0.3+9`.
- baseSteam and baseElectric `2.2.17.2601021`.
- Base Metals `3.1.2.2601021`, with the local shared migration hook, and
  OreSpawn `4.1.0.2601021`. Exact source commits and hashes are in
  `gradle.properties`. The Base Metals patch is not public yet.

## Checks So Far
Production compilation and generated Eclipse metadata audits pass with the
new Forge API and the exact local dependency pins. The first complete unit run
executed 298 tests: 294 passed, while four failed. The two migration-test
adapters have been corrected and their 22-test focused rerun passes. The other
two checks remain held until the combined profile has the actual expansion jar
hashes. Another 37 focused resource, trade and GUI extraction checks pass.

These are not rendered-client or gameplay results. Packaged machine operation,
full world migration, endurance, actual Eclipse compilation and manual testing
remain open. An early compile is not evidence that a saved machine works.

The port keeps registry and NBT identities. It replaces the removed villager
trade event with native trade data and uses the current GUI and fluid-model
APIs. Saved world and player paths change in Minecraft 26, so the migration
suite must prove the converted files and machine state after a full restart.

## Remaining Gates
- Full source and native-codec tests, with mutation and rejection controls.
- Exact release archives, bytecode, reproducibility, fresh-cache and Eclipse checks.
- Connected-machine operation, recipe progression, fluids, networks, moving drills,
  chunk unloading and restart checks against the same candidate jars.
- Original Advantage and released base* world, player and entity upgrades,
  additional dimensions, later chunks and both actual migration-hook orders.
- Rendered models, all machine GUIs, captions, gauges, recipe discovery and login.
- Two pre-sealed 30-minute endurance worlds, cold restart and independent exact
  saved-state replay.
- Manual testing, followed by separately authorised publication.

Earlier [1.21.11 qualification](../1.21.11/QUALIFICATION.md) is retained unchanged
as historical evidence. It does not qualify this build.
