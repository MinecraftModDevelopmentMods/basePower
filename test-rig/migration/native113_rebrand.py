"""Prove frozen 1.10/1.12 workshop upgrades and a real 1.13 cold restart."""

import argparse
import json
from pathlib import Path
import shutil
import socket
import struct
import sys
import time
import traceback

from matrix import WORLD, compare, digest, fingerprint, parse_nbt, write_json
from native113 import output_path, pinned_profile, require_disjoint
from native113_code import INVENTORY, Server, verify_code
from native113_lifecycle import prepare, read_json, require_native_boot, verify_staged


OLD_IDS = {"poweradvantage": "basepower", "steamadvantage": "basesteam",
           "electricadvantage": "baseelectric"}
FACING = ("down", "up", "north", "south", "west", "east")


def canonical(value):
    if value == "Chest":
        return "minecraft:chest"
    for old, new in OLD_IDS.items():
        for namespace in (old, new):
            for prefix in (namespace + ":", namespace + ".", "minecraft:" + namespace + "."):
                if value.startswith(prefix):
                    return new + ":" + value[len(prefix):]
    return value


def fluid_id(value):
    if value in ("crude_oil", "refined_oil"):
        return "basepower:" + value
    return canonical(value if ":" in value else "minecraft:" + value)


def item(entry):
    tag = entry.get("tag", {})
    return dict(slot=int(entry["Slot"]), item=str(entry["id"]), count=int(entry["Count"]),
                damage=int(tag.get("Damage", 0)), charge=float(tag.get("energy", 0)),
                label=str(tag.get("Witness", "")),
                enchantments=[[str(e["id"]), int(e["lvl"])] for e in tag.get("Enchantments", ())])


def expected_item(saved):
    result = dict(saved, item=canonical(saved["item"]))
    if result["item"] == "forge:bucketfilled":
        fluid, amount = result.pop("fluid")
        if amount != 1000:
            raise AssertionError("Fixture must contain a full legacy bucket")
        result["item"] = fluid_id(fluid) + "_bucket"
    return result


def verify_snapshot(before, rows):
    witnesses = before["witnesses"]
    actual = {row["name"]: row for row in rows}
    if len(rows) != len(witnesses) or actual.keys() != witnesses.keys():
        raise AssertionError("Migration probe returned a different witness set")
    for name, saved in witnesses.items():
        row = actual[name]
        if row["actualName"] != canonical(saved["block"]):
            raise AssertionError(f"{name}: unexpected block {row['actualName']}, expected {saved['block']}")
        if name in ("steamDrill", "electricDrill", "conveyor"):
            facing = FACING[saved["meta"] & 7]
            if row["properties"].get("facing") != facing:
                raise AssertionError(f"{name}: expected facing {facing}, got {row['properties']}")
        if saved["tile"] is None:
            if "nbtBase64" in row:
                raise AssertionError(f"{name}: unexpected tile entity")
            continue
        data = parse_nbt(row)
        if data["id"] != canonical(saved["tile"]):
            raise AssertionError(f"{name}: unexpected tile {data['id']}")
        if "items" in saved:
            wanted = sorted((expected_item(e) for e in saved["items"]), key=lambda e: e["slot"])
            items = sorted((item(e) for e in data.get("Items", ())), key=lambda e: e["slot"])
            if items != wanted:
                raise AssertionError(f"{name}: inventory changed: {wanted} -> {items}")
        if "energy" in saved:
            energy = struct.unpack(">f", struct.pack(">i", int(data["Energy"][0])))[0]
            if energy != saved["energy"]:
                raise AssertionError(f"{name}: energy changed: {saved['energy']} -> {energy}")
        if "fluid" in saved:
            tank = data["Tank"]
            expected = [fluid_id(saved["fluid"][0]), saved["fluid"][1]]
            if [tank["FluidName"], tank["Amount"]] != expected:
                raise AssertionError(f"{name}: tank changed: {expected} -> {tank}")


def operate(runtime, target, pins, frozen, positions, expected, java, port):
    launcher = prepare(runtime, target, [], port)
    verify_code(runtime, pins["serverCode"])
    write_json(runtime / INVENTORY, pins["serverCode"])
    shutil.copytree(frozen / WORLD, runtime / WORLD)
    shutil.copytree(frozen / "config", runtime / "config")
    shutil.copy2(frozen / "server.properties", runtime / "server.properties")
    shutil.copy2(frozen / "migration-fixture.json", runtime / "migration-fixture.json")
    (runtime / "migration-operate.allow").touch()
    jobs = (("sourceChest", "destination", "dirt", "dirt", 8),
            ("steamCrusher", "steamCrusher", "stone", "cobblestone", 1),
            ("electricFurnace", "electricFurnace", "iron_ore", "iron_ingot", 1))
    server = Server(runtime, java, launcher, "resumed")
    try:
        for source, _, resource, _, count in jobs:
            x, y, z = positions[source]
            server.send(f"replaceitem block {x} {y} {z} container.0 minecraft:{resource} {count}")
            server.wait("Replaced a slot at", 30)
        time.sleep(30)
        server.snapshot("resumed")
        server.send("save-all flush")
        server.wait("Saved the game", 30)
    finally:
        server.close()
    require_native_boot(runtime / "console-resumed.log")
    verify_staged(runtime, target)
    rows = {row["name"]: row for row in read_json(runtime / "migration-resumed.json")}
    outputs = {}
    for source, sink, resource, product, count in jobs:
        old = sum(e["count"] for e in expected["witnesses"][sink].get("items", ())
                  if e["item"] == "minecraft:" + product)
        live = sum(int(e["Count"]) for e in parse_nbt(rows[sink]).get("Items", ())
                   if e["id"] == "minecraft:" + product)
        remaining = sum(int(e["Count"]) for e in parse_nbt(rows[source]).get("Items", ())
                        if e["id"] == "minecraft:" + resource)
        if live != old + count or remaining:
            raise AssertionError(f"{sink}: expected {old + count} {product} and no remaining input, got {live}/{remaining}")
        outputs[sink] = dict(before=old, after=live, item="minecraft:" + product)
    return dict(status="PASS", outputs=outputs, snapshotSha256=digest(runtime / "migration-resumed.json"))


def run(args):
    sys.path.insert(0, str(args.previous_rig.resolve()))
    from native112_rebrand import observe, observe_turbine

    source = args.fixture / ("target" if args.released112 else "source")
    receipt = args.fixture / "rebrand-upgrade-results.json"
    history = read_json(receipt)
    if history["status"] != "PASS":
        raise AssertionError("A passing retained source qualification is required")
    expected = history["phases"]["restarted" if args.released112 else "source"]
    positions = {key: tuple(value) for key, value in history["positions"].items()}
    target = pinned_profile(args.target_manifest, "target113")
    pins = read_json(args.target_manifest)["target113"]
    output = output_path(args.output)
    for protected in (source, args.fixture, args.source_manifest, args.target_manifest, target[0], *target[2]):
        require_disjoint(output, protected)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", args.port))
    source_world = fingerprint(source / WORLD)
    source_config = fingerprint(source / "config")
    if not args.released112 and source_world != read_json(args.fixture / "source-world-hashes.json"):
        raise AssertionError("Retained 1.10 world differs from its earlier qualification")
    source_pins = read_json(args.source_manifest)
    source_jars = {p.name: digest(p) for p in (source / "mods").glob("*.jar")}
    if args.released112:
        for entry in source_pins["releases"]:
            jar = entry["name"] + "-" + entry["version"] + ".jar"
            if source_jars.get(jar) != entry["artifact"]["sha256"]:
                raise AssertionError(f"Source does not use the released 1.12 jar: {jar}")
    else:
        expected_jars = {Path(row["path"]).name: row["sha256"] for row in source_pins["jars"]}
        if source_jars != expected_jars or digest(args.source_manifest) != history["sourceManifestSha256"]:
            raise AssertionError("Retained 1.10 source mods do not match the original pins")
    observed = json.loads(json.dumps(observe(source / WORLD, positions, require_modern=args.released112)))
    for key, value in observed.items():
        if value != expected[key]:
            raise AssertionError(f"Retained source state changed: {key}")
    verify_code(target[0], pins["serverCode"])
    launcher = prepare(output, target, [], args.port)
    verify_code(output, pins["serverCode"])
    write_json(output / INVENTORY, pins["serverCode"])
    shutil.copytree(source / WORLD, output / WORLD)
    shutil.copytree(source / "config", output / "config")
    properties = output / "server.properties"
    lines = [line for line in properties.read_text().splitlines() if not line.startswith("generator-settings=")]
    properties.write_text("\n".join(lines) + "\n", encoding="ascii")
    write_json(output / "migration-fixture.json", [
        dict(name=name, x=pos[0], y=pos[1], z=pos[2]) for name, pos in positions.items()])
    result_path = output / "native113-rebrand-results.json"
    result = dict(status="RUNNING", source=str(source.resolve()), sourceJars=source_jars,
                  sourceWorld=source_world, sourceManifestSha256=digest(args.source_manifest),
                  previousReceiptSha256=digest(receipt), targetManifestSha256=digest(args.target_manifest),
                  phases={})
    write_json(result_path, result)
    try:
        for phase in ("converted", "restarted"):
            server = Server(output, args.java8, launcher, phase)
            try:
                server.snapshot(phase)
                server.send("save-all flush")
                server.wait("Saved the game", 30)
            finally:
                server.close()
            require_native_boot(output / f"console-{phase}.log")
            verify_staged(output, target)
            snapshot = output / f"migration-{phase}.json"
            verify_snapshot(expected, read_json(snapshot))
            turbine = observe_turbine(output / WORLD, positions["hydroGenerator"])
            if turbine != dict(expected["turbine"], id="baseelectric:hydroturbine"):
                raise AssertionError(f"Saved turbine was changed or replaced: {turbine}")
            result["phases"][phase] = dict(status="PASS", witnesses=len(positions),
                                           snapshotSha256=digest(snapshot), turbine=turbine)
            write_json(result_path, result)
        repeated = compare(output / "migration-converted.json", output / "migration-restarted.json")
        if repeated["status"] != "PASS":
            raise AssertionError(f"Converted save changed during cold restart: {repeated}")
        write_json(output / "state-comparison.json", dict(
            conversion=result["phases"]["converted"], restart=result["phases"]["restarted"],
            idempotence=repeated))
        if args.operate:
            result["phases"]["resumed"] = operate(output / "operation", target, pins, output,
                                                   positions, expected, args.java8, args.port)
        result["status"] = "PASS"
    except Exception as error:
        result.update(status="FAIL", failure=str(error), traceback=traceback.format_exc())
    finally:
        result["sourceUnchanged"] = (source_world == fingerprint(source / WORLD)
                                     and source_config == fingerprint(source / "config"))
        if not result["sourceUnchanged"]:
            result["status"] = "FAIL"
        write_json(result_path, result)
    print(result["status"], result_path, flush=True)
    return int(result["status"] != "PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("previous-rig", "fixture", "source-manifest", "target-manifest", "java8", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--released112", action="store_true")
    parser.add_argument("--operate", action="store_true", help="Run imported machines on a second disposable copy")
    raise SystemExit(run(parser.parse_args()))
