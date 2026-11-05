# MMD Maven releases

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
