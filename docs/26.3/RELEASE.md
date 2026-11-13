# Minecraft 26.3 Release

The automated checks and manual testing are complete. Publication is being
prepared in this order: Base Metals and OreSpawn, basePower, baseSteam, then
baseElectric. Each expansion will pin the exact public basePower release.

## Accepted Versions

| Mod | Version | Player Jar SHA-256 |
| --- | --- | --- |
| basePower | 2.4.18.2603001 | `97525CE9C75509C55225E0061D43BAF9D29BDBDE6DF853E1552A64A54C14C3DE` |
| baseSteam | 2.2.19.2603001 | `770E46CF87FEE3CC1EFF73C666858BFC03D6922E866B4C07C0A093B746B2CF44` |
| baseElectric | 2.2.19.2603001 | `44ED38BFC7040D73251E523B2803718CDA2212482268A7E65BCF4681CF9A75E8` |
| Base Metals | 3.1.2.2603001 | `B03388D10E5B83046A5D2D540E7D956202A8A6CC78B2302A412D3092192CAAE8` |
| OreSpawn | 4.1.1.2603001 | `7FBAFEFF97683DA0E7ABD0035B85C3A19276B3AD9CB50DBC071F9993B33E490D` |

The [qualification record](QUALIFICATION.md) lists the machine, recipe,
world-upgrade, client, full-restart and endurance checks. It also records
the optional checks that were not run. Earlier failed attempts remain
separate from the final passing runs.

## Publication Checks

Source changes go through the fork and a checked MMD pull request. The release
workflow uses the exact merged source and audited CI artifacts. Public
CurseForge, Maven and GitHub downloads are checked against those artifacts
before the next mod is released. Existing tags and release files are not
overwritten, and pending CurseForge uploads are resumed by their original
file ID rather than uploaded again.

Public links and final source references will be recorded here after those
downloads have been independently verified. Release preparation is not a
claim that a file is already publicly available.
