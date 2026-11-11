"""Run physical chunk unload, bridge, drill and interop oracles on Forge 52."""

import argparse
from pathlib import Path
import socket
import traceback

import nbtlib

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
import native117_extended
from native117_geometry import FORCED_LEVEL, FULL_LEVEL
from native117_holder_networks import bridge_fixtures, extended_fixtures, linear_fixtures
from native117_interop import require_checks, require_probe_order
from native117_lifecycle import authorize_lifecycle
from native117_partial import exercise_bridge, exercise_partial
from native117_readiness import await_ready
from native117_ticket_networks import NativeTicketExtendedRun
import native1211 as native


# Forge 52's ChunkPyramid has radius 11 (ChunkLevel.MAX_LEVEL = 44).
# Keep the finite fixtures outside that actual loaded-holder apron.
MAX_TICKET_DISTANCE = 11
HOLDER_RADIUS = FULL_LEVEL + MAX_TICKET_DISTANCE - FORCED_LEVEL


def native_inventory_item(item):
    identity, separator, payload = item.partition("{")
    if not separator:
        return item
    custom = nbtlib.parse_nbt(separator + payload)
    if not isinstance(custom, nbtlib.Compound):
        raise AssertionError("Historical drill fixture item data must be a compound")
    return identity + "[minecraft:custom_data=" + custom.snbt(compact=True) + "]"


def compatibility_view(value):
    """Supply the old oracle's accessors while retaining the full native item components."""
    if isinstance(value, list):
        return [compatibility_view(item) for item in value]
    if not isinstance(value, dict):
        return value
    result = {key: compatibility_view(item) for key, item in value.items()}
    if isinstance(value.get("nbt"), str):
        compound = nbtlib.parse_nbt(value["nbt"])
        if not isinstance(compound, nbtlib.Compound):
            raise AssertionError("Native lifecycle witness NBT must be a compound")
        for stack in compound.get("Items", ()):
            if (not isinstance(stack, nbtlib.Compound) or not isinstance(stack.get("id"), nbtlib.String)
                    or not isinstance(stack.get("count"), nbtlib.Int) or int(stack["count"]) <= 0
                    or "Count" in stack or "tag" in stack):
                raise AssertionError(f"Expected native 1.21.1 inventory stack: {stack}")
            stack["Count"] = stack["count"]
            custom = stack.get("components", {}).get("minecraft:custom_data")
            if custom is not None:
                if not isinstance(custom, nbtlib.Compound):
                    raise AssertionError("Native inventory custom_data must be a compound")
                stack["tag"] = custom
        result["nbt"] = compound.snbt(compact=True)
    return result


class PartialRun(NativeTicketExtendedRun):
    minecraft = native.MINECRAFT
    max_ticket_distance = MAX_TICKET_DISTANCE
    require_boot = staticmethod(native.require_clean_log)

    def open_server(self, label):
        return Server(self.runtime, self.java, self.launcher, label)

    def inventory(self, pos, slot, item, count):
        coordinates = " ".join(map(str, pos))
        self.command(f"item replace block {coordinates} container.{slot} with {native_inventory_item(item)} {count}")

    def probe(self, operation, label):
        return compatibility_view(super().probe(operation, label))


class Server(native.Server):
    def __init__(self, runtime, *args):
        native.audit_staged(runtime)
        super().__init__(runtime, *args)

    def close(self):
        try:
            super().close()
        finally:
            native.audit_staged(self.native_runtime)


def interop(runtime, java, progress):
    observations = {}
    for phase in ("exercise", "restored"):
        server = Server(runtime, java, native.LAUNCHER, phase)
        try:
            server.send("forceload add 1023 1023 1100 1030")
            ready = await_ready(server, runtime, (63, 63, 68, 64), "interop-" + phase,
                                minecraft=native.MINECRAFT)
            server.send("advinterop " + phase)
            server.wait("ADVINTEROP COMPLETE " + phase, 120)
            report = native.read_json(runtime / "advantage-works-results" / f"native-interop-{phase}.json")
            observations[phase] = dict(observation=report, readiness=ready)
            progress(observations)
            require_probe_order(report, ready)
            require_checks(report, phase)
            if "basemetals" in {item["id"] for item in native.read_json(runtime / "network-inputs.json")}:
                native.require_shared_crushing(report)
            server.send("save-all flush")
            server.wait("Saved the game", 30)
        finally:
            server.close()
    return dict(observations=observations, status="PASS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "output", "java"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--campaign", choices=("partial", "bridge", "extended", "interop"), required=True)
    args = parser.parse_args()
    if not 26450 <= args.port <= 65535:
        parser.error("Use a disposable localhost port at or above 26450")

    runtime = output_path(args.output)
    seed, inventory, mods = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, version = native.java21(args.java)
    for path in (args.profile, seed, args.inventory, java, *(item[1] for item in mods.values())):
        require_disjoint(runtime, path)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", args.port))
    protected = fingerprint(seed)
    expected = {path.name: sha.upper() for _, path, sha in mods.values()}
    helpers = [Path(__file__), *(Path(__file__).with_name(name) for name in (
        "native117_partial.py", "native117_ticket_networks.py", "native117_holder_networks.py",
        "native117_extended.py", "extended_lifecycle.py", "lifecycle.py", "audit_pipe_only.py",
    ))]
    source_hashes = {path.name: digest(path) for path in helpers}
    report = dict(status="RUNNING", campaign=args.campaign, qualificationStatus=native.EXPLORATORY,
                  minecraft=native.MINECRAFT, forge=native.FORGE, port=args.port,
                  profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  javaSha256=digest(java), javaVersion=version, jars=expected, oracleSources=source_hashes)
    receipt = runtime / f"native1211-{args.campaign}-results.json"

    def progress(value):
        report["observations"] = value
        write_json(receipt, report)

    runner = None
    staged = False
    try:
        native.stage(runtime, seed, inventory, mods, args.port)
        staged = True
        write_json(receipt, report)
        write_json(runtime / "network-inputs.json", [
            dict(id=key, version=mod_version, file=path.name, sha256=sha.upper())
            for key, (mod_version, path, sha) in mods.items()
        ])
        if args.campaign == "interop":
            result = interop(runtime, java, progress)
        else:
            layout, exercise = {
                "partial": (linear_fixtures, exercise_partial),
                "bridge": (bridge_fixtures, exercise_bridge),
                "extended": (extended_fixtures, native117_extended.exercise),
            }[args.campaign]
            definition = layout(HOLDER_RADIUS)
            fixture = runtime / "lifecycle-fixture.json"
            write_json(fixture, definition)
            report["fixtureSha256"] = digest(fixture)
            authorize_lifecycle(runtime)
            runner = PartialRun(runtime, java, native.LAUNCHER, definition)
            result = exercise(runner, progress)
        report.update(status=result["status"], observations=result)
    except BaseException as failure:
        report.update(status="FAIL", failure=repr(failure), traceback=traceback.format_exc())
        raise
    finally:
        try:
            if runner is not None:
                runner.close()
            if staged:
                native.verify_runtime(runtime, inventory, expected)
            report["seedUnchanged"] = fingerprint(seed) == protected
            report["oraclesUnchanged"] = all(digest(path) == source_hashes[path.name] for path in helpers)
            report["inputsUnchanged"] = all(digest(path) == sha for path, sha in (
                (args.profile, report["profileSha256"]), (args.inventory, report["inventorySha256"]),
                (java, report["javaSha256"]), *((path, sha.upper()) for _, path, sha in mods.values()),
            ))
            if not all(report[key] for key in ("seedUnchanged", "oraclesUnchanged", "inputsUnchanged")):
                raise AssertionError("Protected input changed during the native network test")
        except Exception as audit:
            report.update(status="FAIL", auditError=repr(audit))
        finally:
            if runtime.is_dir():
                write_json(receipt, report)
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
