# Forge 61 Test Runs

These profiles are for Minecraft 1.21.1 and Java 21. They are not permission
to open a real world. Keep the server seed, source worlds and passing source
receipts separate from each new output directory.

`power-interop.json` pins Base Metals and OreSpawn. `dev-core.json` pins the
two expansions for the combined Eclipse launch. Override dependency locations
through the build properties rather than copying jars into source control.
The local Base Metals migration patch is required for old-world tests; the
older public build is not an equivalent input.

## Preparing Inputs

Build Power and the harness, then Steam and Electric against the same Power
jar. Use `migration/native12111.py profile --help` to generate an ignored
packaged profile containing the exact player and harness jar hashes. Stage
only those jars in a disposable runtime. The harness must never enter the
player release jar or the manual client mod list.

Install `migration/requirements.txt` in a local Python environment. From
`test-rig`, the native drivers expose their required arguments with `--help`:

| Driver | Evidence |
| --- | --- |
| `native12111_broad.py` | All walkable stations, shared and decoupled runtimes |
| `native12111_operations.py` | Complete finite machine jobs, raw stopped saves and restart |
| `native12111_recipes.py` | Native unlock packets, all recipe modes and survival progression |
| `native12111_upgrade.py` | Hash-checked historical/retained worlds, state and connected operation |
| `native12111_actors.py` | Players, nested items, entities, dimensions and stopped entity storage |
| `native12111_networks.py` | Partial/bridge unloads, extended drills and fluid API interoperability |
| `native12111_machines.py` | Harvest, cross-mod oil transport and moving-machine lifecycle |
| `native12111_endurance.py` | Fresh and migrated 30-minute runs, restart and resumed processing |
| `audit_native12111_endurance.py` | Independent replay of every saved endurance checkpoint |

Run the Python regression checks with:

```text
python -B -m unittest discover -s migration -p "test_*.py"
```

The shared layouts and older semantic oracles deliberately remain unchanged.
The native adapters translate representation and commands, not quantities or
expected results. In particular, native item components must retain their
custom data; a test setup using old item-command syntax is a failed run,
not evidence of lost production items.

## Evidence Rules

Keep failed, held and short smoke runs. Do not overwrite them with a later
pass. A smoke duration below 1,800 seconds is not an endurance pass. The
full run also requires at least 18 measured game ticks per second and a
minute of successful operation after a full server-process restart.

The independent replay uses the same immutable station specification as
the run, currently `src/main/resources/assets/advantageworkstest/works/works-1.13.2.json`.
Its historical filename records the shared layout, not the running platform.
The driver verifies the actual Minecraft/Forge identities in every checkpoint.

Dimension reports retain the strict failure for Minecraft's independently
proven wolf-health change. Only the exact pinned native control may explain
that difference. Unrelated item loss, ownership changes or missing entities
still fail. See the [port record](../../../docs/1.21.1/PORT-STATUS.md).

Client pixel, GUI caption and recipe-book checks are separate from packaged
server operation. See the [client probe instructions](../../client-resources/README.md).
Real player login, live animations, tooltips and human visual acceptance
remain manual checks.
