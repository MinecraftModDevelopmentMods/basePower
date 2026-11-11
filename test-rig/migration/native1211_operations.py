"""Measure complete finite machine jobs, then compare their stopped saves and cold reload."""

import argparse
import gzip
import io
from pathlib import Path
import traceback
import zlib

import nbtlib

from matrix import digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_actor_io import bind
from native117_machine_operations import validate_operation, validate_restart
import native117_machine_saved as saved
import native1211 as native


def modern_chunk(payload):
    if len(payload) < 6 or int.from_bytes(payload[:4], "big") != len(payload) - 4:
        raise ValueError("Invalid native terrain payload length")
    decoders = {1: gzip.decompress, 2: zlib.decompress, 3: lambda value: value}
    root = nbtlib.File.parse(io.BytesIO(decoders[payload[4]](payload[5:])))
    if int(root.get("DataVersion", -1)) != 3955 or "Level" in root:
        raise ValueError("Machine save must be native 1.21.1 terrain")
    sections = []
    for section in root["sections"]:
        view = nbtlib.Compound({"Y": section["Y"]})
        if "block_states" in section:
            states = section["block_states"]
            view["Palette"] = states["palette"]
            if "data" in states:
                view["BlockStates"] = states["data"]
        sections.append(view)
    # Supply only field-name accessors to the unchanged, typed raw-save oracle.
    return nbtlib.Compound({"xPos": root["xPos"], "zPos": root["zPos"],
                           "Sections": nbtlib.List[nbtlib.Compound](sections),
                           "TileEntities": root["block_entities"]})


audit_saved = bind(saved.audit, chunk=modern_chunk)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "java", "output"):
        parser.add_argument("--" + name, required=True, type=lambda value: Path(value).resolve())
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--group", choices=("all", "pickup", "power", "steam", "electric"), default="all")
    args = parser.parse_args()
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, version = native.java21(args.java)
    runtime = output_path(args.output)
    for path in (args.profile, args.seed, args.inventory, java, *(entry[1] for entry in target[2].values())):
        require_disjoint(runtime, path)
    seed_before = fingerprint(args.seed)
    expected = native.stage(runtime, *target, args.port)
    for marker in ("advantage-machine-operation.allow", "migration-operate.allow"):
        (runtime / marker).touch()
    report = dict(status="RUNNING", minecraft=native.MINECRAFT, forge=native.FORGE, group=args.group,
                  profileSha256=digest(args.profile), inventorySha256=digest(args.inventory), jars=expected,
                  javaSha256=digest(java), javaVersion=version, phases={})
    receipt = runtime / "native1211-machine-operation-results.json"
    write_json(receipt, report)
    server = None
    try:
        server = native.Server(runtime, java, native.LAUNCHER, "operate")
        for command in ("gamerule doMobSpawning false", "gamerule randomTickSpeed 0",
                        "gamerule doWeatherCycle false", "weather clear"):
            server.send(command)
        server.send("advmachines run " + args.group + " measured")
        server.wait("ADVMACHINES COMPLETE measured", 1500)
        catalog = native.read_json(runtime / "advantage-works-results/machine-catalog-measured.json")
        document = native.read_json(runtime / "advantage-works-results/machine-operation-measured.json")
        report["phases"]["operation"] = validate_operation(catalog, document, args.group)
        server.send("save-all flush")
        server.wait("Saved the game", 30)
        server.close()
        server = None
        fixture = native.read_json(runtime / "machine-operation-fixture.json")
        fixture_sha = report["fixtureSha256"] = digest(runtime / "machine-operation-fixture.json")
        report["phases"]["saved-before-restart"] = audit_saved(runtime, fixture, runtime / "raw-before-restart")
        write_json(receipt, report)
        server = native.Server(runtime, java, native.LAUNCHER, "restart")
        server.send("advmachines inspect restarted")
        server.wait("ADVMACHINES INSPECTED restarted", 360)
        observed = native.read_json(runtime / "advantage-works-results/machine-inspection-restarted.json")
        report["phases"]["restart"] = validate_restart(fixture, observed)
        server.send("save-all flush")
        server.wait("Saved the game", 30)
        server.close()
        server = None
        report["phases"]["saved-after-restart"] = audit_saved(runtime, observed, runtime / "raw-after-restart")
        if digest(runtime / "machine-operation-fixture.json") != fixture_sha:
            raise AssertionError("Operating fixture changed across restart")
        report["status"] = "PASS" if all(value["status"] == "PASS" for value in report["phases"].values()) else "FAIL"
    except BaseException as failure:
        report.update(status="FAIL", failure=repr(failure), traceback=traceback.format_exc())
        raise
    finally:
        try:
            if server is not None:
                server.close()
            native.verify_runtime(runtime, target[1], expected)
            report["seedUnchanged"] = fingerprint(args.seed) == seed_before
            if not report["seedUnchanged"]:
                raise AssertionError("Protected server seed changed")
        except BaseException as failure:
            report.update(status="FAIL", custodyError=repr(failure))
            raise
        finally:
            write_json(receipt, report)
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
