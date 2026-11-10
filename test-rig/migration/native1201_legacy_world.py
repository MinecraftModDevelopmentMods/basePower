"""Compare original and released legacy fixtures against live and saved 1.20.1 state."""

import argparse
from pathlib import Path
import shutil
import sys
import time

from matrix import WORLD, compare, digest, fingerprint, parse_nbt, write_json
from native117 import native_normalized, output_path, require_disjoint
from native117_broad import read_json
from native117_commands import native
from native117_legacy_world import turbine
import native1201
import native1201_saved


def portable(hashes):
    return {key.replace("\\", "/"): value.upper() for key, value in hashes.items()}


def operate(runtime, frozen, target, java, port, positions, expected):
    launcher = native1201.stage(runtime, target, port)
    for path in (WORLD, "config"):
        shutil.copytree(frozen / path, runtime / path)
    shutil.copy2(frozen / "migration-fixture.json", runtime / "migration-fixture.json")
    (runtime / "migration-operate.allow").touch()
    jobs = (("sourceChest", "destination", "dirt", "dirt", 8),
            ("steamCrusher", "steamCrusher", "stone", "cobblestone", 1),
            ("electricFurnace", "electricFurnace", "iron_ore", "iron_ingot", 1))
    server = native1201.Server(runtime, java, launcher, "operation")
    try:
        for source, _, item, _, count in jobs:
            x, y, z = positions[source]
            server.send(native(f"replaceitem block {x} {y} {z} container.0 minecraft:{item} {count}"))
            server.wait("Replaced a slot", 30)
        time.sleep(30)
        server.snapshot("operation")
        server.send("save-all flush")
        server.wait("Saved the game", 30)
    finally:
        server.close()
    rows = {row["name"]: row for row in read_json(runtime / "migration-operation.json")}
    outputs = {}
    for source, sink, item, product, count in jobs:
        old = sum(entry["count"] for entry in expected["witnesses"][sink].get("items", ())
                  if entry["item"] == "minecraft:" + product)
        live = sum(int(entry["Count"]) for entry in parse_nbt(rows[sink]).get("Items", ())
                   if entry["id"] == "minecraft:" + product)
        remaining = sum(int(entry["Count"]) for entry in parse_nbt(rows[source]).get("Items", ())
                        if entry["id"] == "minecraft:" + item)
        if live != old + count or remaining:
            raise AssertionError(f"{sink}: expected {old + count} {product} and no input, got {live}/{remaining}")
        outputs[sink] = dict(before=old, after=live, item="minecraft:" + product)
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("prior-run", "fixture", "legacy-helper", "profile", "seed", "inventory", "java17", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--fixture-phase", choices=("source", "restarted"), default="source")
    args = parser.parse_args()
    sys.path.append(str(args.legacy_helper.resolve()))
    from native116_rebrand import verify_snapshot

    previous = read_json(args.prior_run)
    fixture = read_json(args.fixture / "rebrand-upgrade-results.json")
    if previous["status"] != "PASS" or not previous["sourceUnchanged"] or fixture["status"] != "PASS":
        raise AssertionError("Legacy source has no passing, unchanged historical receipt")
    source = Path(previous["source"]).resolve()
    if source != (args.fixture / ("source" if args.fixture_phase == "source" else "target")).resolve():
        raise AssertionError("Legacy receipt and fixture path disagree")
    expected = fixture["phases"][args.fixture_phase]
    positions = fixture["positions"]
    before = portable(fingerprint(source))
    if before != portable(previous["sourceFingerprint"]):
        raise AssertionError("Legacy source changed since the previous run")
    output = output_path(args.output)
    target = native1201.read_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    for protected in (source, args.profile, args.fixture, args.seed, args.inventory):
        require_disjoint(output, protected)
    launcher = native1201.stage(output, target, args.port)
    shutil.copytree(source / WORLD, output / WORLD)
    shutil.copytree(source / "config", output / "config")
    if fingerprint(source / WORLD) != fingerprint(output / WORLD):
        raise AssertionError("The disposable copy differs from its source")
    write_json(output / "migration-fixture.json", [
        dict(name=name, x=pos[0], y=pos[1], z=pos[2]) for name, pos in positions.items()])
    result = dict(status="RUNNING", source=str(source), sourceFingerprint=before,
                  priorReceiptSha256=digest(args.prior_run), profileSha256=digest(args.profile),
                  inventorySha256=digest(args.inventory), phases={})
    report = output / "native1201-legacy-world-results.json"
    try:
        saves = {}
        for phase in ("converted", "restarted"):
            server = native1201.Server(output, args.java17, launcher, phase)
            try:
                server.snapshot(phase)
                server.send("save-all flush")
                server.wait("Saved the game", 30)
            finally:
                server.close()
            snapshot = output / f"migration-{phase}.json"
            rows = read_json(snapshot)
            verify_snapshot(expected, rows)
            saves[phase] = native1201_saved.capture(output, phase, rows)
            observed = turbine(output / WORLD, positions["hydroGenerator"], data_version=native1201_saved.DATA_VERSION)
            if observed != dict(expected["turbine"], id="baseelectric:hydroturbine"):
                raise AssertionError("Saved turbine identity, position or parent changed")
            result["phases"][phase] = dict(status="PASS", witnesses=len(rows), turbine=observed,
                                           snapshotSha256=digest(snapshot), serialized=saves[phase])
            write_json(report, result)
        if saves["converted"]["worldGenSettings"] != saves["restarted"]["worldGenSettings"]:
            raise AssertionError("Native world generation settings changed during restart")
        repeated = compare(output / "migration-converted.json", output / "migration-restarted.json", native_normalized)
        if repeated["status"] != "PASS":
            raise AssertionError(f"Converted machine state changed after restart: {repeated}")
        result["phases"]["operation"] = operate(output / "operation", output, target, args.java17,
                                                 args.port, positions, expected)
        result["status"] = "PASS"
    except Exception as error:
        result.update(status="FAIL", failure=repr(error))
        raise
    finally:
        result["sourceUnchanged"] = portable(fingerprint(source)) == before
        if not result["sourceUnchanged"]:
            result["status"] = "FAIL"
        write_json(report, result)
    return int(result["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
