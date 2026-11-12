# Minecraft 26.2 Port

This is a local port of the accepted Minecraft 26.1.2 source, not a tested or
published release. Earlier qualification records apply only to their named
Minecraft version and exact jars.

The starting target is Forge 65.1.0, official 26.2 names, MCP
20260616.103818 and Temurin 25.0.3+9. Minecraft's own version metadata declares
data version 4903, client resource format 88.0 and server data format 107.1.

Base Metals' shared migration extension is ported locally as 3.1.2.2602001.
Its 135 unit tests and release checks pass. OreSpawn's retained-provider-settings
repair is qualified locally as 4.1.1.2602001, with 261 unit tests, surface
integration and release/Eclipse checks. Neither dependency patch is published.

Power passes all 357 unit tests and its initial release/Eclipse audits. The
dependency repin is being requalified; full family runtime testing is still
pending. A separate native control confirms the leash-helper saveability
contract on this actual engine without loading Power or the test mod.

Remaining work includes production compilation, the full unit/resource/Eclipse
audits, packaged machine and migration checks, full restarts, client rendering,
and measured endurance tests. Manual testing comes after those checks.
