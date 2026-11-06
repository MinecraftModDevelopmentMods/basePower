"""Check original-release block states and actors under both actual transformer orders."""

import argparse
from pathlib import Path
import shutil

import transformer_order as order
from matrix import WORLD, differences, digest, fingerprint, parse_nbt, write_json
from native113 import output_path, pinned_profile, require_disjoint
from native113_code import verify_code
from native113_lifecycle import prepare, read_json, require_native_boot, verify_staged
from native113_rebrand import canonical


def migrated_names(value, key=None):
    if isinstance(value, dict):
        result = {field: migrated_names(child, field) for field, child in value.items()}
        if "Count" in result and result.get("id") in ("basepower:crude_oil", "basepower:refined_oil"):
            # Historical 1.13 exposed the filled bucket under the fluid's path.
            result["id"] += "_bucket"
        return result
    if isinstance(value, list):
        return [migrated_names(child) for child in value]
    if isinstance(value, str) and key in ("id", "name", "FluidName", "FluidID"):
        return canonical(value)
    return value


def compare_exact(expected, actual):
    if expected == actual:
        return {"status": "PASS"}
    return {"status": "FAIL", "differences": differences(expected, actual),
            "reverseDifferences": differences(actual, expected)}


def check_turbine_id(runtime, phase, state):
    rows = read_json(runtime / f"actors-{phase}.json")["entities"]
    turbine = [row for row in rows if row["witness"] == "hydroturbine"]
    if len(turbine) != 1 or parse_nbt(turbine[0])["id"] != "baseelectric:hydroturbine":
        raise AssertionError("Wrong native turbine ID or duplicate turbine")
    # The historical actor reader normalizes this entity to the old name.
    state["entities"]["hydroturbine"]["id"] = "baseelectric:hydroturbine"


def backup_proof(source, runtime):
    uuid = read_json(source / "actor-fixture.json")["playerUuid"]
    proof = {}
    for relative in (Path("level.dat"), Path("playerdata") / f"{uuid}.dat"):
        original = digest(source / WORLD / relative)
        prefix = runtime / WORLD / relative
        backups = {item.name: digest(item) for item in prefix.parent.glob(prefix.name + ".*-legacy-backup")}
        if original not in backups.values():
            raise AssertionError(f"No unmodified backup retained for {relative}")
        proof[str(relative)] = {"original": original, "backups": backups}
    return proof


def run(args):
    output = output_path(args.output)
    require_disjoint(output, args.archive)
    require_disjoint(output, args.target_manifest)
    source = args.archive / "original110-mixed-input"
    reference = args.archive / "original110-natural"
    if read_json(args.archive / "summary.json")["semantic_status"] != "PASS":
        raise AssertionError("Archived comparison was not qualified")
    protected = {path: fingerprint(path) for path in (source, reference)}
    if fingerprint(source / WORLD) != read_json(source / "fixture-hashes.json"):
        raise AssertionError("Original mixed fixture no longer matches the archived hash")
    target = pinned_profile(args.target_manifest, "target113")
    inventory = read_json(args.target_manifest)["target113"]["serverCode"]
    verify_code(target[0], inventory)
    output.mkdir(parents=True)
    patched = order.build_bridge(output, target, args.java8)
    expected_code = {"files": dict(inventory["files"])}
    expected_code["files"][order.COREMODS.as_posix()] = {"sha256": digest(patched), "size": patched.stat().st_size}
    expected_blocks = migrated_names(order.block_semantics(reference, "converted"))
    if len(expected_blocks) != 358:
        raise AssertionError("Archive has the wrong block witness count")
    report = {"status": "RUNNING", "targetManifestSha256": digest(args.target_manifest),
              "archive": str(args.archive.resolve()), "originalWorldHashes": fingerprint(source / WORLD),
              "witnesses": len(expected_blocks), "orders": {}}
    report_path = output / "native113-rebrand-order-results.json"
    try:
        for mode in ("bm-first", "pa-first"):
            runtime = output / mode
            launcher = prepare(runtime, target, [], args.port, source)
            shutil.copy2(source / "actor-fixture.json", runtime / "actor-fixture.json")
            shutil.copy2(patched, runtime / order.COREMODS)
            (runtime / "transformer-order.mode").write_text(mode, encoding="ascii")
            verify_code(runtime, expected_code)
            report["orders"][mode] = phases = {}
            backups = None
            for phase in ("converted", "restarted"):
                first = phase == "converted"
                state, adapter, observations = order.checkpoint(runtime, args.java8, launcher, phase, first)
                require_native_boot(runtime / f"console-{phase}.log")
                verify_staged(runtime, target)
                verify_code(runtime, expected_code)
                state_expected = migrated_names(read_json(reference / f"semantic-{phase}.json"))
                adapter_phase = "converted-adapter" if first else "restarted-adapter"
                adapter_expected = migrated_names(read_json(reference / f"semantic-{adapter_phase}.json"))
                check_turbine_id(runtime, phase, state)
                check_turbine_id(runtime, adapter_phase if first else phase, adapter)
                current_backups = backup_proof(source, runtime)
                if backups is not None and current_backups != backups:
                    raise AssertionError("Original migration backups changed during restart")
                backups = current_backups
                gates = [compare_exact(expected_blocks, order.block_semantics(runtime, phase)),
                         compare_exact(state_expected, state), compare_exact(adapter_expected, adapter)]
                phases[phase] = dict(blocks=gates[0], actors=gates[1], adapter=gates[2],
                                     order=observations["order"], backups=backups)
                write_json(report_path, report)
                print(mode, phase, [gate["status"] for gate in gates], flush=True)
        report["status"] = "PASS" if all(
            item[name]["status"] == "PASS" for phases in report["orders"].values()
            for item in phases.values() for name in ("blocks", "actors", "adapter", "order")) else "FAIL"
    except Exception as failure:
        report.update(status="FAIL", failure=str(failure))
        raise
    finally:
        report["sourceUnchanged"] = all(fingerprint(path) == hashes for path, hashes in protected.items())
        if not report["sourceUnchanged"]:
            report["status"] = "FAIL"
        write_json(report_path, report)
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("archive", "target-manifest", "java8", "output"):
        parser.add_argument("--" + flag, type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    raise SystemExit(run(parser.parse_args()))
