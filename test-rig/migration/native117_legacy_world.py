"""Run an already qualified 1.10/1.12 machine save directly in base* 1.17.1."""

import argparse
import json
from pathlib import Path
import shutil
import socket
import sys
import time

from nbtlib import IntArray

from matrix import WORLD, compare, digest, fingerprint, parse_nbt, write_json
from native117 import native_normalized, output_path, pinned_profile, require_disjoint
from native117_code import Server
from native117_commands import native
from native117_entity_storage import _entity_chunk, _payloads
from native117_format import uuid_longs
from native117_lifecycle import prepare, require_native_boot, verify_staged
from native117_save_io import capture_save, require_same_settings


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def turbine(world, position, *, data_version=2730):
    x, _, z = position
    cx, cz = x // 16, z // 16
    rx, rz = cx // 32, cz // 32
    region = world / "entities" / f"r.{rx}.{rz}.mca"
    payloads = dict(_payloads(region.read_bytes(), rx, rz))
    entities = _entity_chunk(payloads[(cx, cz)], (cx, cz), data_version)
    actors = [actor for actor in entities if str(actor["id"]) == "baseelectric:hydroturbine"]
    if len(actors) != 1:
        raise AssertionError("Expected one saved hydroelectric turbine")
    actor = actors[0]
    words = actor.get("UUID")
    if not isinstance(words, IntArray) or any(key in actor for key in ("UUIDMost", "UUIDLeast")):
        raise ValueError("Saved turbine must use the native UUID int array")
    return dict(id=str(actor["id"]), parent=[int(v) for v in actor["parentPos"]],
                position=[float(v) for v in actor["Pos"]], uuid=list(uuid_longs(words.tolist())))


def operate(runtime, target, frozen, positions, expected, java, port):
    launcher = prepare(runtime, target, [], port, frozen)
    (runtime / "migration-operate.allow").touch()
    jobs = (("sourceChest", "destination", "dirt", "dirt", 8),
            ("steamCrusher", "steamCrusher", "stone", "cobblestone", 1),
            ("electricFurnace", "electricFurnace", "iron_ore", "iron_ingot", 1))
    server = Server(runtime, java, launcher, "resumed")
    try:
        for source, _, item, _, count in jobs:
            x, y, z = positions[source]
            server.send(native(f"replaceitem block {x} {y} {z} container.0 minecraft:{item} {count}"))
            server.wait("Replaced a slot", 30)
        time.sleep(30)
        server.snapshot("resumed")
        server.send("save-all flush")
        server.wait("Saved the game", 30)
    finally:
        server.close()
    require_native_boot(runtime / "console-resumed.log")
    verify_staged(runtime, target)
    rows = {row["name"]: row for row in read(runtime / "migration-resumed.json")}
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


def run(args):
    sys.path.append(str(args.previous_rig.resolve()))
    from native116_rebrand import verify_snapshot

    baseline = read(args.previous_receipt)
    fixture = read(args.fixture / "rebrand-upgrade-results.json")
    source = Path(baseline["source"]).resolve()
    if (baseline["status"] != "PASS" or not baseline["sourceUnchanged"] or fixture["status"] != "PASS"
            or digest(args.fixture / "rebrand-upgrade-results.json") != baseline["previousReceiptSha256"]):
        raise AssertionError("The fixture must match a passing, unchanged 1.16 migration receipt")
    if source != (args.fixture / ("target" if args.released112 else "source")).resolve():
        raise AssertionError("Wrong previously qualified source world")
    before = fingerprint(source)
    if fingerprint(source / WORLD) != baseline["sourceWorld"]:
        raise AssertionError("The retained world has changed since qualification")
    if {file.name: digest(file) for file in (source / "mods").glob("*.jar")} != baseline["sourceJars"]:
        raise AssertionError("The retained source mods have changed since qualification")
    expected = fixture["phases"]["restarted" if args.released112 else "source"]
    positions = fixture["positions"]
    target = pinned_profile(args.target_manifest.resolve(), "target117")
    output = output_path(args.output)
    for protected in (source, args.target_manifest, target[0], *target[2]):
        require_disjoint(output, protected)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", args.port))

    result = dict(status="RUNNING", source=str(source), sourceFingerprint=before,
                  priorReceiptSha256=digest(args.previous_receipt),
                  targetManifestSha256=digest(args.target_manifest), phases={})
    launcher = prepare(output, target, [], args.port)
    shutil.copytree(source / WORLD, output / WORLD)
    shutil.copytree(source / "config", output / "config")
    write_json(output / "migration-fixture.json",
               [dict(name=name, x=pos[0], y=pos[1], z=pos[2]) for name, pos in positions.items()])
    report = output / "native117-legacy-world-results.json"
    try:
        saves = {}
        for phase in ("converted", "restarted"):
            server = Server(output, args.java16, launcher, phase)
            try:
                server.snapshot(phase)
                server.send("save-all flush")
                server.wait("Saved the game", 30)
            finally:
                server.close()
            require_native_boot(output / f"console-{phase}.log")
            verify_staged(output, target)
            snapshot = output / f"migration-{phase}.json"
            verify_snapshot(expected, read(snapshot))
            saves[phase] = capture_save(output, phase, snapshot)
            observed = turbine(output / WORLD, positions["hydroGenerator"])
            if observed != dict(expected["turbine"], id="baseelectric:hydroturbine"):
                raise AssertionError("Saved turbine identity, position or parent changed")
            result["phases"][phase] = dict(status="PASS", witnesses=len(positions), turbine=observed,
                                           snapshotSha256=digest(snapshot))
            write_json(report, result)
        require_same_settings(saves["converted"], saves["restarted"])
        repeated = compare(output / "migration-converted.json", output / "migration-restarted.json", native_normalized)
        if repeated["status"] != "PASS":
            raise AssertionError("Converted save changed after restart")
        result["phases"]["operation"] = operate(output / "operation", target, output,
                                                 positions, expected, args.java16, args.port)
        result["status"] = "PASS"
    except Exception as error:
        result.update(status="FAIL", failure=repr(error))
        raise
    finally:
        result["sourceUnchanged"] = fingerprint(source) == before
        if not result["sourceUnchanged"]:
            result["status"] = "FAIL"
        write_json(report, result)
    return int(result["status"] != "PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("previous-rig", "previous-receipt", "fixture", "target-manifest", "java16", "output"):
        parser.add_argument("--" + option, type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--released112", action="store_true")
    raise SystemExit(run(parser.parse_args()))
