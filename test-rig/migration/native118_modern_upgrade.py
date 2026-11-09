"""Load and operate a stopped, already-qualified 1.17.1 save on 1.18.2."""

import argparse
from pathlib import Path
import shutil

from matrix import WORLD, compare, digest, fingerprint, parse_nbt, write_json
from native117 import native_normalized, output_path, require_disjoint
from native117_legacy_world import turbine
from native117_lifecycle import read_json
import native118
from native118_legacy_world import operate
import native118_saved


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "profile", "seed", "inventory", "output", "java17"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--receipt-sha256", required=True)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    source = args.source.resolve()
    prior = source / "native117-legacy-world-results.json"
    if digest(prior) != args.receipt_sha256.upper():
        raise AssertionError("The protected 1.17.1 receipt changed")
    receipt = read_json(prior)
    if receipt["status"] != "PASS" or not receipt["sourceUnchanged"]:
        raise AssertionError("Source lacks a passing independent migration receipt")
    historical = source / "migration-restarted.json"
    phase = receipt["phases"]["restarted"]
    if phase["status"] != "PASS" or digest(historical) != phase["snapshotSha256"]:
        raise AssertionError("Source lacks its passing restart snapshot")
    historical_rows = read_json(historical)
    expected = {"witnesses": {}}
    for row in historical_rows:
        expected["witnesses"][row["name"]] = {"items": [
            {"item": stack["id"], "count": int(stack["Count"])}
            for stack in (parse_nbt(row).get("Items", ()) if row.get("nbtBase64") else ())]}
    before = fingerprint(source)
    positions = {row["name"]: [row["x"], row["y"], row["z"]]
                 for row in read_json(source / "migration-fixture.json")}
    target = native118.read_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    output = output_path(args.output)
    for path in (source, args.profile, args.seed, args.inventory,
                 *(item["path"] for item in target[2].values())):
        require_disjoint(output, path)
    launcher = native118.stage(output, target, args.port)
    for path in (WORLD, "config"):
        shutil.copytree(source / path, output / path)
    if fingerprint(source / WORLD) != fingerprint(output / WORLD):
        raise AssertionError("The staged 1.17.1 save differs from the source")
    shutil.copy2(source / "migration-fixture.json", output / "migration-fixture.json")
    (output / "advantage-migration.allow").touch()
    result = dict(status="RUNNING", minecraft=native118.MINECRAFT, forge=native118.FORGE,
                  source=str(source), sourceReceiptSha256=digest(prior), sourceFingerprint=before,
                  profileSha256=digest(args.profile), inventorySha256=digest(args.inventory), phases={})
    result_path = output / "native118-modern-upgrade-results.json"
    try:
        for label in ("converted", "restarted"):
            server = native118.Server(output, args.java17, launcher, label)
            try:
                server.snapshot(label)
                server.send("save-all flush")
                server.wait("Saved the game", 30)
            finally:
                server.close()
            snapshot = output / f"migration-{label}.json"
            comparison = compare(historical, snapshot, native_normalized)
            result["phases"][label] = comparison
            if comparison["status"] != "PASS":
                raise AssertionError("The native 1.17.1 witnesses changed in " + label)
            saved = native118_saved.capture(output, label, read_json(snapshot))
            observed = turbine(output / WORLD, positions["hydroGenerator"],
                               data_version=native118_saved.DATA_VERSION)
            if observed != phase["turbine"]:
                raise AssertionError("The saved 1.17.1 turbine changed")
            result["phases"][label].update(serialized=saved, turbine=observed,
                                           snapshotSha256=digest(snapshot))
            write_json(result_path, result)
        result["phases"]["operation"] = operate(output / "operation", output, target, args.java17,
                                                 args.port, positions, expected)
        result["status"] = "PASS"
    except Exception as error:
        result.update(status="FAIL", failure=repr(error))
        raise
    finally:
        result["sourceUnchanged"] = fingerprint(source) == before
        if not result["sourceUnchanged"]:
            result["status"] = "FAIL"
        write_json(result_path, result)
    return int(result["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
