# Forge 61 Test Runs

These profiles are for Minecraft 1.21.11 and Java 21. They are not permission
to open a real world. Keep the server seed, source worlds and passing source
receipts separate from each new output directory.

`power-interop.json` pins Base Metals and OreSpawn. `dev-core.json` pins the
two expansions for the combined Eclipse launch. Override dependency locations
through the build properties rather than copying jars into source control.
All current 1.21.11 tests and new worlds require BaseMetals 3.1.2.121111,
pinned by exact SHA-256 in the profile. This is also the metadata minimum.
The qualified local build includes the migration patch; an older public
build is not an equivalent input.

## Supported Broad Profile

BaseMetals is mandatory for basePower, baseSteam and baseElectric on Minecraft
1.21.11. The native61 broad driver defaults to `packaged-current`, with all six
family/harness inputs pinned. It also accepts explicit `--mode packaged-current`.
Requests for `--mode both` or `--mode packaged-decoupled` are rejected with a
dependency-policy error before input staging, output creation or server startup.
They are not silent skips or passes. This policy is specific to 1.21.11;
older-version drivers and their qualification profiles are unchanged.
The shared `native12111.read_family_target` reader also requires the BaseMetals
pin for every supported full-family campaign. A missing-dependency control must
first validate the complete six-jar profile, then omit BaseMetals only from a
derived NEW expected-negative runtime; it is never a valid normal family profile.

Retain the original failed A/B/C2 decoupled reports and their exact hashes.
The clarified support boundary does not relabel historical failures. Future
required broad qualification covers the supported BaseMetals-present profile.

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
| `native12111_broad.py` | All walkable stations and drill restart witnesses in supported packaged-current |
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
still fail. See the [port record](../../../docs/1.21.11/PORT-STATUS.md).

A separate missing-dependency control can run only after the corrected Power,
Steam and Electric metadata jars are frozen and pinned. Use a NEW disposable
Forge 61 runtime with those new jars and all other required dependencies, but
omit BaseMetals. Bound startup to 120 seconds and retain a `MISSING_DEPENDENCY`
receipt, exact jar hashes and logs. Require Forge's missing-BaseMetals refusal
before OreSpawn IMC, world creation or `Done`; reaching any of those is a failed
negative control. Do not launch the old artifacts or treat their OreSpawn crash
as dependency-refusal proof. This is a proposed loader control, not broad gameplay
qualification or a launch performed by the policy tests.

Client pixel, GUI caption and recipe-book checks are separate from packaged
server operation. See the [client probe instructions](../../client-resources/README.md).
Real player login, live animations, tooltips and human visual acceptance
remain manual checks.
