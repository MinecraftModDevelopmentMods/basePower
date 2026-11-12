"""Genuine preseal, exact stopped freeze and independent Forge66 endurance replay."""

import argparse
from contextlib import ExitStack
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

import nbtlib

import audit_native1201_endurance as checkpoint_audit
import endurance
from matrix import WORLD
from native117_actor_io import bind
from native117_entity_storage import _decompress, _nbt, _payloads
from native117_machine_saved import state
import native12111_endurance_replay as previous
import native263 as native
import native263_platform_diagnostics as platform_diagnostics
from native263_endurance import MODES, RECEIPT, interval_metrics, measure_batch, verify_fresh_fixture_proof, require_retained_receipt
from native263_layout import DATA_VERSION, region_file, read_worldgen
from native263_block_states import decode_palette


Budget = previous.Budget
require, pin, regular = previous.require, previous.pin, previous.regular
write_new, disjoint, tree = previous.write_new, previous.disjoint, previous.tree
stopped = previous.stopped
MAX_JSON = previous.MAX_JSON
row_nbt, typed_nbt_or_none = previous.row_nbt, previous.typed_nbt_or_none
snapshot = bind(previous.snapshot, native=native)
terrain = bind(previous.terrain, DATA_VERSION=DATA_VERSION, decode_palette=decode_palette)
complete = bind(previous.complete, native=native, MODES=MODES)
feed_audit = bind(previous.feed_audit, native=native)
PRE_RUN = "NATIVE66_ENDURANCE_PRE_RUN_SEAL"
RETROSPECTIVE = "NATIVE66_ENDURANCE_RETROSPECTIVE_SEAL"
FREEZE = "NATIVE66_STOPPED_ENDURANCE_FREEZE"


def oracle_paths():
    # Warm the typed-array path before inventorying its imported native dependencies.
    nbtlib.IntArray([3, 2, 1]).snbt()
    paths = set(previous.oracle_paths())
    paths.update(Path(path).resolve() for path in (sys.executable, getattr(sys, "_base_executable", sys.executable)))
    for module in list(sys.modules.values()):
        path = getattr(module, "__file__", None)
        if path and getattr(module, "__name__", "").startswith("numpy"):
            paths.add(Path(path).resolve())
    import numpy
    runtime_root = Path(numpy.__file__).resolve().parent.parent
    paths.update(path.resolve() for path in (runtime_root / "numpy.libs").glob("*.dll"))
    custody = runtime_root / "native263-python-runtime-custody.json"
    if custody.is_file():
        paths.add(custody)
    return sorted(paths)


class Forge66:
    MINECRAFT, FORGE = native.MINECRAFT, native.FORGE
    verify_runtime = staticmethod(native.audit_staged)
    parse_snapshot = staticmethod(snapshot)

    @staticmethod
    def require_native_boot(path):
        native.require_clean_log(path)
        native.require_complete_boot(path.read_text(encoding="utf-8"), stop_sent=True, returncode=0)

    @staticmethod
    def verify_batch(runtime, label, documents, definitions):
        measured = measure_batch({key: documents[key] for key in ("S-04", "E-03", "W-04", "W-03")}, definitions)
        recorded = native.read_json(runtime / f"semantic-batch-{label}.json")
        require(measured["status"] == "PASS" and measured == recorded,
                f"{runtime.name}/{label}: independent batch measurements disagree")


def engine_inputs(path, expected, budget):
    platform_diagnostics.require_controls()
    pin(budget.read(path), expected)
    proof = budget.json(path)
    require(proof.get("status") == "STRICT_CLASSIFIER_AND_ORDERED_COLDSTOP_READY"
            and proof.get("noJvmFlagsAdded") is True and proof.get("debugNotSuppressed") is True
            and proof.get("coreSha256") == budget.digest(Path(native.__file__))
            and proof.get("classifierSha256") == budget.digest(Path(platform_diagnostics.__file__)),
            "Engine proof does not describe the actual current reviewed core/classifier")
    require(platform_diagnostics.COMMON_JAR_SHA in proof["proofFiles"].values()
            and platform_diagnostics.SOURCES_JAR_SHA in proof["proofFiles"].values(),
            "Primary Netty source and native binary proof are missing")
    require({platform_diagnostics.HOST_CONTROL_SHA, platform_diagnostics.HOST_FIXTURE_SHA,
             platform_diagnostics.HOST_FILE_FIXTURE_SHA, platform_diagnostics.NETTY_FIXTURE_SHA,
             platform_diagnostics.OSHI_JAR_SHA} <= set(proof["proofFiles"].values()),
            "Independent native host control, exact appender fixtures or OSHI binary proof missing")
    files = {Path(row["path"]): row["sha256"] for row in proof["sourceClosure"]}
    files.update({Path(name): checksum for name, checksum in proof["proofFiles"].items()})
    for name, checksum in files.items():
        pin(budget.read(name), checksum)
    return files


def seal(profile, profile_sha256, specification, inventory, inventory_sha256, java, budget, *,
         retrospective=False, retained_receipt=None, retained_receipt_sha256=None,
         engine_receipt=None, engine_receipt_sha256=None):
    pin(budget.read(profile), profile_sha256)
    pin(budget.read(inventory), inventory_sha256)
    document = budget.json(profile)
    require(document["minecraftVersion"] == native.MINECRAFT and document["forge"]["version"] == native.FORGE_VERSION,
            "Wrong selected native runtime profile")
    mods = document["mods"]
    require(len(mods) == 6 and {row["id"] for row in mods} == set(native.VERSIONS), "Seal requires exactly six native inputs")
    for row in mods:
        require(Path(row["path"]).name == row["file"] and row["version"] == native.VERSIONS[row["id"]],
                "Artifact identity/version differs")
        pin(budget.read(row["path"]), row["sha256"])
    executable, version = native.java25(Path(java))
    native.verify_compile_pin(next(Path(row["path"]) for row in mods if row["id"] == "advantageworkstest"),
                              next(row["sha256"] for row in mods if row["id"] == "basepower"))
    require(engine_receipt is not None and engine_receipt_sha256 is not None,
            "Preseal must bind the reviewed platform classifier and primary-source proof")
    proof_files = engine_inputs(engine_receipt, engine_receipt_sha256, budget)
    retained_files = []
    if retained_receipt is not None:
        pin(budget.read(retained_receipt), retained_receipt_sha256)
        prior = budget.json(retained_receipt)
        require_retained_receipt(prior, profile_sha256.upper(), inventory_sha256.upper())
        retained_files.append(Path(retained_receipt))
    files = [Path(profile), Path(specification), Path(inventory), executable, Path(engine_receipt),
             *(Path(row["path"]) for row in mods), *retained_files, *proof_files, *oracle_paths()]
    return dict(format=1, purpose=RETROSPECTIVE if retrospective else PRE_RUN,
                createdAtUtc=datetime.now(timezone.utc).isoformat(), profile=str(Path(profile).resolve()),
                specification=str(Path(specification).resolve()), inventory=str(Path(inventory).resolve()),
                java=str(executable), javaVersion=version,
                engineReceipt=str(Path(engine_receipt).resolve()), engineReceiptSha256=engine_receipt_sha256.upper(),
                retainedReceipt=str(Path(retained_receipt).resolve()) if retained_receipt else None,
                files={str(path.resolve()): budget.digest(path) for path in files},
                oracles=[str(path) for path in oracle_paths()])


def verify_seal(path, expected, budget):
    pin(budget.read(path), expected)
    document = budget.json(path)
    require(document.get("format") == 1 and document.get("purpose") in (PRE_RUN, RETROSPECTIVE), "Wrong input seal")
    require(document["oracles"] == [str(path) for path in oracle_paths()], "Full transitive oracle module coverage changed")
    required = set(document["oracles"]) | {document[key] for key in ("profile", "specification", "inventory", "java", "engineReceipt")}
    if document.get("retainedReceipt"):
        required.add(document["retainedReceipt"])
    require(required <= document["files"].keys(), "Seal lists an oracle/input without binding its bytes")
    require(document["files"][document["engineReceipt"]] == document["engineReceiptSha256"], "Engine receipt pin differs")
    proof_files = engine_inputs(Path(document["engineReceipt"]), document["engineReceiptSha256"], budget)
    require(all(document["files"].get(str(path.resolve())) == checksum for path, checksum in proof_files.items()),
            "Seal omits reviewed engine/source proof bytes")
    for name, checksum in document["files"].items():
        pin(budget.read(name), checksum)
    return document


def custody(sealed, run, budget):
    setup = min(datetime.fromisoformat(budget.json(
        run / mode / "advantage-works-results/W-01-checkpoint-setup.json")["recordedAtUtc"]) for mode in MODES)
    created = datetime.fromisoformat(sealed["createdAtUtc"])
    retrospective = sealed["purpose"] == RETROSPECTIVE
    require(retrospective or created < setup, "Seal was not made before setup; retrospective cannot qualify")
    return dict(preRunCustody=not retrospective, sealCreatedAtUtc=sealed["createdAtUtc"], earliestSetupAtUtc=setup.isoformat(),
                coverageGap="No pre-run oracle custody proof; seal is retrospective" if retrospective else None)


def freeze(run, seal_path, seal_sha256, receipt_sha256, output, budget):
    run, output = Path(run).resolve(), Path(output).resolve()
    sealed = verify_seal(seal_path, seal_sha256, budget)
    disjoint(output, run, seal_path, *(Path(name) for name in sealed["files"]))
    pin(budget.read(run / RECEIPT), receipt_sha256)
    complete(budget.json(run / RECEIPT))
    provenance = custody(sealed, run, budget)
    output.mkdir()
    result = dict(status="FAIL", format=1, purpose=FREEZE, sealSha256=seal_sha256.upper(),
                  inputReceiptSha256=receipt_sha256.upper(), source=str(run), custody=provenance)
    try:
        with ExitStack() as stack:
            for mode in MODES:
                stack.enter_context(stopped(run / mode, budget))
            files = tree(run, budget)
            for name, checksum in files.items():
                data = budget.read(run / name)
                pin(data, checksum)
                destination = output / "run" / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                with destination.open("xb") as stream:
                    stream.write(data)
            require(tree(run, budget) == files and tree(output / "run", budget) == files, "Stopped source/frozen copy changed")
        verify_seal(seal_path, seal_sha256, budget)
        with (output / "seal.json").open("xb") as output_seal:
            output_seal.write(budget.read(seal_path))
        result.update(status="PASS", files=files)
    except BaseException as failure:
        result["failure"] = repr(failure)
        raise
    finally:
        write_new(output / "freeze.json", result)
    return result


def raw_final(runtime, budget):
    world = runtime / WORLD
    root = _nbt(_decompress(budget.read(world / "level.dat", MAX_JSON), 1))
    version = root["Data"].get("DataVersion")
    require(type(version) is nbtlib.Int and int(version) == DATA_VERSION, "Wrong stopped native level DataVersion")
    # Absence/unknown fields stay in the actual separate SavedData, never synthesized from level.dat.
    read_worldgen(world)
    grouped, count = {}, 0
    for station in endurance.CHECKED:
        document = budget.json(runtime / "advantage-works-results" / f"{station}-checkpoint-restart-resumed.json")
        snapshot(document)
        dimension = document.get("dimension", "minecraft:overworld")
        for row in document["snapshot"]:
            x, _, z = row["pos"]
            grouped.setdefault((dimension, x // 16, z // 16), []).append(row)
            count += 1
    regions, differences = {}, []
    for (dimension, cx, cz), rows in grouped.items():
        budget.check()
        path = region_file(world, dimension, cx, cz)
        if path not in regions:
            regions[path] = dict(_payloads(budget.read(path), cx // 32, cz // 32))
        require((cx, cz) in regions[path], "Missing stopped terrain chunk")
        sections, tiles = terrain(regions[path][cx, cz], (cx, cz))
        for row in rows:
            x, y, z = pos = tuple(row["pos"])
            palette = sections.get(y // 16)
            actual = palette[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] if palette else {"Name": "minecraft:air"}
            actual = dict(Name=actual["Name"], Properties=actual.get("Properties", {}))
            if actual != state(row) or typed_nbt_or_none(row_nbt(row)) != typed_nbt_or_none(tiles.get(pos)):
                differences.append(dict(pos=list(pos), dimension=dimension, checkpoint=row,
                                        savedState=actual, savedNbt=typed_nbt_or_none(tiles.get(pos))))
    require(not differences, "Stopped raw state/component mismatch: " + json.dumps(differences[:3]))
    return dict(status="PASS", dataVersion=DATA_VERSION, comparedRows=count, comparedChunks=len(grouped))


def interval_audit(runtime, report, budget):
    station = runtime / "advantage-works-results"
    before = budget.json(station / "W-01-checkpoint-setup.json")
    measured = []
    for index in range(1, 31):
        label = f"cycle-{index:03}"
        after = budget.json(station / f"W-01-checkpoint-{label}.json")
        measured.append(interval_metrics(before, after, label))
        before = after
    resumed = interval_metrics(budget.json(station / "W-01-checkpoint-after-restart.json"),
                               budget.json(station / "W-01-checkpoint-restart-resumed.json"), "restart-resumed")
    require(measured == report.get("intervalMeasurements") and resumed == report.get("resumedRate")
            and min(row["ticksPerSecond"] for row in measured) == report.get("minimumIntervalTicksPerSecond"),
            "Recorded per-interval TPS differs from native checkpoint clocks")
    return dict(status="PASS", measuredIntervals=len(measured),
                minimumTicksPerSecond=min(row["ticksPerSecond"] for row in measured), resumed=resumed)


def replay(frozen, freeze_sha256, seal_sha256, budget):
    frozen = Path(frozen).resolve()
    pin(budget.read(frozen / "freeze.json"), freeze_sha256)
    manifest = budget.json(frozen / "freeze.json")
    require(manifest.get("status") == "PASS" and manifest.get("format") == 1
            and manifest.get("purpose") == FREEZE and manifest.get("sealSha256") == seal_sha256.upper(),
            "Incomplete or different frozen evidence")
    sealed = verify_seal(frozen / "seal.json", seal_sha256, budget)
    run = frozen / "run"
    require(tree(run, budget) == manifest["files"], "Frozen file inventory changed")
    pin(budget.read(run / RECEIPT), manifest["inputReceiptSha256"])
    report = budget.json(run / RECEIPT)
    complete(report)
    provenance = custody(sealed, run, budget)
    require(manifest.get("custody") == provenance, "Frozen custody attestation changed")
    require(report["profileSha256"] == sealed["files"][sealed["profile"]]
            and report["stationDefinitionSha256"] == sealed["files"][sealed["specification"]]
            and report["inventorySha256"] == sealed["files"][sealed["inventory"]]
            and report["javaSha256"] == sealed["files"][sealed["java"]] and report["javaVersion"] == sealed["javaVersion"]
            and report["inputSealSha256"].upper() == seal_sha256.upper()
            and report["jars"] == {row["id"]: row["sha256"].upper() for row in budget.json(sealed["profile"])["mods"]},
            "Producer differs from pre-run seal")
    verify_fresh_fixture_proof(run / MODES[0], report["fresh_fixture_proof"])

    class BoundedForge66(Forge66):
        @staticmethod
        def verify_batch(runtime, label, documents, definitions):
            budget.check()
            Forge66.verify_batch(runtime, label, documents, definitions)

    replay_checkpoints = bind(checkpoint_audit.replay, read_json=budget.json, digest=budget.digest)
    measured = replay_checkpoints(run, Path(sealed["profile"]), Path(sealed["specification"]), adapter=BoundedForge66)
    raw, feeds, rates = {}, {}, {}
    for mode in MODES:
        inventory = budget.json(run / mode / native.INVENTORY)
        require(inventory == budget.json(sealed["inventory"]), "Runtime code inventory differs from sealed seed")
        require(not list((run / mode).glob("native*-launch-environment-*.json")), "Diagnostic launch cannot qualify")
        raw[mode], feeds[mode] = raw_final(run / mode, budget), feed_audit(run / mode, budget)
        rates[mode] = interval_audit(run / mode, report["profiles"][mode], budget)
    require(tree(run, budget) == manifest["files"], "Frozen files changed during replay")
    verify_seal(frozen / "seal.json", seal_sha256, budget)
    return dict(status="PASS" if provenance["preRunCustody"] else "PASS_RETROSPECTIVE_REPLAY_NOT_PRE_RUN_CUSTODY",
                qualificationAllowed=provenance["preRunCustody"], custody=provenance,
                scope="Independent stopped native66 raw and every-batch replay; not client/player-conversion acceptance",
                inputFreezeSha256=freeze_sha256.upper(), inputSealSha256=seal_sha256.upper(),
                inputReceiptSha256=manifest["inputReceiptSha256"], checkpointReplay=measured, stoppedRaw=raw, nativeFeeds=feeds,
                intervalThroughput=rates)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    make_seal = sub.add_parser("seal")
    for name in ("profile", "specification", "inventory", "java", "output"):
        make_seal.add_argument("--" + name, required=True, type=Path)
    make_seal.add_argument("--engine-receipt", required=True, type=Path)
    for name in ("profile-sha256", "inventory-sha256"):
        make_seal.add_argument("--" + name, required=True)
    make_seal.add_argument("--engine-receipt-sha256", required=True)
    make_seal.add_argument("--retained-receipt", type=Path)
    make_seal.add_argument("--retained-receipt-sha256")
    make_seal.add_argument("--retrospective", action="store_true")
    make_freeze = sub.add_parser("freeze")
    for name in ("run", "seal", "output"):
        make_freeze.add_argument("--" + name, required=True, type=Path)
    for name in ("seal-sha256", "receipt-sha256"):
        make_freeze.add_argument("--" + name, required=True)
    make_replay = sub.add_parser("replay")
    for name in ("frozen", "output"):
        make_replay.add_argument("--" + name, required=True, type=Path)
    for name in ("freeze-sha256", "seal-sha256"):
        make_replay.add_argument("--" + name, required=True)
    args = parser.parse_args()
    budget = Budget()
    if args.command == "seal":
        disjoint(args.output, args.profile, args.specification, args.inventory, args.java)
        result = seal(args.profile, args.profile_sha256, args.specification, args.inventory, args.inventory_sha256,
                      args.java, budget, retrospective=args.retrospective, retained_receipt=args.retained_receipt,
                      retained_receipt_sha256=args.retained_receipt_sha256, engine_receipt=args.engine_receipt,
                      engine_receipt_sha256=args.engine_receipt_sha256)
        write_new(args.output, result)
    elif args.command == "freeze":
        freeze(args.run, args.seal, args.seal_sha256, args.receipt_sha256, args.output, budget)
    else:
        disjoint(args.output, args.frozen)
        write_new(args.output, replay(args.frozen, args.freeze_sha256, args.seal_sha256, budget))


if __name__ == "__main__":
    main()
