"""Reconstruct every endurance verdict from retained raw station checkpoints."""

import argparse
from pathlib import Path
from unittest.mock import patch

import endurance
import native117_endurance
import semantic_machine_audit
from matrix import digest, fingerprint, write_json
from native117_endurance import audit
from native117_lifecycle import read_json


def retained_protocol(root, hashes):
    retained = {}
    for name, expected in hashes.items():
        basename = Path(name).name
        if basename in retained:
            raise AssertionError("Ambiguous archived protocol basename: " + basename)
        path = root / "protocol-sources" / basename
        if path.is_symlink() or not path.is_file() or digest(path) != expected:
            raise AssertionError("Archived protocol source changed: " + basename)
        retained[basename] = path
    actual = {path.name for path in (root / "protocol-sources").iterdir()}
    if actual != set(retained):
        raise AssertionError("Archived protocol inventory differs")
    return retained


def replay(root):
    result = audit(root)
    report = read_json(root / "native117-endurance-results.json")
    if fingerprint(Path(report["source"])) != report["source_fingerprint"]:
        raise AssertionError("Protected source changed after the run")
    retained = retained_protocol(root, report["helper_sha256"])
    # Replayed verdict code must still be the recorded code. The command adapter
    # is archived provenance only: a read-only replay never sends commands.
    for module in (endurance, native117_endurance, semantic_machine_audit):
        path = Path(module.__file__)
        if path.name not in retained or digest(path) != digest(retained[path.name]):
            raise AssertionError("Replayed oracle differs from recorded code: " + path.name)
    definitions_path = retained["works-1.13.2.json"]
    definitions = {station["id"]: station for station in read_json(definitions_path)["stations"]}
    for name in result["profiles"]:
        runtime = root / name
        rig = endurance.Rig(name, runtime, None, None, definitions)
        setup = endurance.parse_snapshot(read_json(runtime / "advantage-works-results/E-02-checkpoint-setup.json"))
        battery_pos = rig.pos("E-02", (3, 1, 12))
        rig.static_batteries = setup[battery_pos]["data"].get("Items", [])
        ledger = read_json(runtime / "endurance-ledger.json")
        for expected in ledger:
            label = expected["label"]
            for station in endurance.CHECKED:
                document = read_json(runtime / "advantage-works-results" / f"{station}-checkpoint-{label}.json")
                rig.documents[station] = document
                rig.snapshots[station] = endurance.parse_snapshot(document)
            # Suppress the oracle's ledger writer only; never change its checks.
            with patch.object(endurance, "write_json"):
                actual = rig.batch(label)
            if actual != expected:
                raise AssertionError(name + "/" + label + ": raw checkpoint verdict differs from ledger")
        result["profiles"][name]["raw_checkpoint_replays"] = len(ledger)
        result["profiles"][name]["station_snapshots_replayed"] = len(ledger) * len(endurance.CHECKED)
    result["source_fingerprint_rechecked"] = True
    result["protocol_hashes_rechecked"] = True
    result["protocol_source"] = "Immutable archived copies; live replay-oracle bytes also match"
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    result = replay(args.run.resolve())
    write_json(args.output, result)
    print("PASS: raw endurance checkpoints, inventory acknowledgements, artifact pins and protected sources", flush=True)
