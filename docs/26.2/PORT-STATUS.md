# Minecraft 26.2 Port

This is a local port of the accepted Minecraft 26.1.2 source, not a tested or
published release. Earlier qualification records apply only to their named
Minecraft version and exact jars.

The starting target is Forge 65.1.0, official 26.2 names, MCP
20260616.103818 and Temurin 25.0.3+9. Minecraft's own version metadata declares
data version 4903, client resource format 88.0 and server data format 107.1.

Base Metals needs its shared migration extension ported before the old-world
suite can run. OreSpawn's retained-provider-settings repair is present locally
but is not yet a public 26.2 release. Dependency and family checksums will be
sealed only after the actual candidates have been built and verified.

Remaining work includes production compilation, the full unit/resource/Eclipse
audits, packaged machine and migration checks, full restarts, client rendering,
and measured endurance tests. Manual testing comes after those checks.
