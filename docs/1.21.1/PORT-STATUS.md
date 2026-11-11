# Minecraft 1.21.1 Port

Status: local qualification in progress. This is not a release approval.

The port starts from the published 1.20.6 basePower source, commit
`d1cdc73d19551786ab4524879cb9abca8c881251`. It uses Forge 52.1.0,
ForgeGradle 7.0.34 and Java 21. The matching baseSteam and baseElectric
ports are being qualified together.

## Dependencies

- MMD OreSpawn `4.1.0.121011`, published file 9189594.
- Base Metals `3.1.2.121011`, local migration-hook commit
  `b42b92b0e6f60ac5baf07a6b74042b2fa8427904`.

The Base Metals patch is not published. Do not substitute the older public
jar for a world-upgrade test. All source worlds and earlier qualification
receipts are kept unchanged; each run uses a disposable copy.

## Port Changes

Recipes, advancement files, loot tables and item tags use Minecraft's new
singular folder names. Their registry and logical advancement names remain
unchanged. Native recipe inputs, block-entity validation and rendering APIs
replace their previous equivalents.

Minecraft 1.21 also changes entity attributes and makes enchantments
data-driven. A regression first demonstrated loss of a modded entity's swim
speed, then verified the repair through the native entity reader. Saved tool
checks now verify the loaded enchantment level, not just the stored JSON/NBT.

## Validation Gates

- Power unit, dependency, identity, resource, archive and Eclipse audits: passed.
- Packaged Power fluid, shared-crushing and full-restart checks: passed.
- Combined machine, recipes, player/entity and world-upgrade checks: pending.
- Partial chunk unload, moving drills and endurance: pending.
- Combined client rendering, screen geometry and recipe book checks: pending.
- Independent cache, path-with-spaces and reproducibility checks: pending.
- Manual gameplay and visual acceptance: pending.

Exact input hashes, failed runs and subsequent results are retained locally
under `_verification/base-1211-port-20261111` and the rig's ignored `build`
directory. These checks qualify only their recorded inputs, not an arbitrary
modpack. Local commits are allowed for dependency pins; pushing, merging and
publication wait for manual acceptance.
