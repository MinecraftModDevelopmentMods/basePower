"""Measured two-world Forge65 endurance, new-world proof and full cold restart."""

import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import shutil
import time

import endurance
from matrix import WORLD, digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_actor_io import bind
from native117_endurance import (NativeEnduranceRig, FRESH_FIXTURE_SEED, POI_POSITION,
                                 configure_new_fresh_fixture)
from native117_broad import read_json
import native12111_endurance as previous
import native262 as native
from native262_layout import read_level, read_worldgen, shared_file
from native262_upgrade import SCOPE as MIGRATION_SCOPE, comparison, copy_source


MODES = ("fresh262", "retained-upgrade-native262")
RECEIPT = "native262-endurance-results.json"
EnduranceHold = previous.EnduranceHold
check_hold = previous.check_hold
stop_at_checkpoint = previous.stop_at_checkpoint
measure_batch = bind(previous.measure_batch, native=native)


class ThroughputFailure(AssertionError):
    pass


def interval_metrics(before, after, label):
    if type(before.get("worldTime")) is not int or type(after.get("worldTime")) is not int:
        raise AssertionError("Missing native integer interval world time")
    seconds = (datetime.fromisoformat(after["recordedAtUtc"]) - datetime.fromisoformat(before["recordedAtUtc"])).total_seconds()
    ticks = after["worldTime"] - before["worldTime"]
    if seconds <= 0 or ticks < 0:
        raise AssertionError("Nonmonotonic native interval clocks")
    if ticks < 18 * seconds:
        raise ThroughputFailure(f"RESOURCE_THROUGHPUT_GATE {label}: {ticks / seconds:.3f} TPS below 18; not a gameplay diagnosis")
    return dict(label=label, gameTicks=ticks, elapsedSeconds=seconds, ticksPerSecond=ticks / seconds)


class Rig(previous.Rig):
    server_type = native.Server
    checkpoint = bind(previous.Rig.checkpoint, native=native)
    batch = bind(previous.Rig.batch, measure_batch=measure_batch)

    def boot(self, phase):
        native.verify_runtime(self.runtime, self.code_inventory, self.expected_jars)
        NativeEnduranceRig.boot(self, phase)


def world_info(world):
    level, envelope = read_level(world), read_worldgen(world)
    settings = envelope["data"]
    import nbtlib
    if type(settings.get("seed")) is not nbtlib.Long or type(settings.get("generate_structures")) is not nbtlib.Byte:
        raise AssertionError("Native worldgen seed/structure flag is missing or wrongly typed")
    return dict(RandomSeed=int(settings["seed"]), MapFeatures=int(settings["generate_structures"]),
                dataVersion=int(level["DataVersion"]), fullWorldgenSnbt=envelope.snbt())


def verify_fresh_fixture_proof(runtime, proof):
    endurance.require(proof.get("existing_world_absent") is True and proof.get("status") in ("UNVERIFIED", "PASS"),
                      "Missing independent new-world isolation proof")
    for name, expected in proof["files"].items():
        endurance.require(digest(runtime / name) == expected, "Fresh isolation evidence changed: " + name)
    properties = dict(line.split("=", 1) for line in
                      (runtime / "fresh-fixture-proof/server-before-first-creation.properties").read_text().splitlines()
                      if line and not line.startswith("#") and "=" in line)
    endurance.require(properties.get("generate-structures") == "false"
                      and properties.get("level-seed") == str(FRESH_FIXTURE_SEED), "Wrong fresh creation settings")
    actual = world_info(runtime / "fresh-fixture-proof/world")
    endurance.require(actual["MapFeatures"] == 0 and actual["RandomSeed"] == FRESH_FIXTURE_SEED,
                      "Actual native external worldgen differs from configured fresh world")
    endurance.require(actual == proof["actual_world_info"], "Native worldgen/unknown NBT proof changed")
    rows = read_json(runtime / "migration-fresh-isolation-before-build.json")
    endurance.require(len(rows) == 1 and tuple(rows[0][key] for key in ("x", "y", "z")) == POI_POSITION
                      and rows[0]["actualName"] == "minecraft:air" and rows[0] == proof["critical_block"],
                      "Missing exact original composter AIR control")
    return actual


def prove_fresh_fixture_before_build(rig, configuration):
    runtime = rig.runtime
    write_json(runtime / "migration-fixture.json", [dict(id="poi-target", x=POI_POSITION[0], y=POI_POSITION[1], z=POI_POSITION[2])])
    rig.server.snapshot("fresh-isolation-before-build")
    rig.command("save-all flush", "Saved the game")
    root = runtime / "fresh-fixture-proof/world"
    root.mkdir()
    shutil.copy2(runtime / WORLD / "level.dat", root / "level.dat")
    destination = shared_file(root, "minecraft:world_gen_settings")
    destination.parent.mkdir(parents=True)
    shutil.copy2(shared_file(runtime / WORLD, "minecraft:world_gen_settings"), destination)
    files = ["fresh-fixture-proof/server-before-first-creation.properties", "fresh-fixture-proof/world/level.dat",
             "fresh-fixture-proof/world/data/minecraft/world_gen_settings.dat", "migration-fresh-isolation-before-build.json"]
    proof = dict(configuration, status="UNVERIFIED", actual_world_info=world_info(root),
                 critical_block=read_json(runtime / files[-1])[0], files={name: digest(runtime / name) for name in files},
                 scope="NEW native4903 world before Works build; complete external SavedData preserved; no source edits")
    path = runtime / "fresh-fixture-proof.json"
    write_json(path, proof)
    verify_fresh_fixture_proof(runtime, proof)
    proof["status"] = "PASS"
    write_json(path, proof)
    return proof


def require_retained_receipt(prior, profile_sha, inventory_sha):
    if (prior.get("status") != "PASS" or prior.get("scope") != MIGRATION_SCOPE or prior.get("route") != "original110"
            or prior.get("minecraft") != native.MINECRAFT or prior.get("forge") != native.FORGE
            or prior.get("profileSha256") != profile_sha
            or inventory_sha not in prior.get("inputHashes", {}).values()
            or not all(prior.get(key) is True for key in ("sourceUnchanged", "referenceUnchanged", "seedUnchanged", "inputsUnchanged"))):
        raise AssertionError("Endurance requires the full passing original110 migration under the same candidate")
    for phase in ("converted", "restarted"):
        observation = prior["phases"][phase]
        if (observation.get("status") != "PASS" or observation.get("serialized", {}).get("status") != "PASS"
                or observation.get("witnesses") != 14 or observation["serialized"].get("witnesses") != 14
                or observation["serialized"].get("nativeDataVersion") != 4903):
            raise AssertionError("Missing genuine stopped native migration capture")
    if not {"destination", "steamCrusher", "electricFurnace", "readiness"} <= prior["phases"].get("operation", {}).keys():
        raise AssertionError("Hook-only or non-operating migration cannot supply retained endurance")


def retained_input(args):
    if args.retained is None:
        if args.seconds >= 1800:
            raise ValueError("Qualified endurance requires the fresh original-world migration first")
        return None
    if args.retained_receipt is None or args.retained_receipt_sha256 is None:
        raise ValueError("Retained runtime needs its explicit passing migration receipt and SHA-256")
    native.validate_hash(args.retained_receipt, args.retained_receipt_sha256)
    prior = read_json(args.retained_receipt)
    require_retained_receipt(prior, digest(args.profile), digest(args.inventory))
    if (Path(prior["runtime"]).resolve() != args.retained
            or prior.get("inputHashes", {}).get(str(args.inventory)) != digest(args.inventory)
            or digest(args.retained / "migration-restarted.json") != prior["phases"]["restarted"]["snapshotSha256"]):
        raise AssertionError("Retained world does not match the actual passing pinned native262 migration")
    return fingerprint(args.retained)


def run(args):
    endurance.require(args.seconds >= 60 and args.seconds % 60 == 0, "Use whole one-minute batches")
    root = output_path(args.output)
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, version = native.java25(args.java)
    for port in (args.port, args.port + 1):
        native.require_free_port(port)
    for protected in (args.profile, args.seed, args.inventory, java, *(item[1] for item in target[2].values())):
        require_disjoint(root, protected)
    if args.retained:
        require_disjoint(root, args.retained)
    source_before = retained_input(args)
    from native262_endurance_replay import Budget, verify_seal
    sealed = verify_seal(args.seal, args.seal_sha256, Budget())
    if sealed["purpose"] != "NATIVE65_ENDURANCE_PRE_RUN_SEAL":
        raise ValueError("Producer requires a genuine pre-run seal, never retrospective")
    if (sealed["files"].get(str(args.profile)) != digest(args.profile)
            or sealed.get("retainedReceipt") != (str(args.retained_receipt) if args.retained_receipt else None)):
        raise ValueError("Producer inputs differ from pre-run seal")
    seed_before = fingerprint(args.seed)
    spec = native.RIG / "src/main/resources/assets/advantageworkstest/works/works-1.13.2.json"
    definitions = {row["id"]: row for row in read_json(spec)["stations"]}
    report = dict(status="RUNNING", minecraft=native.MINECRAFT, forge=native.FORGE,
                  qualificationStatus=native.EXPLORATORY, javaVersion=version, javaSha256=digest(java),
                  jars={key: item[2] for key, item in target[2].items()}, duration_seconds=args.seconds,
                  interval_seconds=60, profileSha256=digest(args.profile), inventorySha256=digest(args.inventory),
                  stationDefinitionSha256=digest(spec), active_stations=endurance.ACTIVE, checked_stations=endurance.CHECKED,
                  profiles={}, retained=str(args.retained) if args.retained else None, inputSealSha256=args.seal_sha256)
    receipt = root / RECEIPT
    write_json(receipt, report)
    rigs = []
    try:
        cases = tuple(zip(MODES, (None, args.retained))) if args.retained else ((MODES[0], None),)
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
        previous_intervals = {rig.name: rig.documents["W-01"].copy() for rig in rigs}
        intervals = {rig.name: [] for rig in rigs}
        started, cycles = time.monotonic(), args.seconds // 60
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
                intervals[rig.name].append(interval_metrics(previous_intervals[rig.name], rig.documents["W-01"], label))
                previous_intervals[rig.name] = rig.documents["W-01"].copy()
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
            previous_intervals[rig.name] = rig.documents["W-01"].copy()
            rig.batch("after-restart")
            rig.structure()
            rig.seed()
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            check_hold(root)
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        for rig in rigs:
            check_hold(root)
            statuses = stop_at_checkpoint(rig)
            resumed_rate = interval_metrics(previous_intervals[rig.name], rig.documents["W-01"], "restart-resumed")
            totals = Counter()
            for batch in rig.ledger:
                if batch["label"].startswith("cycle-"):
                    totals.update(batch["outputs"])
            report["profiles"][rig.name] = dict(status="PASS", elapsed_seconds=elapsed, endurance_batches=cycles,
                                               game_ticks=ticks[rig.name], verified_checkpoints=len(rig.ledger),
                                               endurance_outputs=dict(totals), station_statuses=statuses,
                                               restart="PASS", resumed_processing="PASS", intervalMeasurements=intervals[rig.name],
                                               minimumIntervalTicksPerSecond=min(row["ticksPerSecond"] for row in intervals[rig.name]),
                                               resumedRate=resumed_rate)
        report["status"] = "PASS" if args.seconds >= 1800 else "SMOKE_PASS_NOT_ENDURANCE"
    except EnduranceHold as hold:
        report.update(status="HELD", holdReason=str(hold))
    except ThroughputFailure as failure:
        report.update(status="FAIL", failure=repr(failure), failureCategory="RESOURCE_THROUGHPUT_NOT_GAMEPLAY_DIAGNOSIS")
        raise
    except BaseException as failure:
        report.update(status="FAIL", failure=repr(failure))
        raise
    finally:
        for rig in rigs:
            try:
                rig.shutdown()
                native.verify_runtime(rig.runtime, rig.code_inventory, rig.expected_jars)
            except Exception as failure:
                report.update(status="FAIL", shutdown_or_audit_error=repr(failure))
        report["seedUnchanged"] = fingerprint(args.seed) == seed_before
        if args.retained:
            report["retained_unchanged"] = fingerprint(args.retained) == source_before
        if not report["seedUnchanged"] or (args.retained and not report["retained_unchanged"]):
            report["status"] = "FAIL"
        try:
            verify_seal(args.seal, args.seal_sha256, Budget())
        except Exception as failure:
            report.update(status="FAIL", sealAuditError=repr(failure))
        write_json(receipt, report)
    print(report["status"] + ": " + str(receipt), flush=True)
    return 2 if report["status"] == "HELD" else int(report["status"] == "FAIL")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("profile", "seed", "inventory", "output", "java", "seal"):
        parser.add_argument("--" + name, required=True, type=lambda value: Path(value).resolve())
    for name in ("inventory-sha256", "seal-sha256"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--seconds", type=int, default=1800)
    parser.add_argument("--retained", type=lambda value: Path(value).resolve(strict=True))
    parser.add_argument("--retained-receipt", type=lambda value: Path(value).resolve(strict=True))
    parser.add_argument("--retained-receipt-sha256")
    parser.add_argument("--port", type=int, required=True)
    return run(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
