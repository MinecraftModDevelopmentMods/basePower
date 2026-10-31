# Minecraft 1.17.1 Port

This is a local port candidate. It has not been approved for normal worlds or
published. Earlier-version results do not qualify this version.

Power Advantage targets Forge 37.1.1 with Java 16 production bytecode. Gradle
runs on Java 17; Mavenizer needs Java 25 and the legacy renamer uses Java 8.
The dependency source commits and development-jar checksums are pinned in
`gradle.properties`. Packaged tests use the exact public Base Metals
3.1.0.117011 and OreSpawn 4.1.0.117011 release jars.

The port keeps registry and saved-state identities. Native block-entity factories
now receive their real position and block state, and native tickers dispatch
the existing machine update logic. Configured material aliases are additive
server data-pack tags. Legacy migration also covers separate entity chunks.

Qualification is in progress; see the [current test record](CURRENT-QUALIFICATION.md).
The handover will list the tested artifact hashes,
completed checks, failures and remaining manual checks. Compilation, saved-state
presence, animation or a green result from an older port is not evidence that a
machine has done useful work in 1.17.

Use disposable copies for every upgrade test. Never open the only copy of a
valuable world with a port candidate.

The [manual checklist](MANUAL-TESTING.md) gives the Eclipse project paths and
the remaining visual, interaction and copied-world checks.
