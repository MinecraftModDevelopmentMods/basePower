"""Run the measured Works feed, conservation and cold-restart protocol on 1.20.1."""

import argparse
from collections import Counter
from pathlib import Path
import shutil
import time

import endurance
from matrix import WORLD, compare, digest, fingerprint, write_json
from native117 import native_normalized, output_path, require_disjoint
from native117_broad import read_json
from native117_endurance import NativeEnduranceRig, configure_new_fresh_fixture, prove_fresh_fixture_before_build
import native1201


class Rig(NativeEnduranceRig):
    server_type = native1201.Server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "output", "java17"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--seconds", type=int, default=1800)
    parser.add_argument("--retained", type=Path)
    parser.add_argument("--port", type=int, default=25658)
    args = parser.parse_args()
    endurance.require(args.seconds >= 60 and args.seconds % 60 == 0, "Use whole one-minute batches")
    root = output_path(args.output)
    target = native1201.read_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    for protected in (args.profile, args.seed, args.inventory, *(item["path"] for item in target[2].values())):
        require_disjoint(root, protected)
    source_before = None
    if args.retained:
        require_disjoint(root, args.retained)
        prior = read_json(args.retained / "native1201-legacy-world-results.json")
        if (prior["status"] != "PASS" or prior["profileSha256"] != digest(args.profile)
                or prior["inventorySha256"] != digest(args.inventory)
                or digest(args.retained / "migration-restarted.json") != prior["phases"]["restarted"]["snapshotSha256"]):
            raise AssertionError("Retained input does not match the passing pinned migration")
        source_before = fingerprint(args.retained / WORLD)
    spec = native1201.RIG / "src/main/resources/assets/advantageworkstest/works/works-1.13.2.json"
    definitions = {row["id"]: row for row in read_json(spec)["stations"]}
    report = dict(status="RUNNING", minecraft=native1201.MINECRAFT, duration_seconds=args.seconds,
                  interval_seconds=60, profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  stationDefinitionSha256=digest(spec), active_stations=endurance.ACTIVE,
                  checked_stations=endurance.CHECKED, profiles={}, retained=str(args.retained) if args.retained else None)
    receipt = root / "native1201-endurance-results.json"
    write_json(receipt, report)
    rigs = []
    try:
        cases = (("fresh1201", None), ("retained-upgrade-native1201", args.retained)) if args.retained else (("fresh1201", None),)
        for index, (name, source) in enumerate(cases):
            runtime = root / name
            launcher = native1201.stage(runtime, target, args.port + index)
            rig = Rig(name, runtime, args.java17, launcher, definitions)
            rigs.append(rig)
            if source:
                for path in (WORLD, "config"):
                    shutil.copytree(source / path, runtime / path)
                shutil.copy2(source / "migration-fixture.json", runtime / "migration-fixture.json")
                if fingerprint(runtime / WORLD) != source_before:
                    raise AssertionError("The retained fixture copy differs from the source")
                rig.boot("retained-before-build")
                rig.server.snapshot("retained-before-build")
                rig.shutdown()
                preservation = compare(source / "migration-restarted.json",
                                       runtime / "migration-retained-before-build.json", native_normalized)
                write_json(runtime / "retained-comparison.json", preservation)
                endurance.require(preservation["status"] == "PASS", "Retained fixture changed before operation")
            else:
                creation = configure_new_fresh_fixture(runtime)
            (runtime / "advantage-works.allow").touch()
            (runtime / "migration-operate.allow").touch()
            rig.boot("endurance")
            if source is None:
                report["fresh_fixture_proof"] = prove_fresh_fixture_before_build(rig, creation)
            rig.start(build=True)
        initial = {rig.name: rig.documents["W-01"]["worldTime"] for rig in rigs}
        started = time.monotonic()
        cycles = args.seconds // 60
        for cycle in range(1, cycles + 1):
            deadline = started + cycle * 60
            while time.monotonic() < deadline:
                for rig in rigs:
                    endurance.require(rig.server.process.poll() is None, rig.name + ": server died")
                time.sleep(min(1, max(0, deadline - time.monotonic())))
            for rig in rigs:
                label = f"cycle-{cycle:03}"
                rig.checkpoint(label)
                rig.batch(label)
                if cycle < cycles:
                    rig.seed()
                report["profiles"][rig.name] = dict(status="RUNNING", completed_batches=len(rig.ledger),
                                                   latest_world_time=rig.documents["W-01"]["worldTime"])
            write_json(receipt, report)
            print(f"{cycle * 60}s: all requested profiles passed batch {cycle}", flush=True)
        elapsed = time.monotonic() - started
        ticks = {rig.name: rig.documents["W-01"]["worldTime"] - initial[rig.name] for rig in rigs}
        for rig in rigs:
            endurance.require(ticks[rig.name] >= args.seconds * 18, rig.name + ": fewer than 18 game ticks per second")
            rig.structure()
            rig.checkpoint("before-restart")
            rig.batch("before-restart")
            rig.command("save-all flush", "Saved the game")
            rig.shutdown()
            rig.boot("endurance-restart")
            rig.checkpoint("after-restart")
            rig.batch("after-restart")
            rig.structure()
            rig.seed()
        time.sleep(60)
        for rig in rigs:
            rig.checkpoint("restart-resumed")
            rig.batch("restart-resumed")
            statuses = rig.structure()
            rig.command("save-all flush", "Saved the game")
            rig.shutdown()
            totals = Counter()
            for batch in rig.ledger:
                if batch["label"].startswith("cycle-"):
                    totals.update(batch["outputs"])
            report["profiles"][rig.name] = dict(status="PASS", elapsed_seconds=elapsed, endurance_batches=cycles,
                                               game_ticks=ticks[rig.name], verified_checkpoints=len(rig.ledger),
                                               endurance_outputs=dict(totals), station_statuses=statuses,
                                               restart="PASS", resumed_processing="PASS")
        report["status"] = "PASS" if args.seconds >= 1800 else "SMOKE_PASS_NOT_ENDURANCE"
    except BaseException as error:
        report.update(status="FAIL", failure=repr(error))
        raise
    finally:
        for rig in rigs:
            try:
                rig.shutdown()
                native1201.verify_runtime(rig.runtime)
            except Exception as error:
                report.update(status="FAIL", shutdown_or_audit_error=repr(error))
        if args.retained:
            report["retained_unchanged"] = fingerprint(args.retained / WORLD) == source_before
            if not report["retained_unchanged"]:
                report["status"] = "FAIL"
        write_json(receipt, report)
    print(report["status"] + ": " + str(receipt), flush=True)
    return int(report["status"] == "FAIL")


if __name__ == "__main__":
    raise SystemExit(main())
