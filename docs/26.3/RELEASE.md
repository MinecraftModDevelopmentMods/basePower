# Minecraft 26.3 Release

The automated checks and manual testing are complete. Base Metals, OreSpawn
and basePower are public. baseSteam is processing at CurseForge; baseElectric's
matching release is being published in parallel. Both expansions pin the
exact public basePower release and pass their own publication gates.

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
- [Base Metals 3.1.2.2603001](https://www.curseforge.com/minecraft/mc-mods/base-metals/files/9589195)
- [MMD OreSpawn 4.1.1.2603001](https://www.curseforge.com/minecraft/mc-mods/mmd-orespawn/files/9589145)

Steam's existing upload is file `9589874`; the authenticated author dashboard
shows **Processing**, and it is not yet publicly verified.
Electric's public downloads are not yet verified. The expansions do not depend
on one another, so their uploads may process in parallel after Power is public.
Pending files are never uploaded again just to retry the rest of publication.

## Publication Checks

Source changes go through the fork and a checked MMD pull request. The release
workflow uses the exact merged source and audited CI artifacts. Public
CurseForge, Maven and GitHub downloads are checked against those artifacts
before the next mod is released. Existing tags and release files are not
overwritten, and pending CurseForge uploads are resumed by their original
file ID rather than uploaded again.

Power's release source is `4fae0a51feb3e49875f09c500b869266122a711a`.
Its public CurseForge player jar, all three Maven and GitHub jars, POM and
Maven index have been verified. The remaining links will be added after their
downloads are public; an accepted upload is not public-download verification.

The source ports are merged in MMD: [Power #46](https://github.com/MinecraftModDevelopmentMods/basePower/pull/46),
[Steam #41](https://github.com/MinecraftModDevelopmentMods/baseSteam/pull/41) and
[Electric #40](https://github.com/MinecraftModDevelopmentMods/baseElectric/pull/40).
The player builds, sources and Javadoc are published through the guarded Maven,
CurseForge and GitHub workflow, not by rebuilding a release from a later branch head.
