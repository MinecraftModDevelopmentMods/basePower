# MMD Maven releases

## New releases

Use **Release basePower** on the MMD repository's default branch. After the
version branch passes CI, tag the exact tested commit and add the CI run, artifact
ID, archive SHA-256 and tested main-jar SHA-256 to `releases.json`. Run
**check-only** first, then **publish**. The workflow uploads the checked player
jar to CurseForge, verifies the public download, and publishes those same bytes
to MMD Maven before creating the GitHub release.

If CurseForge has accepted the file but it isn't public yet, wait for approval.
Start a new run with the recorded CurseForge file ID so it verifies that upload
without submitting the jar again. The release workflow sets Java 8 through
Minecraft 1.16.5, Java 16 for Minecraft 1.17.1, Java 17 through Minecraft 1.20.1,
Java 21 for Minecraft 1.20.6 through 1.21.11, and Java 25 for Minecraft 26.1.2,
26.2 and 26.3. The 26.3 line has passed automated and manual testing.
Unknown version branches are rejected until their release target is reviewed.

## NeoForge 1.21.1

The only native target is `master-1.21.1-neo`: Minecraft 1.21.1, Java 21,
loader `neoforge`, and version suffix `.121012`. Forge 1.21.1 keeps
`.121011`. The branch selects the loader; an optional `loader` field in a
release record must agree. Other native branches and mismatched codes are
rejected.

The upload name uses the selected loader. Native releases add MMDLib
(`261744`) as a required CurseForge relation; Forge relations stay unchanged.
CI must upload exactly one checksum file, `build/release/SHA256SUMS`, sealing
the three release jars. Do not upload a second copy from `build/libs`.

This support adds no release record or approval. Record only the actual merged
commit, matching tag, successful branch CI run, artifact ID and exact archive
and jar hashes. Check public dependencies in order: basePower, baseSteam, then
baseElectric. Run check-only before publishing and resume an accepted
CurseForge upload with its existing file ID.

## Existing CurseForge releases

Use **Actions > Publish to MMD Maven** on the MMD repository's default branch.
Select a version listed in `releases.json` and run **check-only** first. Once it
passes, run **publish** for that same version.

This workflow publishes the exact main, sources and Javadoc jars retained by the
successful CI run, plus a POM. It checks the archive and jar checksums and
compares the main jar with the existing CurseForge download. It does not build
the mod, move tags, change versions or upload to CurseForge. An identical Maven
release is a no-op; different bytes or a partial upload need investigation.

To add a release, record its CurseForge file ID, CI run, source commit, artifact
ID and archive checksum in `releases.json` after checking the public download.
Include its tag when one exists. The CI archive must still be available.
