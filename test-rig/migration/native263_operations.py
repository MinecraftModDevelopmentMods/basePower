"""Run the unchanged 132 finite-job oracles against explicit Forge66 storage."""

import argparse
from pathlib import Path
import traceback
from zipfile import ZipFile

import nbtlib

from matrix import WORLD, digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_entity_storage import _decompress, _nbt
from native117_machine_operations import validate_operation as frozen_validate_operation
import native117_machine_saved as saved
import native12111_operations as previous
import native263 as native
from native263_layout import DATA_VERSION, region_file
from native263_block_states import palette_state
from transformer_order import read_region


def validate_operation(catalog, document, group):
    result = frozen_validate_operation(catalog, document, group)
    if group == "all" and result["cases"] != 132:
        raise AssertionError("Native263 full operations requires all 132 finite jobs")
    return result


def modern_chunk(payload):
    if len(payload) < 6 or int.from_bytes(payload[:4], "big") != len(payload) - 4:
        raise ValueError("Invalid native terrain payload length")
    root = _nbt(_decompress(payload[5:], payload[4]))
    if type(root.get("DataVersion")) is not nbtlib.Int or int(root["DataVersion"]) != DATA_VERSION or "Level" in root:
        raise ValueError("Machine save must be native 26.3 terrain")
    if (type(root.get("xPos")) is not nbtlib.Int or type(root.get("zPos")) is not nbtlib.Int
            or not isinstance(root.get("sections"), nbtlib.List)
            or not isinstance(root.get("block_entities"), nbtlib.List)):
        raise ValueError("Missing typed native chunk fields")
    sections = []
    for section in root["sections"]:
        view = nbtlib.Compound({"Y": section["Y"]})
        if "block_states" in section:
            states = section["block_states"]
            view["Palette"] = nbtlib.List[nbtlib.Compound](
                nbtlib.Compound({"Name": nbtlib.String(value["Name"]),
                                 "Properties": nbtlib.Compound({key: nbtlib.String(item)
                                     for key, item in value["Properties"].items()})})
                for value in (palette_state(entry.unpack()) for entry in states["palette"]))
            if "data" in states:
                view["BlockStates"] = states["data"]
        sections.append(view)
    return nbtlib.Compound({"xPos": root["xPos"], "zPos": root["zPos"],
                           "Sections": nbtlib.List[nbtlib.Compound](sections), "TileEntities": root["block_entities"]})


def audit_saved(runtime, fixture, evidence):
    """Same typed state/NBT comparator, new explicit dimension storage; retain raw payloads."""
    runtime, evidence = Path(runtime).resolve(), Path(evidence).resolve()
    world = runtime / WORLD
    require_disjoint(evidence, world)
    evidence.mkdir(parents=True, exist_ok=False)
    report = dict(status="FAIL", cases=len(fixture["cases"]), files={}, failures=[],
                  scope="Stopped Forge66 terrain state and complete typed machine NBT; no source writes")
    write_json(evidence / "snapshot.json", fixture)
    regions, chunks = {}, {}
    try:
        for case in fixture["cases"]:
            dimension = case.get("dimension", "minecraft:overworld")
            grouped = {}
            for row in case["positions"]:
                grouped.setdefault((row["x"] // 16, row["z"] // 16), []).append(row)
            for (cx, cz), rows in grouped.items():
                path = region_file(world, dimension, cx, cz)
                if path not in regions:
                    report["files"][str(path)] = digest(path)
                    regions[path] = read_region(path)[1]
                key = dimension, cx, cz
                if key not in chunks:
                    payload = regions[path][(cx & 31) + (cz & 31) * 32]
                    with (evidence / f"chunk-{len(chunks):05}.bin").open("xb") as output:
                        output.write(payload)
                    chunks[key] = modern_chunk(payload)
                    if (int(chunks[key]["xPos"]), int(chunks[key]["zPos"])) != (cx, cz):
                        raise AssertionError("Saved chunk coordinate differs from its region slot")
                differences = saved.compare_chunk(rows, chunks[key])
                if differences:
                    report["failures"].append(dict(id=case["id"], dimension=dimension, differences=differences))
        if any(digest(Path(path)) != sha for path, sha in report["files"].items()):
            raise AssertionError("Terrain changed during stopped audit")
        report["status"] = "FAIL" if report["failures"] else "PASS"
    except BaseException as failure:
        report["error"] = repr(failure)
        raise
    finally:
        write_json(evidence / "receipt.json", report)
    return report


def native_load_sources():
    sources = native.RIG.parent / f".gradle/mavenizer/repo/net/minecraftforge/forge/{native.FORGE_VERSION}/forge-{native.FORGE_VERSION}-sources.jar"
    expected = {"net/minecraft/world/entity/Entity.java":
                "E53CA0AA2777622317A05540183B66FFC4EA47B634D46B57C82AA05C88F2FF66",
                "net/minecraft/world/entity/LivingEntity.java":
                "778CBCA7C4DBC1C0D7AF01170F5B8022D46E0A6D1156AE96AFA1B941FAC58944"}
    # Both load methods are source-bound before reusing the existing exact expected-only
    # clamp/rebase rules. Observed full NBT and the frozen equality oracle are unchanged.
    import hashlib
    with ZipFile(sources) as jar:
        for path, sha in expected.items():
            if hashlib.sha256(jar.read(path)).hexdigest().upper() != sha:
                raise AssertionError("Review selected native actor load source: " + path)
    return expected


def validate_restart(fixture, inspected):
    source_hashes = native_load_sources()
    result = previous.validate_restart(fixture, inspected)
    result["comparison"] = result["comparison"].replace("1.21.11 explosive", "26.3 source-verified explosive")
    result["nativeLoadSourceSha256"] = source_hashes
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "java", "output"):
        parser.add_argument("--" + name, required=True, type=lambda value: Path(value).resolve())
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--group", choices=("all", "pickup", "power", "steam", "electric"), default="all")
    args = parser.parse_args()
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, version = native.java25(args.java)
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
    receipt = runtime / "native263-machine-operation-results.json"
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
