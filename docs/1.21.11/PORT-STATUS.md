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

## Checks So Far

- Production and test compilation pass.
- All 249 Power unit tests pass, without skips.
- Native resource discovery reads 261 client and 160 server files, with no
  warnings or errors. Opposite-format and missing-metadata controls remain.
- A disposable packaged Power world starts, saves and reopens in a new
  process with the exact local Base Metals migration-hook candidate.
- The new Python adapters have positive and negative controls for command
  acknowledgement, logging custody, item state and saved terrain.

The Windows Forge/Netty startup probe is also run without mods. The test
launcher retains normal stack traces and all logging levels, avoiding the
extended formatter's attempt to initialize unsupported native OS classes.
Only exact, reproduced DEBUG capability probes are classified as platform
observations. ERROR events, different causes, extra exceptions and mod frames
remain failures; the original failed logs are retained.

## Still Required

The combined machine, upgrade, actor/dimension, chunk/unload, recipe,
cross-mod oil, world-generation and endurance suites are not complete.
Full archive, Eclipse, cold-cache, path-with-spaces and reproducibility
checks remain, followed by actual client visual and manual play checks.
Earlier-version results do not qualify these jars.

The Base Metals 3.1.2.121111 shared migration hook is local-only. Do not
publish this candidate against the older public dependency or describe it
as ready for release. Always protect the original save and test a copy.
