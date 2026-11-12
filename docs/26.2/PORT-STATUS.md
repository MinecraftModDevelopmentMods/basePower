# Minecraft 26.2 Port

This is a local port of the accepted Minecraft 26.1.2 source. The automated
checks have passed; manual testing and publication are separate steps.
See the [26.2 qualification record](QUALIFICATION.md) for the exact jars,
completed checks and remaining manual scope. Earlier records apply only to
their named Minecraft version and exact jars.

The starting target is Forge 65.1.0, official 26.2 names, MCP
20260616.103818 and Temurin 25.0.3+9. Minecraft's own version metadata declares
data version 4903, client resource format 88.0 and server data format 107.1.

Base Metals' shared migration extension is ported locally as 3.1.2.2602001.
Its 135 unit tests and release checks pass. OreSpawn's retained-provider-settings
repair is qualified locally as 4.1.1.2602001, with 261 unit tests, surface
integration and release/Eclipse checks. Neither dependency patch is published.

Power passes all 357 unit tests, fresh Eclipse compilation and its release
audits. Independent empty-cache builds reproduce the player, sources and
Javadoc archives. Steam and Electric pass their own equivalent build gates.
The complete family passes operating migrations, restart, network-unloading,
moving-drill, generation, progression, graphical and measured endurance checks.
A separate native control confirms the leash-helper saveability contract on
this actual engine without loading Power or the test mod.

The port uses the new native GUI, render-target and vertex APIs without
changing machine balance or registry identities. Power recognises the new
native sulfur-caves biome when validating saved biome snapshots. The graphical
test probe submits native GPU work before waiting on its readback fence;
that test-only correction is not a change to player fluid rendering.

No 26.2 source, dependency fix, tag or release has been pushed or published.
Manual testing should use the prepared combined Eclipse launch and the pinned
local dependencies. Remaining observations include normal play, turbine
orientation/spin, weapon handling and the other mods in a real modpack.
