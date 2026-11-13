# Minecraft 26.3 Release

Published on 13 November 2026. Base Metals and OreSpawn were published first,
then basePower. Both expansions pin the verified public basePower release.
Their independent uploads processed in parallel, and all five releases are now
publicly verified. Automated checks and manual testing are complete.

## Final Release Versions

| Mod | Version | Player Jar SHA-256 |
| --- | --- | --- |
| basePower | 2.4.18.2603001 | `97525CE9C75509C55225E0061D43BAF9D29BDBDE6DF853E1552A64A54C14C3DE` |
| baseSteam | 2.2.19.2603001 | `770E46CF87FEE3CC1EFF73C666858BFC03D6922E866B4C07C0A093B746B2CF44` |
| baseElectric | 2.2.19.2603001 | `7DD8DD0D2348360C41C64BDE9ADC88E95284842DE1FCEA73CEBC9A4938AD7819` |
| Base Metals | 3.1.2.2603001 | `B03388D10E5B83046A5D2D540E7D956202A8A6CC78B2302A412D3092192CAAE8` |
| OreSpawn | 4.1.1.2603001 | `7FBAFEFF97683DA0E7ABD0035B85C3A19276B3AD9CB50DBC071F9993B33E490D` |

The [qualification record](QUALIFICATION.md) lists the machine, recipe,
world-upgrade, client, full-restart and endurance checks. It also records
the optional checks that were not run. Earlier failed attempts remain
separate from the final passing runs.

Electric's public API pin changes only its manifest. The final release family
passed a fresh operating 1.10 upgrade, two real 30-minute endurance worlds,
cold restarts and independent saved-world replay. The qualification record
keeps that evidence separate from the original manually accepted test kit.

## Public Downloads

- [basePower 2.4.18.2603001](https://www.curseforge.com/minecraft/mc-mods/basepower/files/9589309)
- [baseSteam 2.2.19.2603001](https://www.curseforge.com/minecraft/mc-mods/basesteam/files/9589874)
- [baseElectric 2.2.19.2603001](https://www.curseforge.com/minecraft/mc-mods/baseelectric/files/9590229)
- [Base Metals 3.1.2.2603001](https://www.curseforge.com/minecraft/mc-mods/base-metals/files/9589195)
- [MMD OreSpawn 4.1.1.2603001](https://www.curseforge.com/minecraft/mc-mods/mmd-orespawn/files/9589145)

All three base* mods are also on [MMD Maven](https://maven.moddev.zone/releases/)
and have GitHub releases:

- [basePower release](https://github.com/MinecraftModDevelopmentMods/basePower/releases/tag/2.4.18.2603001)
- [baseSteam release](https://github.com/MinecraftModDevelopmentMods/baseSteam/releases/tag/2.2.19.2603001)
- [baseElectric release](https://github.com/MinecraftModDevelopmentMods/baseElectric/releases/tag/2.2.19.2603001)

Maven coordinates:

```text
zone.moddev.mc.basepower:basePower:2.4.18.2603001
zone.moddev.mc.basesteam:baseSteam:2.2.19.2603001
zone.moddev.mc.baseelectric:baseElectric:2.2.19.2603001
```

## Publication Checks

Source changes went through the fork and checked MMD pull requests. The release
workflow used the exact merged source and audited CI artifacts. Each base* player
jar was downloaded anonymously from CurseForge, Maven and GitHub and matched
the qualified checksum. Sources and Javadoc from Maven and GitHub matched CI;
the POMs and Maven indexes were checked too.

| Mod | Exact Release Source |
| --- | --- |
| basePower | `4fae0a51feb3e49875f09c500b869266122a711a` |
| baseSteam | `5e50614d4f5d7c162ca3394f3530c0b3201f5254` |
| baseElectric | `22d0a9e962bb04382ff2cc75715fd85364be927c` |

Each player jar was uploaded to CurseForge once. When processing delayed its
public download, publication resumed with the existing file ID. No version,
tag or release file was replaced. Later documentation commits do not change
the source or artifacts pinned by these releases.

The source ports are merged in MMD: [Power #46](https://github.com/MinecraftModDevelopmentMods/basePower/pull/46),
[Steam #41](https://github.com/MinecraftModDevelopmentMods/baseSteam/pull/41) and
[Electric #40](https://github.com/MinecraftModDevelopmentMods/baseElectric/pull/40).
The player builds, sources and Javadoc are published through the guarded Maven,
CurseForge and GitHub workflow, not by rebuilding a release from a later branch head.
