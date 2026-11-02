# basePower

**A Base Metals expansion continuing DrCyano's Power Advantage, with backwards-compatible upgrades for existing Minecraft 1.10.2 worlds.**

basePower brings item transport, fluid handling and the shared foundation for steam and electrical machinery to your workshop. It continues [DrCyano's original Power Advantage](https://www.curseforge.com/minecraft/mc-mods/power-advantage), keeping its familiar machines and approach to automation while fixing bugs and maintaining compatibility with existing saves.

This is the continuation under a new name, not an extra mod to install alongside Power Advantage.

## What Does It Add?

- **Conveyors:** Move items between inventories, collect dropped items and carry them upwards. No power supply needed.
- **Sorting and filters:** Separate items into different storage routes, filter by category or selected items, and handle overflow.
- **Fluid transport:** Collect water, lava and other fluids with drains, move them through pipes, and return them to the world with discharges.
- **Storage and control:** Store fluids in portable or metal tanks, select accepted fluids and control pipe connections with redstone switches.
- **Workshop materials:** Make sprockets, steel frames and bioplastic for your machinery.
- **Oil processing:** Collect crude oil and distil it into refined oil. OreSpawn handles underground desert deposits, and Mineralogy's crude oil is accepted by the appropriate processing machinery too.

basePower is useful by itself. Its companion mods add the steam and electrical equipment; installing basePower alone does not add their generators, drills or processing machines.

## Expand Your Workshop

- **[baseSteam](https://github.com/MinecraftModDevelopmentMods/baseSteam):** The continuation of Steam Advantage, adding boilers, steam pipes, tanks and steam-powered machinery.
- **[baseElectric](https://github.com/MinecraftModDevelopmentMods/baseElectric):** The continuation of Electric Advantage, adding generators, batteries, wiring and electrically powered machinery.

Both use basePower as their shared foundation. Install matching versions for your Minecraft release.

## Requirements

- **Minecraft 1.10.2 with Forge 12.18.3.2511 or a compatible later 1.10.2 Forge build.**
- **Java 8.**
- **[Base Metals](https://www.curseforge.com/minecraft/mc-mods/base-metals).**
- **[MMD OreSpawn](https://www.curseforge.com/minecraft/mc-mods/mmd-orespawn).**

The current 1.10.2 build has been tested with Base Metals `2.5.1.110021` and OreSpawn `4.1.2.110021`, including existing-world upgrades and full restarts. Steam and Electric are optional companions, not requirements for basePower's conveyors and fluid equipment.

## Moving An Existing Power Advantage World

basePower supports upgrading worlds made with the original released Cyano Minecraft 1.10.2 Power Advantage. Existing blocks, items, inventories, stored fluids and machine state are migrated to the new identities without needing to rebuild your installations.

1. Back up the complete world and its configuration before changing any mods.
2. Remove Power Advantage and install basePower. Do not keep both jars installed.
3. If the world also uses Steam Advantage or Electric Advantage, replace them with matching baseSteam and baseElectric versions at the same time. Do not mix the old and new families.
4. Open a copy first, check your machines and contents, then close Minecraft completely and reopen it to check the saved result.

Existing configuration is carried forward when no new base configuration exists, and saved OreSpawn settings remain authoritative. Keep your original backup: rolling back means restoring the complete backup, not putting old jars back into an already converted world.

This compatibility applies to the released 1.10.2 worlds. It is not a promise that every old third-party add-on works unchanged; integrations which look for the old mod IDs may need an update.

## Credits, Modpacks And Development

Power Advantage was created by **DrCyano (Cyanobacterium)**. basePower continues that work as part of **Minecraft Mod Development**, alongside Base Metals, baseSteam and baseElectric.

Modpack inclusion is welcome. The project remains open source under **LGPL-3.0**. Its developer API includes the preserved `cyano.poweradvantage.api` package for the 1.10.2 line.

[Source code](https://github.com/MinecraftModDevelopmentMods/basePower) | [Original Power Advantage](https://www.curseforge.com/minecraft/mc-mods/power-advantage)
