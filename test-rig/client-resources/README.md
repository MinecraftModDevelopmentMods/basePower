# Native Client Resource Check

This disposable Minecraft 1.21.11 / Forge 61.1.0 client checks models, textures,
translations, recipe-book cells and machine screens after Minecraft loads them.
It registers no gameplay content, opens no world and closes when finished.
The probe is excluded from release jars and ordinary Eclipse launches.
Compilation and worldless unit checks are separate from actual graphical qualification.
Earlier-version results do not qualify this version.

Use Java 21 for Gradle, compilation and the client and server runners. The
target is official 1.21.11 mappings with MCP `20251223.124241`.

## Build And Run

```powershell
./gradlew.bat check clientResourceProbeJar verifyEclipseProductionClasspath prepareCombinedEclipseLaunch
./test-rig/client-resources/test-launcher.ps1
./test-rig/client-resources/run.ps1 -JavaHome '<Java 21 home>' -LaunchProfile runClientAllAdvantage.launch -RequireExtensions -MachineGuiLayouts -ChildRendererGeometry -RecipeBookFixtures '<native 1.21.11 recipe run>/unlock' -PreflightOnly
./test-rig/client-resources/run.ps1 -JavaHome '<Java 21 home>' -LaunchProfile runClientAllAdvantage.launch -RequireExtensions -MachineGuiLayouts -ChildRendererGeometry -RecipeBookFixtures '<native 1.21.11 recipe run>/unlock'
```

The combined launch requires Power `2.4.15.121111`, Steam `2.2.16.121111` and
Electric `2.2.16.121111`. Pin the exact rebuilt jars in `profiles/native12111`
before regenerating it. Omitting the combined profile retains Power-only
scope. Normal Eclipse launches contain production code only.

Each run has a new directory under `run-client-resource-runs/`. It retains
the launch, production-file manifest, exact dependency and candidate hashes,
logs, framebuffer images and `client-resources.json`. Missing output, failed
assertions and Advantage model/texture errors fail the run. A runner failure
is retained separately without replacing partial native results. Existing
saves and earlier reports are untouched. `-PreflightOnly` starts no client and
does not claim a graphical pass.

The default `-OutputSource Gradle` snapshots compiled classes and processed
resources. `-OutputSource Eclipse` copies `bin/main` without overlaying Gradle
resources. `-PowerCandidate` selects an explicit native player jar; its hash
is provenance, not proof that the selected loose output equals that jar.

## Recipe Fixtures

Generate fresh exports from the packaged 1.21.11 candidates, not a historical run:

```powershell
<python.exe> -B test-rig/migration/native12111_recipes.py --profile '<pinned packaged-current profile>' --seed '<Forge 61.1.0 server>' --inventory '<sealed inventory>' --inventory-sha256 '<inventory SHA-256>' --java '<Java 21 home>/bin/java.exe' --port 25577 --output test-rig/build/native12111-recipes-new
```

Use an existing Python 3.11+ environment with `migration/requirements.txt`.
This runs five unlock/persistence boots and 18 progression checks across all
recipe modes, with rubber enabled and disabled. `--campaign unlock` or
`--campaign progression` selects one part. Mode changes legitimately prune
removed recipe IDs from saved books: the audit retains those log events and
requires their exact set to match the preceding and current recipe packets.
Unrelated errors still fail.

Pass the resulting `unlock` directory to the client. It must contain the three
`recipes-*-sweep.bin` packets, matching successful JSON reports with
`bookPacketType: ClientboundRecipeBookAddPacket`, per-recipe `displayIds`,
`bookRecipes` and the generated world's `level.dat`. The client reads its
registry snapshot before decoding the packets; it does not open the world.
Historical or mismatched headers fail. Native recipe-book collections must
group only identical outputs, retain every variant and render thirteen separate
example cells with more than 1,000 colored framebuffer pixels. Without fixtures
this is explicitly `NOT_RUN_NO_NATIVE_FIXTURES`. The removed full-holder update
packet is rejected, not emulated. Focused native packet/collection tests are in
`test/`; they are not graphical evidence or part of the production jar.

## What Is Checked

- Every registered Advantage item and valid block state: native item definitions, geometry, missing
  models/sprites, English names, creative-tab contents and GUI translation keys.
  Native fluid-block models are intentionally particle-only.
- Portable and metal tank gauges: compare the whole gauge to an independent
  native sprite render at empty, quarter, half and full. Water, lava and both
  oils must show the right texture, tint and fill boundary. The probe also
  checks real update packets, fluid capability simulation/execution and
  neighbour-face culling for both tanks.
- Native bucket fluids and fluid-renderer vertices for crude/refined oil,
  with water/lava controls, actual atlas sprites and tint.
- With `-MachineGuiLayouts`, real screen rendering at nine window/GUI-scale
  combinations, caption pixels, slot/frame placement and deliberately broken
  controls. Screenshots are retained before assertions.
- With `-ChildRendererGeometry`, registered drill-bit, beam and turret geometry
  buffers. Hydroturbine registration and texture are checked; its live
  orientation and spin still require a disposable world.

GUI captures use native `GuiRenderState` extraction, `GuiRenderer` GPU submission,
and fenced `Screenshot` readback from an actual temporary render target. There
is no legacy immediate-mode GL projection or synthetic pixel substitute.

The live probes retain the pixel, packet and missing-model checks. Run the
current launcher's parser and negative controls without starting Minecraft:

```powershell
./test-rig/client-resources/test-launcher.ps1 -StaticOnly
```

The native contract entry points retain the immutable historical source hashes
and caption fixture, compare 33 complete unchanged oracle methods against the
inherited 1.21.1 source with the JDK parser, and fingerprint the explicit native
API boundaries. The geometry contract can also bind GUI projection and GPU
readback to the actual Forge 61 mapped engine sources. Its mutation companion
rejects 31 weakened source mutants. None of these static results replaces a
graphical client run.

After Gradle regenerates human Eclipse launches, run the preparation helper
with a fresh evidence directory, then run the full launcher test:

    ./test-rig/client-resources/prepare-eclipse-client.ps1 -Evidence <fresh local evidence>
    ./test-rig/client-resources/test-launcher.ps1

The helper validates all native asset objects and resolves only generated
asset arguments. It changes no source, root build logic or jar, launches no
client and opens no world. Reapply it after subsequent launch regeneration.

## Frozen Candidate Runs

Supply the frozen output mode, packaged profile and approved profile SHA-256,
compiled probe jar, complete native asset snapshot with index 29, and isolated
bootstrap cache. The runner snapshots and verifies the five production jars
without loading loose production output. The profile's server harness is not
loaded into this worldless client.

The explicit loose-Power diagnostic switch instead snapshots Power's compiled
classes and resources while retaining frozen dependency jars. Its receipts
cannot qualify a frozen Power jar. Only diagnostic mode may stage old recipe
fixtures without tag packets so the recipe scope can fail independently while
other scopes finish; normal frozen runs reject incomplete exports.

Fresh server exports must supply tagsPacketType, tagsPacketFile,
tagsPacketSha256 and itemTagCount. The client decodes the actual native tag
packet, verifies exact item-tag member IDs, and uses the native registry
tag-reload path before decoding the book display packet.

These checks do not certify machine operation, live animated entities,
multiplayer menu handshakes, manual recipe discovery or third-party resource
packs. Those remain separate from compilation and graphical qualification.
