"""Run the measured Works feed, conservation and cold-restart protocol on Forge 52."""

import argparse
from collections import Counter
import json
from pathlib import Path
import time

import endurance
from matrix import WORLD, digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_broad import read_json
from native117_endurance import NativeEnduranceRig, configure_new_fresh_fixture, prove_fresh_fixture_before_build
from native117_identity import target_id
import native1211 as native
from native1211_upgrade import comparison, copy_source
import semantic_machine_audit


class EnduranceHold(Exception):
    pass


def check_hold(root):
    if (root / "hold-endurance.request").is_file():
        raise EnduranceHold("Operator requested hold")


def measure_batch(documents, definitions):
    # Use a derived view for the frozen oracle; raw checkpoint documents stay intact.
    def project(row):
        if "nbt" not in row:
            return row
        view = dict(row, nbt=native.compat_tile_nbt(row).snbt())
        view.pop("nbtBase64", None)
        return view

    try:
        views = {
            station: dict(document, snapshot=[project(row) for row in document["snapshot"]])
            if document.get("snapshot") else document
            for station, document in documents.items()
        }
    except (AssertionError, KeyError, ValueError, TypeError) as error:
        return dict(status="FAIL", failures=[str(error)])
    return semantic_machine_audit.endurance_batch(views, definitions, identity=target_id)


class Rig(NativeEnduranceRig):
    server_type = native.Server

    def __init__(self, name, runtime, java, launcher, definitions, inventory, expected):
        super().__init__(name, runtime, java, launcher, definitions)
        self.code_inventory, self.expected_jars = inventory, expected

    def start(self, build=False):
        # Prime an inert rig. Otherwise old start-action stock can enter a conveyor
        # while console commands clear and replace the finite measured inputs.
        self.command("tick freeze", "The game is frozen")
        try:
            return super().start(build=build)
        finally:
            self.command("tick unfreeze", "The game is running normally")

    def seed(self, initial=False):
        if initial:
            return super().seed(initial=True)  # start() already holds the native tick gate.
        self.command("tick freeze", "The game is frozen")
        try:
            return super().seed(initial=False)
        finally:
            self.command("tick unfreeze", "The game is running normally")

    def checkpoint(self, label):
        for station in endurance.CHECKED:
            self.command(f"advworks checkpoint {station} {label}", "Checkpoint written for " + station)
            document = read_json(self.runtime / "advantage-works-results" / f"{station}-checkpoint-{label}.json")
            self.documents[station], self.snapshots[station] = document, native.parse_endurance_snapshot(document)

    def batch(self, label):
        measured = measure_batch(
            {station: self.documents[station] for station in ("S-04", "E-03", "W-04", "W-03")}, self.definitions)
        with (self.runtime / f"semantic-batch-{label}.json").open("x", encoding="utf-8") as stream:
            json.dump(measured, stream, indent=2)
        endurance.require(measured["status"] == "PASS", "Independent batch measurements failed: " + str(measured))
        return endurance.Rig.batch(self, label, power="basepower")

    def boot(self, phase):
        native.verify_runtime(self.runtime, self.code_inventory, self.expected_jars)
        super().boot(phase)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "output", "java"):
        parser.add_argument("--" + name, required=True, type=lambda value: Path(value).resolve())
    parser.add_argument("--inventory-sha256", required=True)
    parser.add_argument("--seconds", type=int, default=1800)
    parser.add_argument("--retained", type=lambda value: Path(value).resolve(strict=True))
    parser.add_argument("--retained-receipt-sha256")
    parser.add_argument("--port", type=int, default=25658)
    args = parser.parse_args()
    endurance.require(args.seconds >= 60 and args.seconds % 60 == 0, "Use whole one-minute batches")
    if not 1024 <= args.port < 65535:
        parser.error("Use unprivileged consecutive localhost ports")
    root = output_path(args.output)
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, version = native.java21(args.java)
    seed_before = fingerprint(args.seed)
    for protected in (args.profile, args.seed, args.inventory, java, *(item[1] for item in target[2].values())):
        require_disjoint(root, protected)
    source_before = None
    if args.retained:
        require_disjoint(root, args.retained)
        receipts = list(args.retained.glob("native1211-*-upgrade-results.json"))
        if len(receipts) != 1 or args.retained_receipt_sha256 is None:
            raise ValueError("Pin the exact passing retained migration receipt")
        native.validate_hash(receipts[0], args.retained_receipt_sha256)
        prior = read_json(receipts[0])
        if (prior["status"] != "PASS" or prior["profileSha256"] != digest(args.profile)
                or prior["inventorySha256"] != digest(args.inventory)
                or digest(args.retained / "migration-restarted.json") != prior["phases"]["restarted"]["snapshotSha256"]):
            raise AssertionError("Retained input does not match the passing pinned migration")
        source_before = fingerprint(args.retained)
    spec = native.RIG / "src/main/resources/assets/advantageworkstest/works/works-1.13.2.json"
    definitions = {row["id"]: row for row in read_json(spec)["stations"]}
    report = dict(status="RUNNING", minecraft=native.MINECRAFT, forge=native.FORGE,
                  qualificationStatus=native.EXPLORATORY, javaVersion=version, javaSha256=digest(java),
                  jars={key: item[2] for key, item in target[2].items()}, duration_seconds=args.seconds,
                  interval_seconds=60, profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  stationDefinitionSha256=digest(spec), active_stations=endurance.ACTIVE,
                  checked_stations=endurance.CHECKED, profiles={}, retained=str(args.retained) if args.retained else None)
    receipt = root / "native1211-endurance-results.json"
    write_json(receipt, report)
    rigs = []
    try:
        cases = (("fresh1211", None), ("retained-upgrade-native1211", args.retained)) if args.retained else (("fresh1211", None),)
        for index, (name, source) in enumerate(cases):
            check_hold(root)
            runtime = root / name
            expected = native.stage(runtime, *target, args.port + index)
            rig = Rig(name, runtime, java, native.LAUNCHER, definitions, target[1], expected)
            rigs.append(rig)
            if source:
                copy_source(source, runtime)
                write_json(runtime / "migration-fixture.json", read_json(source / "migration-fixture.json"))
                rig.boot("retained-before-build")
                rig.server.snapshot("retained-before-build")
                rig.shutdown()
                preservation = comparison(read_json(source / "migration-restarted.json"),
                                          read_json(runtime / "migration-retained-before-build.json"), modern=False)
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
                check_hold(root)
                for rig in rigs:
                    endurance.require(rig.server.process.poll() is None, rig.name + ": server died")
                time.sleep(min(1, max(0, deadline - time.monotonic())))
            for rig in rigs:
                check_hold(root)
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
            check_hold(root)
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
        resume_deadline = time.monotonic() + 60
        while time.monotonic() < resume_deadline:
            check_hold(root)
            time.sleep(min(1, max(0, resume_deadline - time.monotonic())))
        for rig in rigs:
            check_hold(root)
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
    except EnduranceHold as hold:
        report.update(status="HELD", holdReason=str(hold))
    except BaseException as error:
        report.update(status="FAIL", failure=repr(error))
        raise
    finally:
        for rig in rigs:
            try:
                rig.shutdown()
                native.verify_runtime(rig.runtime, rig.code_inventory, rig.expected_jars)
            except Exception as error:
                report.update(status="FAIL", shutdown_or_audit_error=repr(error))
        report["seedUnchanged"] = fingerprint(args.seed) == seed_before
        if not report["seedUnchanged"]:
            report["status"] = "FAIL"
        if args.retained:
            report["retained_unchanged"] = fingerprint(args.retained) == source_before
            if not report["retained_unchanged"]:
                report["status"] = "FAIL"
        write_json(receipt, report)
    print(report["status"] + ": " + str(receipt), flush=True)
    return 2 if report["status"] == "HELD" else int(report["status"] == "FAIL")


if __name__ == "__main__":
    raise SystemExit(main())
