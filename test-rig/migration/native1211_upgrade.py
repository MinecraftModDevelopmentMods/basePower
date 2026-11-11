"""Strict shared family upgrade using sealed historical or modern source receipts."""

import argparse
import gzip
import io
from pathlib import Path
import shutil
import time
import traceback
import zlib

import nbtlib

from matrix import WORLD, digest, differences, fingerprint, parse_nbt, plain, write_json
from native117 import native_normalized, output_path, require_disjoint
from native117_format import decode_palette
from native117_legacy_world import turbine
from native1211 import (EXPLORATORY, FORGE, LAUNCHER, MINECRAFT, Server, java21, read_family_target, read_json, stage,
                        validate_hash, verify_runtime)
from transformer_order import read_region


DATA_VERSION = 3955
JOBS = (("sourceChest", "destination", "dirt", "dirt", 8),
        ("steamCrusher", "steamCrusher", "stone", "cobblestone", 1),
        ("electricFurnace", "electricFurnace", "iron_ore", "iron_ingot", 1))


def components(value):
    """Translate only the 1.20.1 item representation, never a 1.21.1 observation."""
    if isinstance(value, list):
        return [components(item) for item in value]
    if not isinstance(value, dict):
        return value
    if "Count" not in value or "id" not in value:
        return {key: components(item) for key, item in value.items()}
    stack = {key: components(item) for key, item in value.items() if key not in ("Count", "tag")}
    stack["count"] = value["Count"]
    old = dict(value.get("tag", {}))
    patch = {}
    if "Damage" in old:
        patch["minecraft:damage"] = old.pop("Damage")
    for before, after in (("Enchantments", "minecraft:enchantments"),
                          ("StoredEnchantments", "minecraft:stored_enchantments")):
        if before in old:
            entries = old.pop(before)
            levels = {row["id"]: row["lvl"] for row in entries}
            if len(levels) != len(entries):
                raise AssertionError("Duplicate enchantment witness")
            patch[after] = {"levels": levels}
    if old:
        patch["minecraft:custom_data"] = components(old)
    if patch:
        stack["components"] = patch
    return stack


def comparison(before, after, *, modern):
    def index(rows):
        result = {(row["name"], row["x"], row["y"], row["z"]): row for row in rows}
        if len(rows) != len(result):
            raise AssertionError("Duplicate machine witness")
        return result

    first, second = index(before), index(after)
    if first.keys() != second.keys():
        raise AssertionError("Machine witness coordinates or names changed")
    findings = []
    for identity, left in first.items():
        right = second[identity]
        key = str(identity)
        for field in ("actualName", "properties"):
            if left[field] != right[field]:
                findings.append(dict(path=f"{key}.{field}", expected=left[field], actual=right[field]))
        if bool(left.get("nbtBase64")) != bool(right.get("nbtBase64")):
            findings.append(dict(path=f"{key}.tile", expected=bool(left.get("nbtBase64")),
                                 actual=bool(right.get("nbtBase64"))))
        elif left.get("nbtBase64"):
            expected = native_normalized(plain(parse_nbt(left)))
            if modern:
                expected = components(expected)
            actual = native_normalized(plain(parse_nbt(right)))
            findings.extend(differences(expected, actual, key + ".nbt"))
            for row in differences(actual, expected, key + ".nbt"):
                if row["actual"] == "<missing>":
                    findings.append(dict(path=row["path"], expected="<missing>", actual=row["expected"]))
    return dict(status="FAIL" if findings else "PASS", witnesses=len(first), differences=findings)


def capture(runtime, label, rows):
    world = runtime / WORLD
    output = runtime / "native1211-save" / label
    output.mkdir(parents=True)
    shutil.copy2(world / "level.dat", output / "level.dat")
    level = nbtlib.load(output / "level.dat")["Data"]
    if int(level["DataVersion"]) != DATA_VERSION:
        raise AssertionError("Saved level did not identify Minecraft 1.21.1")
    regions, chunks = {}, {}
    for row in rows:
        x, y, z = pos = tuple(row[axis] for axis in ("x", "y", "z"))
        cx, cz = key = x // 16, z // 16
        if key not in chunks:
            rx, rz = region = cx // 32, cz // 32
            if region not in regions:
                _, regions[region] = read_region(world / "region" / f"r.{rx}.{rz}.mca")
            payload = regions[region][(cx & 31) + (cz & 31) * 32]
            (output / f"{cx}_{cz}.chunk").write_bytes(payload)
            if len(payload) < 6 or int.from_bytes(payload[:4], "big") != len(payload) - 4:
                raise ValueError("Malformed chunk length")
            decoders = {1: gzip.decompress, 2: zlib.decompress, 3: lambda data: data}
            chunk = plain(nbtlib.File.parse(io.BytesIO(decoders[payload[4]](payload[5:]))).unpack())
            if chunk["DataVersion"] != DATA_VERSION or "Level" in chunk or (chunk["xPos"], chunk["zPos"]) != key:
                raise AssertionError("Saved chunk differs from its native 1.21.1 region slot")
            sections, tiles = {}, {}
            for section in chunk["sections"]:
                section_y = section["Y"]
                if section_y in sections or any(k in section for k in ("Blocks", "Palette", "BlockStates")):
                    raise AssertionError("Duplicate section or old palette")
                states = section.get("block_states")
                sections[section_y] = decode_palette(states["palette"], states.get("data", [])) if states else None
            for tile in chunk.get("block_entities", ()):
                location = tile["x"], tile["y"], tile["z"]
                if location in tiles:
                    raise AssertionError("Duplicate saved tile")
                tile.pop("keepPacked", None)
                tiles[location] = tile
            chunks[key] = sections, tiles
        sections, tiles = chunks[key]
        palette = sections.get(y // 16)
        block = palette[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] if palette else {"Name": "minecraft:air"}
        if (block["Name"], block.get("Properties", {})) != (row["actualName"], row["properties"]):
            raise AssertionError(f"Saved block differs from live state at {pos}: {block}")
        expected = plain(parse_nbt(row)) if row.get("nbtBase64") else None
        if tiles.get(pos) != expected:
            raise AssertionError(f"Saved block entity differs from live state at {pos}: {tiles.get(pos)} != {expected}")
    result = dict(status="PASS", witnesses=len(rows), nativeDataVersion=DATA_VERSION,
                  worldGenSettings=plain(level["WorldGenSettings"].unpack()),
                  files={path.name: digest(path) for path in output.iterdir()})
    write_json(output / "receipt.json", result)
    return result


def copy_source(source, runtime):
    protected = fingerprint(source / WORLD)
    shutil.copytree(source / WORLD, runtime / WORLD)
    shutil.copytree(source / "config", runtime / "config", dirs_exist_ok=True)
    if protected != fingerprint(runtime / WORLD):
        raise AssertionError("Staged world is not an exact copy")


def with_server(runtime, java, label, action):
    server = Server(runtime, java, LAUNCHER, label)
    try:
        action(server)
        server.snapshot(label)
        server.send("save-all flush")
        server.wait("Saved the game", 30)
    except BaseException as failure:
        try:
            server.close()
        except BaseException as shutdown:
            failure.add_note(f"Shutdown/log audit also failed: {shutdown!r}")
        raise
    else:
        server.close()
    return read_json(runtime / f"migration-{label}.json")


def count(rows, name, item):
    tile = next(row for row in rows if row["name"] == name)
    return sum(int(stack["count"]) for stack in parse_nbt(tile).get("Items", ()) if stack["id"] == "minecraft:" + item)


def operate(runtime, frozen, target, java, port, positions, before):
    expected = stage(runtime, *target, port)
    copy_source(frozen, runtime)
    shutil.copy2(frozen / "migration-fixture.json", runtime / "migration-fixture.json")
    (runtime / "migration-operate.allow").touch()

    def start(server):
        for source, _, item, _, amount in JOBS:
            x, y, z = positions[source]
            server.send(f"item replace block {x} {y} {z} container.0 with minecraft:{item} {amount}")
            server.wait("Replaced a slot", 30)
        time.sleep(30)

    try:
        rows = with_server(runtime, java, "operation", start)
    finally:
        verify_runtime(runtime, target[1], expected)
    results = {}
    for source, sink, item, product, amount in JOBS:
        old, new, remaining = count(before, sink, product), count(rows, sink, product), count(rows, source, item)
        if new != old + amount or remaining:
            raise AssertionError(f"{sink}: expected {old + amount} {product} and no input, got {new}/{remaining}")
        results[sink] = dict(before=old, after=new, item="minecraft:" + product)
    return results


def run(args):
    source = args.source.resolve(strict=True)
    reference = args.legacy_reference or source
    prior_path = reference / ("native1201-legacy-world-results.json" if args.legacy_reference
                              else getattr(args, "source_receipt_name", "native1201-modern-upgrade-results.json"))
    validate_hash(prior_path, args.receipt_sha256)
    prior = read_json(prior_path)
    restart = prior["phases"]["restarted"]
    if prior["status"] != "PASS" or not prior["sourceUnchanged"] or restart["status"] != "PASS":
        raise AssertionError("Missing passing source and restart receipts")
    old_snapshot = reference / "migration-restarted.json"
    validate_hash(old_snapshot, restart["snapshotSha256"])
    old_rows = read_json(old_snapshot)
    target = read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, java_version = java21(args.java)
    runtime = output_path(args.output)
    for protected in (source, reference, args.seed, args.profile, args.inventory, java, *(mod[1] for mod in target[2].values())):
        require_disjoint(runtime, protected)
    before, seed_before = fingerprint(source), fingerprint(args.seed)
    if args.legacy_reference:
        def portable(hashes):
            return {key.replace("\\", "/"): sha.upper() for key, sha in hashes.items()}
        if source != Path(prior["source"]).resolve() or portable(before) != portable(prior["sourceFingerprint"]):
            raise AssertionError("Historical source changed since the independent 1.20.1 run")
    expected = stage(runtime, *target, args.port)
    result = dict(status="RUNNING", qualificationStatus=EXPLORATORY, minecraft=MINECRAFT, forge=FORGE,
                  source=str(source), reference=str(reference), sourceReceiptSha256=digest(prior_path), profileSha256=digest(args.profile),
                  inventorySha256=digest(args.inventory), javaSha256=digest(java), javaVersion=java_version,
                  jars=expected, phases={})
    route = "historical" if args.legacy_reference else "modern"
    receipt = runtime / f"native1211-{route}-upgrade-results.json"
    write_json(receipt, result)
    try:
        copy_source(source, runtime)
        fixture = read_json(reference / "migration-fixture.json")
        write_json(runtime / "migration-fixture.json", fixture)
        positions = {row["name"]: [row["x"], row["y"], row["z"]] for row in fixture}
        saved = []
        for label in ("converted", "restarted"):
            snapshot = runtime / f"migration-{label}.json"
            try:
                rows = with_server(runtime, java, label, lambda server: None)
            except Exception as failure:
                if snapshot.is_file():
                    try:
                        phase = comparison(old_rows, read_json(snapshot), modern=True)
                        phase.update(snapshotSha256=digest(snapshot), evidenceStatus="OBSERVATION_ONLY",
                                     reason="Server or log validation failed")
                        result["phases"][label] = phase
                    except Exception as diagnostic:
                        failure.add_note(f"Snapshot comparison also failed: {diagnostic!r}")
                raise
            phase = comparison(old_rows, rows, modern=True)
            phase["snapshotSha256"] = digest(snapshot)
            result["phases"][label] = phase
            write_json(receipt, result)
            if phase["status"] != "PASS":
                raise AssertionError(f"Strict 1.20.1 witness mismatch in {label}: {phase['differences'][:5]}")
            phase["serialized"] = capture(runtime, label, rows)
            phase["turbine"] = turbine(runtime / WORLD, positions["hydroGenerator"], data_version=DATA_VERSION)
            if phase["turbine"] != restart["turbine"]:
                raise AssertionError("Turbine UUID, parent or position changed")
            saved.append((rows, phase["serialized"]["worldGenSettings"]))
        if saved[0][1] != saved[1][1] or comparison(saved[0][0], saved[1][0], modern=False)["status"] != "PASS":
            raise AssertionError("Native machine state or worldgen settings changed during restart")
        result["phases"]["operation"] = operate(runtime / "operation", runtime, target, java, args.port,
                                                  positions, saved[1][0])
        result["status"] = "PASS"
    except BaseException as error:
        result.update(status="FAIL", failure=repr(error), traceback=traceback.format_exc())
        raise
    finally:
        try:
            verify_runtime(runtime, target[1], expected)
            result["sourceUnchanged"] = fingerprint(source) == before
            result["seedUnchanged"] = fingerprint(args.seed) == seed_before
            if not result["sourceUnchanged"] or not result["seedUnchanged"]:
                raise AssertionError("Protected source or seed changed")
        except Exception as audit:
            result.update(status="FAIL", auditError=repr(audit))
        finally:
            write_json(receipt, result)
    return int(result["status"] != "PASS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("source", "profile", "seed", "inventory", "java", "output"):
        parser.add_argument("--" + key, type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--legacy-reference", type=lambda value: Path(value).resolve(strict=True),
                        help="Passing independent 1.20.1 reference for the exact historical source directory")
    parser.add_argument("--source-receipt-name", choices=("native1201-modern-upgrade-results.json",
                        "native1206-modern-upgrade-results.json"), default="native1201-modern-upgrade-results.json")
    for key in ("receipt", "inventory"):
        parser.add_argument("--" + key + "-sha256", required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("A dedicated unprivileged localhost port is required")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
