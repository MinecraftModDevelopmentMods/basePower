"""Compare real saved 1.12 workshop resources on both sides of the 1.13 flattening."""

import argparse
from pathlib import Path
import shutil
import traceback

from matrix import WORLD, compare, digest, fingerprint, normalized, write_json
from native113 import output_path, pinned_profile, require_disjoint
from native113_code import INVENTORY, Server, verify_code
from native113_lifecycle import prepare, read_json, require_native_boot, verify_staged
from native113_resource_audit import _nbt, _project, SLOT_LIMITS
from native113_rebrand import canonical


CASES = frozenset((
    "power/still/production", "power/still/invalid", "steam/geothermal-production",
    "steam/oil-production", "steam/pump-full-output", "steam/still-one-output-space",
    "electric/refinery", "electric/growth-chain", "electric/growth-scarcity-full",
    "electric/lifecycle/battery-mixed-charge", "electric/turret-owner-credit",
))
STATE = ("growth", "prog", "progress", "BurnTimeTotal", "team", "player")
LIMITS = {canonical(name): count for name, count in SLOT_LIMITS.items()}


def legacy_values(value):
    if isinstance(value, dict):
        return {key: legacy_values(child) for key, child in value.items()}
    if isinstance(value, list):
        return [legacy_values(child) for child in value]
    if isinstance(value, str):
        if value == "minecraft:reeds":
            return "minecraft:sugar_cane"
        if value == "baseelectric:l_e_d":
            return "baseelectric:led"
        return canonical(value)
    return value


def compare_witnesses(expected, actual):
    if len(expected) != len(actual):
        raise AssertionError("Native workshop witness count changed")
    differences = []
    for before, after in zip(expected, actual):
        name, block = before["name"], before["block"]
        if after["name"] != name or after["actualName"] != block:
            differences.append(dict(name=name, field="block", expected=block, actual=after["actualName"]))
            continue
        saved = legacy_values(normalized(_nbt(before)))
        if "nbtBase64" not in after:
            differences.append(dict(name=name, field="tile", expected=saved["id"], actual=None))
            continue
        live = normalized(_nbt(after))
        for key in ("id", *STATE):
            if key in saved and saved[key] != live.get(key):
                differences.append(dict(name=name, field=key, expected=saved[key], actual=live.get(key)))
        resources = _project(saved, block, LIMITS)
        live_resources = _project(live, block, LIMITS)
        if resources != live_resources:
            differences.append(dict(name=name, field="resources", expected=resources, actual=live_resources))
        if "facing=" in before["state"]:
            facing = before["state"].split("facing=", 1)[1].split(",", 1)[0].rstrip("]")
            if after["properties"].get("facing") != facing:
                differences.append(dict(name=name, field="facing", expected=facing, actual=after["properties"]))
    return dict(status="FAIL" if differences else "PASS", positions=len(expected), differences=differences)


def run(args):
    source = args.source.resolve()
    manifest = read_json(args.target_manifest)["target113"]
    target = pinned_profile(args.target_manifest, "target113")
    runtime = output_path(args.output)
    for protected in (source, args.source_release, args.target_manifest, target[0], *target[2]):
        require_disjoint(runtime, protected)
    released = read_json(args.source_release)["releases"]
    for entry in released:
        jar = source / "mods" / (entry["name"] + "-" + entry["version"] + ".jar")
        if digest(jar) != entry["artifact"]["sha256"]:
            raise AssertionError("Wrong released 1.12 source jar: " + str(jar))
    previous = read_json(source / "native112-machine-operation-results.json")
    if previous["status"] != "PASS" or previous["phases"]["restart"]["status"] != "PASS":
        raise AssertionError("The source workshop lacks passing 1.12 cold-restart evidence")
    evidence = source / "advantage-works-results/machine-inspection-restarted.json"
    document = read_json(evidence)
    if not document["held"] or document["constructionCallsThisProcess"] != 0:
        raise AssertionError("Source state was not a read-only restart")
    chosen = {row["id"]: row for row in document["cases"] if row["id"] in CASES}
    if chosen.keys() != CASES:
        raise AssertionError("Required source case absent")
    rows = [dict(row, name=f"{case}/{index}") for case, result in chosen.items()
            for index, row in enumerate(result["positions"])
            if row["block"].startswith(("basepower:", "basesteam:", "baseelectric:")) and "nbt" in row]
    if len(rows) < 25 or len({(r["x"], r["y"], r["z"]) for r in rows}) != len(rows):
        raise AssertionError("Not enough distinct saved machine witnesses")
    source_hashes = fingerprint(source / WORLD)
    source_config = fingerprint(source / "config")
    verify_code(target[0], manifest["serverCode"])
    launcher = prepare(runtime, target, [], args.port)
    verify_code(runtime, manifest["serverCode"])
    write_json(runtime / INVENTORY, manifest["serverCode"])
    shutil.copytree(source / WORLD, runtime / WORLD)
    shutil.copytree(source / "config", runtime / "config")
    props = runtime / "server.properties"
    props.write_text("\n".join(line for line in props.read_text().splitlines()
                               if not line.startswith("generator-settings=")) + "\n", encoding="ascii")
    write_json(runtime / "migration-fixture.json", [
        {key: row[key] for key in ("name", "x", "y", "z")} for row in rows])
    report = dict(status="RUNNING", source=str(source), sourceWorldHashes=source_hashes,
                  sourceSnapshotSha256=digest(evidence), targetManifestSha256=digest(args.target_manifest),
                  protocolSha256=digest(Path(__file__)), phases={})
    output = runtime / "native113-machine-upgrade-results.json"
    write_json(output, report)
    try:
        for phase in ("converted", "restarted"):
            server = Server(runtime, args.java8, launcher, phase)
            try:
                server.snapshot(phase)
                server.send("save-all flush")
                server.wait("Saved the game", 60)
            finally:
                server.close()
            require_native_boot(runtime / f"console-{phase}.log")
            verify_staged(runtime, target)
            report["phases"][phase] = compare_witnesses(rows, read_json(runtime / f"migration-{phase}.json"))
            write_json(output, report)
        report["phases"]["idempotence"] = compare(runtime / "migration-converted.json",
                                                 runtime / "migration-restarted.json")
        report["status"] = "PASS" if all(phase["status"] == "PASS" for phase in report["phases"].values()) else "FAIL"
    except Exception as error:
        report.update(status="FAIL", failure=str(error), traceback=traceback.format_exc())
    finally:
        report["sourceUnchanged"] = (fingerprint(source / WORLD) == source_hashes
                                     and fingerprint(source / "config") == source_config)
        if not report["sourceUnchanged"]:
            report["status"] = "FAIL"
        write_json(output, report)
    print(report["status"], output, flush=True)
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("source", "source-release", "target-manifest", "output", "java8"):
        parser.add_argument("--" + option, type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    raise SystemExit(run(parser.parse_args()))
