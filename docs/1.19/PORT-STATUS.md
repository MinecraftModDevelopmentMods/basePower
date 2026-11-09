# Minecraft 1.19.4 Port

This is a local candidate, not a release. Back up a world before upgrading and
use a disposable copy for tests. Earlier versions' results do not qualify this
build.

The port targets Forge 45.4.0, official 1.19.4 mappings and Java 17. It uses
Forge's creative tab and fluid types and keeps existing registry names, tank
formats and recipe modes. The Base Metals pin includes the local shared
migration hook. Do not substitute the public Base Metals jar for that pin.

The release audit checks package contents, mappings, dependency hashes, Java
bytecode and Eclipse launch classpaths. The separate graphical probe loads
real models and textures and renders the machine screens. A server startup
cannot prove those visuals.

The Works harness uses Minecraft's flat preset and retains the 21 station
definitions. The migration runners check live and serialized machine state,
verify that the source is unchanged and refuse changed input hashes.

See [the 1.19 Works rig](../../test-rig/NATIVE119.md) and
[the client probe](../../test-rig/client-resources/README.md) for commands.
Keep local logs and provisional jars out of release artifacts and commits.
