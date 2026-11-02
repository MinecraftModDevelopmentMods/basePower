"""Original released Cyano 1.10 -> renamed 1.10, on copies and strict diagnostics.

No historical diagnostic permit is imported. New warnings/errors need new proof.
Only expected identity fields are translated; raw fixtures and snapshots stay intact.
"""
import argparse
import base64
import copy
from datetime import datetime, timezone
import gzip
import io
from pathlib import Path
import shutil
import socket
import traceback

import nbtlib

import legacy_connected_oracle as oracle
import semantic_machine_audit
from native110 import output_path, pinned_profile, require_disjoint
from native110_code import INVENTORY, verify_code
from native110_connected import NativeServer
from native110_lifecycle import read_json, verify_staged
from rig_transport import WORLD, digest, fingerprint, write_json

NAMESPACES = {"poweradvantage": "basepower", "steamadvantage": "basesteam", "electricadvantage": "baseelectric"}
TARGET_IDS = {"basepower", "basesteam", "baseelectric", "basemetals", "orespawn", "advantageworkstest"}
ORIGINAL_JARS = {
    "PowerAdvantage_1.10.2-2.3.0.jar": "47CD509C1141095BFE1C8C7DD689C2ECD3F99C89195E34A88CC9E0B5FC12413E",
    "SteamAdvantage_1.10.2-2.2.0.jar": "34A0C46C7B36DEF313F35720BD788539BDEECAD3C30880D30D82BC84E35AD9A5",
    "ElectricAdvantage_1.10.2-2.2.0.jar": "61AE5343A79F518F54F1717D508EA3F6DA7209EE69B0C4433E0B7A9722213EE0",
    "BaseMetals_1.10.2-2.4.0.11.jar": "A225D3E24CD37C377A9D843861FC13BDDB0B88F799D3D3AEDBC8B1DC636BB5E5",
    "OreSpawn_1.10.2-1.1.0.jar": "91345E4B825AA06E05F470066F467AABA0C7A2804A8DBCA4D3CD1905ED53607C",
    "AdvantageMigrationProbe-110-1.0.0.jar": "B9F756681593B508A8E3222BD592A3DFCB474303532B154045893B3C585EF1D3",
}
TANKS = {"Tank", "InputTank", "TankIn", "TankOut", "FuelTank", "WaterTank"}


def mapped_id(value, namespaces):
    domain, separator, path = value.partition(":")
    return namespaces.get(domain, domain) + separator + path if separator else value


def target_id(value):
    return mapped_id(value, NAMESPACES)


def tile_alias(value):
    if value == "Chest": return "minecraft:chest"
    for namespace in (*NAMESPACES, *NAMESPACES.values()):
        for prefix in (namespace + ".", "minecraft:" + namespace + "."):
            if value.startswith(prefix): return namespace + ":" + value[len(prefix):]
    return value


def binary_nbt(row):
    return nbtlib.File.parse(io.BytesIO(gzip.decompress(base64.b64decode(row["nbtBase64"], validate=True))))


def encode_nbt(tag):
    output = io.BytesIO()
    tag.write(output)
    return base64.b64encode(gzip.compress(output.getvalue())).decode("ascii")


def identity_nbt(tag, namespaces):
    """Translate tile IDs, inventory stack IDs and named tank fluids, never free text."""
    result = copy.deepcopy(tag)
    if "id" in result and isinstance(result["id"], nbtlib.String):
        result["id"] = nbtlib.String(mapped_id(tile_alias(str(result["id"])), namespaces))

    def contents(value):
        if not isinstance(value, nbtlib.Compound): return
        for key, child in value.items():
            if key in ("Items", "Inventory") and isinstance(child, nbtlib.List):
                for stack in child:
                    if isinstance(stack, nbtlib.Compound) and "Count" in stack and isinstance(stack.get("id"), nbtlib.String):
                        stack["id"] = nbtlib.String(mapped_id(str(stack["id"]), namespaces))
            if key in TANKS and isinstance(child, nbtlib.Compound) and isinstance(child.get("FluidName"), nbtlib.String):
                child["FluidName"] = nbtlib.String(mapped_id(str(child["FluidName"]), namespaces))
            if isinstance(child, nbtlib.Compound): contents(child)
            elif isinstance(child, nbtlib.List):
                for entry in child: contents(entry)
    contents(result)
    return result


def preservation(before, after):
    def rows(document, expected):
        result = {}
        for row in document["snapshot"]:
            if row["id"] in result: raise AssertionError("Duplicate connected witness")
            value = {key: row[key] for key in ("station", "kind", "name", "x", "y", "z", "actualName", "properties")}
            if expected: value["actualName"] = target_id(value["actualName"])
            if "nbt" in row and "nbtBase64" not in row: raise AssertionError("Missing typed native NBT")
            if "nbtBase64" in row:
                tag = identity_nbt(binary_nbt(row), NAMESPACES if expected else {})
                if expected and row["actualName"] == "steamadvantage:steam_drill" and "Facing" not in tag:
                    facings = ("down", "up", "north", "south", "west", "east")
                    tag["Facing"] = nbtlib.Byte(facings.index(row["properties"]["facing"]))
                value["nbt"] = oracle.saved_tile_nbt(tag)
                for field in ("ForgeData", "ForgeCaps"):
                    if value["nbt"].get(field) == {}: value["nbt"].pop(field)
            result[row["id"]] = value
        return result
    left, right = rows(before, True), rows(after, False)
    changed = sorted(key for key in left.keys() | right.keys() if left.get(key) != right.get(key))
    return dict(status="FAIL" if changed else "PASS", changed=changed,
        comparison="Exact witnesses, block states and typed NBT; only expected namespace fields and documented legacy serializer aliases")


def require_passive(document):
    if (document.get("held") is not True or type(document.get("buildCallsThisProcess")) is not int
            or document["buildCallsThisProcess"] != 0 or type(document.get("advancedTicks")) is not int
            or document["advancedTicks"] != 0 or type(document.get("requestedTicks")) is not int
            or document["requestedTicks"] != 0):
        raise AssertionError("Loaded comparison requires zero ticks, zero construction and held machines")


def operation_view(document):
    # Only an in-memory comparison view for the unchanged finite-work oracles.
    result = copy.deepcopy(document)
    reverse = {new: old for old, new in NAMESPACES.items()}
    for row in result["snapshot"]:
        row["actualName"] = mapped_id(row["actualName"], reverse)
        if "nbtBase64" in row: row["nbtBase64"] = encode_nbt(identity_nbt(binary_nbt(row), reverse))
    return result


def sealed_json(path, checksum):
    path = Path(path).resolve()
    if digest(path) != checksum.upper(): raise AssertionError("Changed sealed input: " + str(path))
    return read_json(path)


def bind_source(report_path, checksum):
    history = sealed_json(report_path, checksum)
    if history.get("sourceKey") != "original110" or history.get("status") != "PASS":
        raise AssertionError("Only completed original released Cyano 1.10 provenance is eligible")
    source = Path(history["source"]).resolve()
    manifest = sealed_json(history["sourceManifest"], history["sourceManifestSha256"])
    pins = {Path(j["path"]).name: j["sha256"].upper() for j in manifest["original110"]["jars"]}
    actual = {p.name: digest(p) for p in (source / "mods").glob("*.jar")}
    if pins != ORIGINAL_JARS or actual != pins: raise AssertionError("Not the exact original release mod inventory")
    for jar in manifest["original110"]["jars"]:
        if digest((Path(history["sourceManifest"]).parent / jar["path"]).resolve()) != jar["sha256"].upper():
            raise AssertionError("Archived original input artifact changed")
    hashes = sealed_json(history["sourceHashes"], history["sourceHashesSha256"])
    if fingerprint(source / WORLD) != hashes: raise AssertionError("Protected original world changed")
    if (not history.get("sourceRuntimeBefore") or history["sourceRuntimeBefore"] != history.get("sourceRuntimeAfter")
            or fingerprint(source) != history["sourceRuntimeBefore"]):
        raise AssertionError("Original fixture definition, configuration or runtime custody changed")
    checkpoint = source / ("connected-" + history["sourceCheckpoint"] + ".json")
    sealed_json(checkpoint, history["sourceCheckpointSha256"])
    return source, history


def bind_target(manifest_path):
    target = pinned_profile(manifest_path, "target110")
    pins = read_json(manifest_path)["target110"]
    jars = pins["jars"]
    if len(jars) != len(TARGET_IDS) or {j["id"] for j in jars} != TARGET_IDS:
        raise AssertionError("Target must contain exactly basePower/baseSteam/baseElectric plus the three pinned support mods")
    base = next(j for j in jars if j["id"] == "basemetals")
    if base["sha256"].upper() != ORIGINAL_JARS["BaseMetals_1.10.2-2.4.0.11.jar"]:
        raise AssertionError("This route upgrades Advantage, not the source Base Metals registry")
    verify_code(target[0], pins["serverCode"])
    return target, pins


def run(args):
    output = output_path(args.output)
    source, history = bind_source(args.source_report, args.source_report_sha256)
    target, pins = bind_target(args.target_manifest)
    probe = next(j for j in read_json(Path(history["sourceManifest"]))["original110"]["jars"]
                 if Path(j["path"]).name == "AdvantageMigrationProbe-110-1.0.0.jar")
    probe_path = (Path(history["sourceManifest"]).parent / probe["path"]).resolve()
    profile = target[:2] + (target[2] + [probe_path],)
    for protected in (source, args.source_report, args.target_manifest, target[0], probe_path, *target[2]):
        require_disjoint(output, protected)
    with socket.socket() as listener: listener.bind(("127.0.0.1", args.port))
    source_before = fingerprint(source)
    helpers = [Path(__file__), Path(oracle.__file__), Path(semantic_machine_audit.__file__),
        *[Path(__file__).with_name(name) for name in ("native110.py", "native110_code.py", "native110_connected.py",
           "native110_lifecycle.py", "native110_diagnostics.py", "rig_transport.py")]]
    report = dict(status="RUNNING", sourceKey="original110", sourceReport=str(args.source_report.resolve()),
        sourceReportSha256=digest(args.source_report), targetManifest=str(args.target_manifest.resolve()),
        targetManifestSha256=digest(args.target_manifest), sourceBefore=source_before, phases={},
        startedAtUtc=datetime.now(timezone.utc).isoformat(), protocolSha256={str(p): digest(p) for p in helpers},
        scope="Original Cyano 1.10 to renamed 1.10 only; two 2048-tick intervals and full process restart; strict diagnostics, no legacy bucket permit")
    receipt = output / "native110-rebrand-connected-results.json"
    server = None
    try:
        launcher = oracle.prepare(output, profile, None, args.port, source)
        for path in helpers:
            destination = output / "protocol-sources" / path.name
            destination.parent.mkdir(exist_ok=True)
            shutil.copy2(path, destination)
        verify_code(output, pins["serverCode"])
        write_json(output / INVENTORY, pins["serverCode"])
        write_json(receipt, report)
        if (output / "connected-build.allow").exists(): raise AssertionError("Destination construction authority leaked")
        previous = oracle.read(source, history["sourceCheckpoint"])
        definition = read_json(output / "connected-fixture.json")
        if oracle.state(previous)["status"] != "PASS": raise AssertionError("Invalid original finite stocks")
        for phase in ("converted", "restarted"):
            bind_source(args.source_report, args.source_report_sha256)
            if digest(args.target_manifest) != report["targetManifestSha256"]: raise AssertionError("Target manifest changed")
            verify_staged(output, profile)
            verify_code(output, pins["serverCode"])
            server = NativeServer(output, args.java8, launcher, phase)
            server.observe("snapshot", phase + "-loaded")
            loaded = oracle.read(output, phase + "-loaded")
            require_passive(loaded)
            preserved = preservation(previous, loaded)
            report["phases"][phase + "-preservation"] = preserved
            write_json(receipt, report)
            if preserved["status"] != "PASS": raise AssertionError("Unticked imported state changed: " + str(preserved))
            server.observe("step", phase + "-worked", 2048)
            worked = oracle.read(output, phase + "-worked")
            before_view, after_view = operation_view(loaded), operation_view(worked)
            operation = oracle.operation(before_view, after_view, 2048)
            completed = semantic_machine_audit.connected_interval(before_view, after_view, definition, 2048)
            report["phases"][phase + "-operation"] = operation
            report["phases"][phase + "-completed-work"] = completed
            if operation["status"] != "PASS" or completed["status"] != "PASS": raise AssertionError("Imported machines failed finite paid work")
            server.send("save-all flush")
            server.wait("Saved the world", 120)
            server.close(); server = None
            verify_staged(output, profile)
            if phase == "converted": shutil.copytree(output / WORLD, output / "pre-restart-save")
            previous = worked
            write_json(receipt, report)
        report["status"] = "PASS"
    except BaseException as error:
        report.update(status="FAIL", failure=str(error), traceback=traceback.format_exc())
    finally:
        if server:
            try: server.close()
            except BaseException as error: report.update(status="FAIL", shutdownError=str(error))
        report["sourceUnchanged"] = fingerprint(source) == source_before
        report["protocolUnchanged"] = report["protocolSha256"] == {str(p): digest(p) for p in helpers}
        report["manifestUnchanged"] = digest(args.target_manifest) == report["targetManifestSha256"]
        try:
            bind_source(args.source_report, args.source_report_sha256)
            report["sourceBindingUnchanged"] = True
        except BaseException as error:
            report.update(status="FAIL", sourceBindingUnchanged=False, sourceBindingError=str(error))
        if not all(report[k] for k in ("sourceUnchanged", "protocolUnchanged", "manifestUnchanged")): report["status"] = "FAIL"
        report["completedAtUtc"] = datetime.now(timezone.utc).isoformat()
        output.mkdir(parents=True, exist_ok=True)
        report["rawSha256"] = {str(p.relative_to(output)): digest(p) for p in output.rglob("*") if p.is_file() and p != receipt}
        write_json(receipt, report)
    return int(report["status"] != "PASS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-report", type=Path, required=True)
    parser.add_argument("--source-report-sha256", required=True)
    parser.add_argument("--target-manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java8", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    return run(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
