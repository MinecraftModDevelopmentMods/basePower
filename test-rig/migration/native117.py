"""Separate native117 destination pinning and frozen-copy migration witness runner.

Never builds or launches a source. Historical matrix/connected/actor campaigns remain
separate and their failures are not reclassified by this destination-only runner.
"""

import argparse
import json
from pathlib import Path
import re
import tomllib
import zipfile
import shutil
import time
import os

import nbtlib

from matrix import (WORLD, compare, copy_fixture, differences, digest, fingerprint,
                    normalized, parse_nbt, plain, stage as historical_stage, write_json)
from native117_code import INVENTORY, Server, verify_code, verify_if_sealed
from native117_identity import target_id


RIG = Path(__file__).resolve().parents[1]
LEGACY_POWER = RIG.parents[2] / "PA113/PowerAdvantage"
LAUNCHER = "libraries/net/minecraftforge/forge/1.17.1-37.1.1/" + ("win_args.txt" if os.name == "nt" else "unix_args.txt")
TEST_FLAT = "advantageworkstest:test_flat"


def stage(runtime, target):
    verify_if_sealed(target[0])
    launcher = historical_stage(runtime, target)
    if (target[0] / INVENTORY).is_file():
        shutil.copy2(target[0] / INVENTORY, runtime / INVENTORY)
        verify_code(runtime)
    configure_creation(runtime)
    return launcher


def configure_creation(runtime):
    # Forge's factory supplies registry-aware objects; vanilla flat JsonOps cannot.
    # Only launch properties change. An imported level.dat is never rewritten.
    settings = {"level-type": TEST_FLAT, "generate-structures": "false", "generator-settings": ""}
    path = runtime / "server.properties"
    lines = [line for line in path.read_text().splitlines() if line.partition("=")[0] not in settings]
    path.write_text("\n".join(lines) + "\n" + "".join(f"{key}={value}\n" for key, value in settings.items()))


def boot(runtime, target, java, port):
    launcher = stage(runtime, target)
    properties = runtime / "server.properties"
    properties.write_text(properties.read_text().replace("server-port=25584", f"server-port={port}"))
    return launcher


def native_normalized(value, enchantments=None):
    result = normalized(value, enchantments)
    # Native 1.14 LockCode.write omits an empty lock. Give only vanilla chest
    # roots their canonical unlocked value, retaining strict checks on real locks.
    if isinstance(result, dict) and result.get("id") == "minecraft:chest":
        result.setdefault("Lock", "")
    return result


def source_normalized(value, enchantments=None):
    def remap_nbt(entry):
        if isinstance(entry, list):
            return [remap_nbt(row) for row in entry]
        if not isinstance(entry, dict):
            return entry
        return {key: target_id(row) if key in ("id", "FluidName", "FluidID") else remap_nbt(row)
                for key, row in entry.items()}

    return remap_nbt(native_normalized(value, enchantments))


def compare_upgraded(source, target):
    before = json.loads(source.read_text(encoding="utf-8"))
    after = json.loads(target.read_text(encoding="utf-8"))
    if len(before) != len(after):
        raise AssertionError("Witness count changed")

    def enchantments(snapshot):
        level = nbtlib.load(snapshot.parent / WORLD / "level.dat")
        entries = level.get("FML", {}).get("Registries", {}).get("minecraft:enchantments", {}).get("ids", ())
        return {int(entry["V"]): str(entry["K"]) for entry in entries}

    old_ids, new_ids = enchantments(source), enchantments(target)
    findings = []
    counts = {"blocks": len(before), "tiles": 0, "items": 0}
    for old, new in zip(before, after):
        pos = f"{old['name']}@{old['x']},{old['y']},{old['z']}"
        if any(old[field] != new[field] for field in ("name", "x", "y", "z")):
            raise AssertionError("Witness moved or reordered at " + pos)
        expected_id = target_id(old["actualName"])
        if expected_id != new["actualName"]:
            findings.append({"path": pos, "expected": expected_id, "actual": new["actualName"]})
        findings.extend(differences(old["properties"], new["properties"], pos + ".state"))
        if "nbt" in old:
            counts["tiles"] += 1
            old_nbt = source_normalized(plain(parse_nbt(old)), old_ids)
            counts["items"] += sum(int(item.get("Count", 0)) for item in old_nbt.get("Items", ()))
            if "nbt" not in new:
                findings.append({"path": pos + ".tile", "expected": "tile", "actual": "<missing>"})
            else:
                findings.extend(differences(old_nbt, native_normalized(plain(parse_nbt(new)), new_ids), pos + ".nbt"))
        elif "nbt" in new:
            findings.append({"path": pos + ".tile", "expected": "<missing>", "actual": "tile"})
    return {"status": "FAIL" if findings else "PASS", "witnesses": counts, "differences": findings}


def require_clean_log(path):
    verify_if_sealed(path.parent)
    text = path.read_text(encoding="utf-8", errors="replace")
    failures = runtime_error_lines(text)
    if failures:
        raise AssertionError(f"Native runtime errors in {path}: {failures[:5]}")


def runtime_error_lines(text):
    failures = []
    for original in text.splitlines():
        line = re.sub(r"\x1b\[[0-9;]*m", "", original)
        if ("/ERROR]" in line or "/FATAL]" in line
                or re.search(r"\b(?:[\w$]+\.)*[\w$]+(?:Exception|Error)\b", line)
                or re.search(r"Missing data pack mod:(?:poweradvantage|steamadvantage|electricadvantage)(?:\s|$)", line)
                or any(marker in line for marker in (
                    "Exception in thread", "Caused by:", "Failed to reload data packs",
                    "Encountered an unexpected exception"))):
            failures.append(original)
    return failures


def start_snapshot(runtime, java, launcher, label, build=False):
    server = Server(runtime, java, launcher, label)
    try:
        server.snapshot(label, build)
        server.send("save-all flush")
        time.sleep(2)
    finally:
        server.close()


def output_path(path):
    path = path.resolve()
    if not path.is_relative_to((RIG / "build").resolve()):
        raise ValueError("Native117 outputs must be new paths under test-rig/build")
    if path.exists():
        raise FileExistsError(path)
    return path


def require_disjoint(output, protected):
    output, protected = output.resolve(), protected.resolve()
    if output.is_relative_to(protected) or protected.is_relative_to(output):
        raise ValueError(f"Output overlaps a protected input: {protected}")


def jar_identity(path, mod_id, version):
    with zipfile.ZipFile(path) as archive:
        text = archive.read("META-INF/mods.toml").decode("utf-8")
    for fields in tomllib.loads(text).get("mods", []):
        if fields.get("modId") == mod_id and fields.get("version") == version:
            return
    raise ValueError(f"Wrong native mod identity/version: {path}")


def pin_target(profile_path, seed, output):
    output = output_path(output)
    seed = seed.resolve()
    require_disjoint(output, seed)
    profile = json.loads(profile_path.read_text(encoding="utf-8"))
    if (profile.get("minecraftVersion") != "1.17.1" or profile.get("mode") != "packaged"
            or profile.get("portStatus") != "PORTED_UNQUALIFIED"
            or profile.get("forge", {}).get("version") != "1.17.1-37.1.1"):
        raise ValueError("A ported native117 packaged destination profile is required")
    if not (seed / LAUNCHER).is_file() or not (seed / "libraries").is_dir():
        raise FileNotFoundError("Supply an installed native Forge 37.1.1 server seed")
    server_code = verify_code(seed)
    jars = []
    for mod in profile["mods"]:
        if not re.fullmatch(r"[A-Fa-f0-9]{64}", mod.get("sha256") or ""):
            raise ValueError("Pending native117 artifact pin: " + mod["id"])
        path = (RIG / mod["path"]).resolve()
        require_disjoint(output, path)
        if not mod["version"].endswith(".117011") or path.name != mod["file"] or "deobf" in path.name:
            raise ValueError(f"Not a native117 packaged input: {path}")
        jar_identity(path, mod["id"], mod["version"])
        actual = digest(path)
        if mod.get("sha256") and actual != mod["sha256"].upper():
            raise AssertionError(f"Destination jar hash mismatch: {path}")
        entry = {"path": str(path), "sha256": actual, "id": mod["id"], "version": mod["version"]}
        for field in ("originPath", "sourceRef"):
            if field in mod:
                entry[field] = mod[field]
        jars.append(entry)
    if not {"basepower", "advantageworkstest", "orespawn"}.issubset({j["id"] for j in jars}):
        raise ValueError("Missing native Power, OreSpawn or harness input")
    write_json(output, {"target117": {
        "seed": str(seed), "launcher": LAUNCHER, "launcher_sha256": digest(seed / LAUNCHER),
        "jars": jars, "qualificationStatus": "UNQUALIFIED",
        "profile": str(profile_path.resolve()), "profileSha256": digest(profile_path),
        "legacySourceRoot": str(LEGACY_POWER),
        "serverCode": server_code,
    }})


def pinned_profile(manifest_path, key):
    entry = json.loads(manifest_path.read_text(encoding="utf-8"))[key]
    def resolve(value):
        return (manifest_path.parent / value).resolve()
    jars = []
    for jar in entry["jars"]:
        path = resolve(jar["path"])
        if digest(path) != jar["sha256"].upper():
            raise AssertionError(f"Pinned input changed: {path}")
        if key == "target117":
            if not jar.get("version", "").endswith(".117011"):
                raise ValueError("Destination manifest contains a non-native input")
            jar_identity(path, jar["id"], jar["version"])
        jars.append(path)
    seed = resolve(entry["seed"])
    if key == "target117":
        if entry["launcher"] != LAUNCHER or digest(seed / LAUNCHER) != entry["launcher_sha256"].upper():
            raise AssertionError("Native117 server launcher changed")
        identities = [jar["id"] for jar in entry["jars"]]
        if len(set(identities)) != len(identities) or not {"basepower", "advantageworkstest", "orespawn"}.issubset(identities):
            raise ValueError("Destination must contain unique native Power, OreSpawn and harness inputs")
        if not (seed / "libraries").is_dir():
            raise FileNotFoundError("Destination server libraries are missing")
        server_code = entry.get("serverCode")
        if not isinstance(server_code, dict):
            raise ValueError("Native117 destination requires a sealed server code inventory")
        verify_code(seed, server_code)
        if json.loads((seed / INVENTORY).read_text(encoding="utf-8")) != server_code:
            raise AssertionError("Native117 seed inventory receipt differs from manifest")
    return seed, entry["launcher"], jars


def run(args):
    from native117_save_io import capture_save, require_same_settings

    source = args.source.resolve()
    output = output_path(args.output)
    for protected in (source, args.source_manifest, args.target_manifest):
        require_disjoint(output, protected)
    if not re.fullmatch(r"migration-[A-Za-z0-9_-]+\.json", args.source_snapshot):
        raise ValueError("Source snapshot must name an existing snapshot inside the source runtime")
    # Explicit archived source manifests resolve against their original PA113 location,
    # never against matrix.py's copied PA114 convenience defaults.
    pinned_profile(args.source_manifest.resolve(), args.source_key)
    target = pinned_profile(args.target_manifest.resolve(), "target117")
    require_disjoint(output, target[0])
    before = fingerprint(source)
    expected = json.loads(args.source_hashes.read_text(encoding="utf-8"))
    if fingerprint(source / WORLD) != expected:
        raise AssertionError("Immutable source world does not match its approved fingerprint")
    snapshot = source / args.source_snapshot
    if not snapshot.is_file() or not (source / "migration-fixture.json").is_file():
        raise FileNotFoundError("Missing retained source snapshot or fixture specification")
    try:
        launcher = stage(output, target)
        copy_fixture(source, output)
        results, saved = {}, {}
        for label in ("converted", "restarted"):
            start_snapshot(output, args.java8, launcher, label)
            require_clean_log(output / f"console-{label}.log")
            results[label] = compare_upgraded(snapshot, output / f"migration-{label}.json")
            saved[label] = capture_save(output, label)
        require_same_settings(saved["converted"], saved["restarted"])
        results["idempotence"] = compare(output / "migration-converted.json", output / "migration-restarted.json", native_normalized)
        write_json(output / "state-comparison.json", results)
        write_json(output / "native117-provenance.json", {
            "source": str(source), "sourceManifest": str(args.source_manifest.resolve()),
            "sourceManifestSha256": digest(args.source_manifest), "sourceKey": args.source_key,
            "sourceSnapshotSha256": digest(snapshot), "sourceHashes": expected,
            "targetManifest": str(args.target_manifest.resolve()), "targetManifestSha256": digest(args.target_manifest),
            "scope": "frozen-copy-state-and-restart-only",
            "nativeSaveFormat": saved,
            "platformNormalization": "Only vanilla chest roots treat omitted Lock as the canonical empty string; nonempty locks remain strict.",
        })
        return int(any(result["status"] != "PASS" for result in results.values()))
    finally:
        if fingerprint(source) != before:
            raise AssertionError("Protected source runtime changed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    pin = commands.add_parser("pin-target", help="Pin actual destination jars and an installed server, without opening worlds")
    pin.add_argument("--profile", type=Path, default=RIG / "profiles/native117/packaged-current.json")
    pin.add_argument("--server-seed", type=Path, required=True)
    pin.add_argument("--output", type=Path, required=True)
    launch = commands.add_parser("run", help="Convert only a fingerprinted copy; never construct or operate machinery")
    launch.add_argument("--source", type=Path, required=True)
    launch.add_argument("--source-manifest", type=Path, required=True)
    launch.add_argument("--source-key", required=True)
    launch.add_argument("--source-hashes", type=Path, required=True)
    launch.add_argument("--source-snapshot", default="migration-source.json")
    launch.add_argument("--target-manifest", type=Path, required=True)
    launch.add_argument("--java8", type=Path, required=True)
    launch.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "pin-target":
        pin_target(args.profile, args.server_seed, args.output)
        return 0
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
