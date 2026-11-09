"""Replay every station checkpoint from a completed 1.19.4 endurance run."""

import argparse
from collections import Counter
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import endurance
from matrix import digest, write_json
from native117 import require_disjoint
from native117_broad import read_json
import native119


def replay(run, profile, specification):
    report = read_json(run / "native119-endurance-results.json")
    modes = ("fresh119", "retained-upgrade-native119")
    require = endurance.require
    require(report["status"] == "PASS" and report["minecraft"] == native119.MINECRAFT
            and report["duration_seconds"] == 1800 and report["interval_seconds"] == 60
            and report["retained_unchanged"] is True and set(report["profiles"]) == set(modes),
            "Expected a complete fresh and migrated 30-minute run")
    require(digest(profile) == report["profileSha256"] and digest(specification) == report["stationDefinitionSha256"],
            "The profile or station definition differs from the run")
    require(report["checked_stations"] == endurance.CHECKED, "Wrong station coverage")
    definitions = {row["id"]: row for row in read_json(specification)["stations"]}
    pins = read_json(profile)["mods"]
    expected_jars = {row["file"]: row["sha256"].upper() for row in pins}
    mod_versions = {row["id"]: row["version"] for row in pins}
    fresh = report["fresh_fixture_proof"]
    require(fresh["existing_world_absent"] is True and fresh["critical_block"]["actualName"] == "minecraft:air",
            "Missing pre-construction fresh-world evidence")
    for name, expected in fresh["files"].items():
        require(digest(run / modes[0] / name) == expected, "Fresh-world evidence changed: " + name)
    require(read_json(run / modes[1] / "retained-comparison.json")["status"] == "PASS",
            "Migrated world was not checked before station construction")

    labels = [f"cycle-{i:03d}" for i in range(1, 31)] + [
        "before-restart", "after-restart", "restart-resumed"]
    results = {}
    for mode in modes:
        runtime = run / mode
        station_path = runtime / "advantage-works-results"
        native119.verify_runtime(runtime)
        require({jar.name: digest(jar) for jar in (runtime / "mods").glob("*.jar")} == expected_jars,
                mode + ": staged artifacts differ from the approved profile")
        for boot in ("endurance", "endurance-restart"):
            native119.require_native_boot(runtime / f"console-{boot}.log")
        rig = endurance.Rig(mode, runtime, None, None, definitions)

        def snapshot(station, label):
            document = read_json(station_path / f"{station}-checkpoint-{label}.json")
            require(document["minecraft"] == native119.MINECRAFT and document["forge"] == native119.FORGE
                    and all(document["mods"].get(key) == value for key, value in mod_versions.items()),
                    mode + ": checkpoint runtime differs from profile")
            return document

        setup = snapshot("E-02", "setup")
        rig.static_batteries = endurance.parse_snapshot(setup)[rig.pos("E-02", (3, 1, 12))]["data"]["Items"]
        ledger = read_json(runtime / "endurance-ledger.json")
        require([row["label"] for row in ledger] == labels, mode + ": missing or misordered checkpoints")
        times = {}
        totals = Counter()
        for row in ledger:
            label = row["label"]
            for station in endurance.CHECKED:
                document = snapshot(station, label)
                rig.documents[station], rig.snapshots[station] = document, endurance.parse_snapshot(document)
            with patch.object(endurance, "write_json"):
                verified = rig.batch(label, power="basepower")
            require(verified == row, mode + "/" + label + ": raw result differs from the reported ledger")
            times[label] = datetime.fromisoformat(rig.documents["W-01"]["recordedAtUtc"])
            if label.startswith("cycle-"):
                totals.update(verified["outputs"])
        setup_time = snapshot("W-01", "setup")["worldTime"]
        ticks = ledger[29]["world_time"] - setup_time
        require(ticks == report["profiles"][mode]["game_ticks"],
                mode + ": reported ticks differ from the setup-to-end checkpoints")
        require(all(before < after for before, after in zip(times.values(), list(times.values())[1:])),
                mode + ": nonmonotonic checkpoint times")
        sampled_seconds = (times["cycle-030"] - times["cycle-001"]).total_seconds()
        sampled_ticks = ledger[29]["world_time"] - ledger[0]["world_time"]
        require(report["profiles"][mode]["elapsed_seconds"] >= 1800
                and sampled_seconds >= 1735,
                mode + ": checkpoints do not span the measured endurance period")
        require(sampled_ticks >= 18 * sampled_seconds, mode + ": less than 18 measured game ticks per second")
        require((times["restart-resumed"] - times["after-restart"]).total_seconds() >= 60,
                mode + ": missing minute of resumed operation")
        require(dict(totals) == report["profiles"][mode]["endurance_outputs"], mode + ": output totals differ")
        results[mode] = dict(checkpoints=len(ledger), stationSnapshots=len(ledger) * len(endurance.CHECKED),
                             setupToEndTicks=ticks, sampledTicks=sampled_ticks, sampledSeconds=sampled_seconds,
                             sampledTicksPerSecond=sampled_ticks / sampled_seconds, outputs=dict(totals))
    return dict(status="PASS", inputReceiptSha256=digest(run / "native119-endurance-results.json"), profiles=results)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("run", "profile", "specification", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    for path in (args.run, args.profile, args.specification):
        require_disjoint(args.output, path)
    if args.output.exists():
        raise FileExistsError(args.output)
    write_json(args.output, replay(args.run, args.profile, args.specification))
    print("PASS: both 30-minute ledgers replayed from native station snapshots")


if __name__ == "__main__":
    main()
