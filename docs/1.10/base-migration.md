# Moving From Advantage To basePower

basePower, baseSteam and baseElectric continue the released Minecraft 1.10.2 Advantage mods under new names. The original repositories have not been renamed or removed.

## Before Opening A World

1. Close Minecraft and make a complete backup of the world and its configuration.
2. Remove the old Power Advantage, Steam Advantage and Electric Advantage jars. Install the matching base replacements together; do not mix old and new expansion jars.
3. Keep the required Base Metals and OreSpawn dependencies installed. Optional converter content still needs the corresponding power mod.
4. Test a copy first. Check inventories, fluids, batteries, machine directions and ongoing jobs, then close Minecraft completely and open the copy again.

The supported historical source is the released Cyano Minecraft 1.10.2 line. Later base releases will migrate from the base identities. Unpublished intermediate Advantage ports are not a separate compatibility promise.

## What Changes

| Original mod ID | Replacement mod ID | Display name |
| --- | --- | --- |
| `poweradvantage` | `basepower` | basePower |
| `steamadvantage` | `basesteam` | baseSteam |
| `electricadvantage` | `baseelectric` | baseElectric |

Existing block and item names retain their path, with the new namespace. Forge's registry remapping retains the saved numeric IDs and metadata rather than rebuilding machines. Tile entities accept the released IDs and write their new canonical IDs on save. An unknown owned component is a migration failure, not permission to replace it with air.

Fluid registry names such as `crude_oil` and `refined_oil`, machine NBT keys, quantities and processing rules are not being renamed for branding. The legacy `cyano.poweradvantage.api` and supported compatibility facades remain available to integrations.

The four musket enchantments also keep their original `steamadvantage` registry keys. Forge 1.10 cannot remap that registry in the same way as blocks and items. Keeping those keys and their registration order preserves enchantments on existing weapons; it does not mean the old Steam Advantage jar should remain installed.

Forge also saves the owning mod for each default fluid in the world header. The migration converts the two Power oil owners before Forge reads that header and retains byte-for-byte copies of changed headers in `basepower-migration-backups`. These header copies are an audit trail, not a substitute for backing up the complete world. Restore the complete original backup to roll back an upgrade, not an old header alongside converted chunks.

The saved mod list is also translated, but only for replacement mods actually installed. Other mods are not relabelled or hidden. Legacy read aliases must remain available in later base ports: unvisited chunks and offline players can still contain old tile or item IDs even after the world's header has been updated.

If a new base configuration does not exist, the matching old `.cfg` is copied. The old file is kept, and an existing base configuration wins. Additional Loot Tables files are imported without overwriting base customisations; old copies are retained under inactive backup suffixes so both sets do not inject loot.

OreSpawn configuration requires the same care: user choices must remain authoritative and an old rule must not be activated alongside a new default for the same deposit. Provider migration is covered separately from block remapping.

## Validation Boundary

The regression work uses disposable, hash-checked copies of original-release fixtures. It checks saved state, real machine operation, full process restarts and a measured 30-minute finite-resource endurance job. Generated worlds and reports are local evidence, never release contents.

Automated checks do not replace visual and gameplay acceptance. A build or server-start pass alone is not proof that a world migrated without losing contents. Current candidate results and any remaining gaps must be reported against the exact tested jars before release.

The new CurseForge projects have not been assigned numeric project IDs in this source yet. Publication must remain blocked until those IDs are supplied; the old Advantage upload targets must never be reused.
