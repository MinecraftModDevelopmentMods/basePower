"""Native64 transport for unchanged physical-unload, bridge and extended-drill oracles."""

import argparse
from dataclasses import dataclass
from pathlib import Path
import sys
import subprocess
import traceback

import nbtlib

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_actor_io import bind
import native117_extended
from native117_geometry import FORCED_LEVEL, FULL_LEVEL
from native117_holder_networks import bridge_fixtures, extended_fixtures, linear_fixtures
from native117_lifecycle import authorize_lifecycle
from native117_partial import exercise_bridge, exercise_partial
from native117_ticket_networks import NativeTicketExtendedRun
import native262 as native


# Native ChunkLevel derives 33 + GENERATION_PYRAMID's radius. Every live probe
# must confirm radius 11, forced level 31 and the resulting 13-chunk holder halo.
MAX_TICKET_DISTANCE = 11
HOLDER_RADIUS = FULL_LEVEL + MAX_TICKET_DISTANCE - FORCED_LEVEL
CAMPAIGNS = {
    "partial": (linear_fixtures, exercise_partial),
    "bridge": (bridge_fixtures, exercise_bridge),
    "extended": (extended_fixtures, native117_extended.exercise),
}


def capped_command(command):
    command = list(command)
    if (not command or "-Xmx2G" not in command or not any(part.startswith("@") for part in command)
            or any(part.startswith("-XX:ActiveProcessorCount=") for part in command)):
        raise ValueError("Review a changed native finite-server launch before adding its CPU cap")
    return [command[0], "-XX:ActiveProcessorCount=4", *command[1:]]


class _NativeProcess:
    def __init__(self, owner):
        self.owner = owner

    def __getattr__(self, name):
        return getattr(subprocess, name)

    def Popen(self, command, *args, **kwargs):
        command = capped_command(command)
        environment = kwargs.get("env")
        if not isinstance(environment, dict):
            raise ValueError("Finite native launch needs the core's explicit environment")
        names = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")
        if any(name in environment for name in names):
            raise ValueError("Finite native launch may not inherit JVM options/classpath")
        path = Path(kwargs["cwd"]) / f"native262-finite-launch-{self.owner.native_label}.json"
        if path.exists():
            raise FileExistsError(path)
        record = dict(status="PLANNED", command=command, inheritedOptions={name: None for name in names},
                      explicitProcessorCap=4, explicitMaxHeap="2G")
        write_json(path, record)
        process = None
        try:
            process = subprocess.Popen(command, *args, **kwargs)
            record.update(status="REAL_PROCESS_STARTED", pid=process.pid)
            write_json(path, record)
            return process
        except BaseException:
            if process is not None and process.poll() is None:
                process.kill()
                process.wait(timeout=30)
            raise


class _CappedLaunch(native.NativeLaunch):
    def __init__(self, *args, **kwargs):
        bind(native.NativeLaunch.__init__, subprocess=_NativeProcess(self))(self, *args, **kwargs)


class Server(native.Server, _CappedLaunch):
    """Keep the sealed core transport; add a recorded command-line cap without global mutation."""


def native_boot(path):
    native.require_clean_log(path)
    text = Path(path).read_text(encoding="utf-8")
    for marker in (f"Starting minecraft server version {native.MINECRAFT}",
                   f"Forge mod loading, version {native.FORGE}, for MC {native.MINECRAFT} with MCP {native.MCP}"):
        if marker not in text:
            raise AssertionError("Missing native65 finite-campaign boot proof: " + marker)


def native_inventory_item(item):
    identity, separator, payload = item.partition("{")
    if not separator:
        return item
    if "[" in identity:
        raise ValueError("Ambiguous legacy and native fixture item components")
    custom = nbtlib.parse_nbt(separator + payload)
    if not isinstance(custom, nbtlib.Compound):
        raise AssertionError("Historical fixture item data must be a compound")
    return identity + "[minecraft:custom_data=" + custom.snbt(compact=True) + "]"


def compatibility_view(value):
    """Derived legacy accessors only; never rewrite native probe files or input objects."""
    if isinstance(value, list):
        return [compatibility_view(item) for item in value]
    if not isinstance(value, dict):
        return value
    result = {key: compatibility_view(item) for key, item in value.items()}
    if isinstance(value.get("nbt"), str):
        compound = nbtlib.parse_nbt(value["nbt"])
        if not isinstance(compound, nbtlib.Compound):
            raise AssertionError("Native lifecycle witness NBT must be a compound")
        items = compound.get("Items", ())
        if "Items" in compound and not isinstance(items, nbtlib.List):
            raise AssertionError("Native inventory must be a typed list")
        for stack in items:
            if (not isinstance(stack, nbtlib.Compound) or not isinstance(stack.get("id"), nbtlib.String)
                    or not isinstance(stack.get("count"), nbtlib.Int) or int(stack["count"]) <= 0
                    or "Count" in stack or "tag" in stack):
                raise AssertionError(f"Expected native 26.2 inventory stack: {stack}")
            components = stack.get("components", nbtlib.Compound())
            if not isinstance(components, nbtlib.Compound):
                raise AssertionError("Native item components must be a compound")
            stack["Count"] = stack["count"]
            custom = components.get("minecraft:custom_data")
            if custom is not None:
                if not isinstance(custom, nbtlib.Compound):
                    raise AssertionError("Native inventory custom_data must be a compound")
                stack["tag"] = custom
        result["nbt"] = compound.snbt(compact=True)
    return result


def validate_snapshot(snapshot):
    if ((snapshot.get("minecraft"), snapshot.get("forge"), snapshot.get("station"))
            != (native.MINECRAFT, native.FORGE, "lifecycle")
            or type(snapshot.get("worldTime")) is not int or snapshot["worldTime"] < 0):
        raise AssertionError("Historical or incomplete lifecycle evidence is not native65 proof")
    return snapshot


def oracle_closure():
    directory = Path(__file__).resolve().parent
    return {Path(module.__file__).resolve(): digest(Path(module.__file__)) for module in tuple(sys.modules.values())
            if getattr(module, "__file__", None) and Path(module.__file__).suffix == ".py"
            and Path(module.__file__).resolve().parent == directory
            and not Path(module.__file__).name.startswith("test_")}


@dataclass
class Inputs:
    target: tuple
    java: Path
    java_version: str
    output: Path
    hashes: dict
    seed_before: dict

    def verify(self, staged):
        if staged:
            native.verify_runtime(self.output, self.target[1],
                                  {path.name: sha for _, path, sha in self.target[2].values()})
        if fingerprint(self.target[0]) != self.seed_before:
            raise AssertionError("Protected server seed changed")
        for path, expected in self.hashes.items():
            native.validate_hash(path, expected)
        if set(oracle_closure()) - set(self.hashes):
            raise AssertionError("An unsealed migration oracle was imported during the run")

    def receipt(self):
        return dict(qualificationStatus=native.EXPLORATORY, minecraft=native.MINECRAFT,
                    forge=native.FORGE, mcp=native.MCP, javaVersion=self.java_version,
                    inputHashes={str(path): sha for path, sha in self.hashes.items()},
                    jars={key: value[2] for key, value in self.target[2].items()},
                    pythonVersion=sys.version, pythonExecutable=str(Path(sys.executable).resolve()),
                    inheritedJvmOptions={name: None for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")},
                    explicitProcessorCap=4, explicitMaxHeap="2G",
                    legacyOracleView="Derived aliases only; original native probe files are unchanged")


def prepare_inputs(args):
    if any(name in native.os.environ for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")):
        raise ValueError("Finite native campaigns require absent JVM option/classpath variables")
    native.native_launch_environment()
    native.validate_hash(args.profile, args.profile_sha256)
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    if set(target[2]) != set(native.VERSIONS):
        raise ValueError("Require the complete six-jar native65 family")
    java, version = native.java25(args.java)
    native.require_free_port(args.port)
    output = output_path(Path(args.output))
    sources = oracle_closure()
    sources.update({Path(path).resolve(): digest(path) for path in (
        args.profile, args.inventory, java, Path(sys.executable), *(value[1] for value in target[2].values()))})
    for path in [target[0], *sources]:
        require_disjoint(output, Path(path))
    return Inputs(target, java, version, output, sources, fingerprint(target[0]))


def arguments(description=__doc__):
    parser = argparse.ArgumentParser(description=description)
    for name in ("profile", "seed", "inventory", "output", "java"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--profile-sha256", required=True)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--preflight-only", action="store_true")
    return parser


class PartialRun(NativeTicketExtendedRun):
    minecraft = native.MINECRAFT
    max_ticket_distance = MAX_TICKET_DISTANCE
    require_boot = staticmethod(native_boot)

    def __init__(self, *args):
        super().__init__(*args)
        self.pids = []

    def open_server(self, label):
        server = Server(self.runtime, self.java, self.launcher, label)
        self.pids.append(server.process.pid)
        return server

    def inventory(self, pos, slot, item, count):
        coordinates = " ".join(map(str, pos))
        self.command(f"item replace block {coordinates} container.{slot} with {native_inventory_item(item)} {count}")

    def _raw_probe(self, operation, label):
        return validate_snapshot(super()._raw_probe(operation, label))

    def probe(self, operation, label):
        return compatibility_view(super().probe(operation, label))


def run(args):
    inputs = prepare_inputs(args)
    if args.preflight_only:
        return dict(status="READY_NOT_RUN", qualificationAllowed=False, **inputs.receipt())
    runtime = inputs.output
    receipt = runtime / f"native262-{args.campaign}-results.json"
    report = dict(status="RUNNING", campaign=args.campaign, scope="FINITE_PHYSICAL_UNLOAD_NETWORKS_AND_DRILLS",
                  **inputs.receipt())

    def progress(value):
        report["observations"] = value
        write_json(receipt, report)

    runner, staged = None, False
    try:
        native.stage(runtime, *inputs.target, args.port)
        staged = True
        write_json(receipt, report)
        layout, exercise = CAMPAIGNS[args.campaign]
        definition = layout(HOLDER_RADIUS)
        fixture = runtime / "lifecycle-fixture.json"
        write_json(fixture, definition)
        report["fixtureSha256"] = digest(fixture)
        authorize_lifecycle(runtime)
        runner = PartialRun(runtime, inputs.java, native.LAUNCHER, definition)
        result = exercise(runner, progress)
        report.update(status=result["status"], observations=result)
    except BaseException as failure:
        report.update(status="FAIL", failure=repr(failure), traceback=traceback.format_exc())
    finally:
        try:
            if runner is not None:
                runner.close()
                report["processIds"] = runner.pids
                report["processesClosed"] = runner.server is None
            inputs.verify(staged)
            report.update(seedUnchanged=True, inputsUnchanged=True, oraclesUnchanged=True)
        except BaseException as failure:
            report.update(status="FAIL", custodyError=repr(failure), custodyTraceback=traceback.format_exc())
        if runtime.is_dir():
            write_json(receipt, report)
    return report


def main(argv=None):
    parser = arguments()
    parser.add_argument("--campaign", choices=tuple(CAMPAIGNS), required=True)
    report = run(parser.parse_args(argv))
    print(report["status"], flush=True)
    return int(report["status"] not in ("PASS", "READY_NOT_RUN"))


if __name__ == "__main__":
    raise SystemExit(main())
