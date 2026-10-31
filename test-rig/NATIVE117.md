# Advantage Works For Minecraft 1.17.1

Use this guide for the PA117 checkout. The retained `README.md`, `NATIVE116.md`
and older adapters describe historical versions; they are not current runtime
instructions. See the [test record](../docs/1.17/CURRENT-QUALIFICATION.md) for
completed checks, exact artifacts, retained failures and manual gaps.

The native target is Forge `37.1.1`, official `1.17.1` mappings, Java 16 and
harness `1.1.0.117011`. Gradle runs on Java 17. Some inherited command-line flags
are still named `--java8`; in every `native117` runner they take the **Java 16
executable**, not a Java 8 installation.

## Safety

These are destructive construction tests for disposable worlds only. Never add
their authorization markers to a valuable world. Keep the harness, probe jars,
worlds, logs and generated reports out of gameplay distributions.

Every run needs a new output directory under `test-rig/build/`. Existing outputs
are not overwritten. Retained source worlds are fingerprinted, copied and checked
again after use. They are never launched in place. Allocate one unused localhost
port per running campaign, including gaps between its server boots.

Do not edit a running campaign's source, fixture, manifest or artifacts. A passing
machine result cannot rescue a failed source-custody check. Keep failures and use
a new output name after a justified correction.

## Build And Offline Checks

Build the three production mods first using their pinned dependencies and normal
release audits. From Power's root, build the separate harness with:

```text
gradlew.bat -p test-rig clean check assemble
```

The harness compiles against the pinned Power deobf jar. Build the production
mods with their declared API pins first; runtime profiles stage their dependency
jars separately. The harness release goes to `test-rig/build/gradle/libs/`, never
Power's release jar.

Use Python 3.11 or later with `test-rig/migration/requirements.txt`. For example:

```powershell
python -m venv test-rig/migration/.venv
& ./test-rig/migration/.venv/Scripts/python.exe -m pip install -r test-rig/migration/requirements.txt
& ./test-rig/migration/.venv/Scripts/python.exe -m unittest discover -s test-rig/migration -p 'test_native117*.py'
```

Run the separate shared controls from `test-rig/migration` too:

```text
python -m unittest test_broad_campaign_status test_audit_native117_partial
```

The historical all-version Python discovery command is not a native 1.17 gate:
several older tests deliberately require their original engine or source hashes.
Do not relax those checks or relabel their failures to qualify this port.

The production Eclipse compiler check is documented
[separately](../tools/verification/native117/README.md). The graphical checks are
in [client-resources/README.md](client-resources/README.md).

## Pin Inputs Before Running

Destination profiles are in `profiles/native117/`. They describe inputs, not
automatic acceptance. Populate every declared artifact pin, then use:

```text
python test-rig/migration/native117.py pin-target --profile <profile.json> --server-seed <installed-native-server> --output test-rig/build/<new-inputs.json>
```

The installed server must have the exact verified Forge code inventory. The
native launcher uses `@win_args.txt` on Windows, not `-jar win_args.txt`. The Java
Works runner uses its separately pinned files-only seed receipt; it must not
rewrite the canonical Python inventory.

Keep the resulting manifest immutable. A harness correction needs a new manifest
and a separate report; it does not replace earlier evidence. Use each runner's
`--help` for its required source receipts, output, port and Java parameters.

## Runtime Coverage

| Runner | Purpose |
| --- | --- |
| `native117_machine_operations.py` | Finite measured jobs, exact cold restart and raw region-file corroboration |
| `native117_steam_control_audit.py` | Read-only replay of comparator and first-loaded-tick Steam controls |
| `native117_campaign.py` | Hash-protected frozen and connected migration route matrices |
| `native117_operating_import.py` | Preserve the accepted 132-machine 1.16 fixture without reconstructing it |
| `native117_actors.py` and `native117_audit_actors.py` | Players, nested items, entities, dimensions, ownership, real ticking and saved entity regions |
| `native117_player_layout.py` | Exact player bag and Ender storage layouts |
| `native117_respawn.py` and its auditor | Death, drops, retained inventories and respawn through native server handlers |
| `native117_transformer_order.py` and its auditor | Actual Base Metals/Power transformer order, emitted hooks and migration semantics |
| `native117_lifecycle.py` | Moving drills, actual chunk callbacks, mining and restarts |
| `native117_holder_networks.py` | Independent machine/supply or bridge unloading beyond the native ticket halo |
| `native117_endurance.py` and its auditor | Repeated bounded load for 30 minutes, shutdown, restart and resumed processing |
| `native117_recipes.py` | Ingredient triggers, recipe modes and manufacture from available materials |
| `audit_native117_hammer.py` | Independently replay exact query-visible hammer inputs, native recipe selection, one callback, consumption, durability and output quantities |
| `native117_harvest.py` | Real survival-tool, mining-tier and dropped-item checks |
| `native117_interop.py` | Fluid and energy capability acceptance and conservation |
| `native117_oil.py` | Bucket placement, drains, pipes, tanks, Mineralogy oil and full restarts |
| `native117_broad.py` and its auditor | All walkable bays before and after restart, with manual procedures still open |
| `native117_redstone_reload.py` | Naturally earned mid-block drill state and the first native ticks after restart |

Hammer telemetry records the exact source object in the native query before one
callback. It does not require that a visible item has already ticked. Observer
errors and invisible or contaminated inputs fail before the action; they are
not missing-recipe passes. A later successful action cannot repair a failed
receipt. Retain historical failures whose action-time conditions were not
recorded rather than guessing their cause.

Use holder-aware layouts for partial-network isolation. Adjacent forced chunks
can keep a short bridge loaded; that layout cannot prove unloading regardless of
how long the runner sleeps. Assertions require real unload/load callbacks and
unchanged endpoint identities, not an assumed absence.

## Interpret Results Literally

`PASS` applies only to the stated scope. A frozen import is not machine operation;
progress is not a completed drop; a built bay is not its whole manual checklist.
`INCOMPLETE`, `SKIP` and `MANUAL_REQUIRED` are not passing gameplay tests.

Native platform differences must remain separate from strict comparisons and
need exact independent evidence. For example, a native non-persistent leash
helper's reconstruction is not UUID persistence in an entity-region file.
Unknown differences, missing quantities and missing evidence remain failures.
