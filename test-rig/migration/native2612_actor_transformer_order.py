"""Actual two-order actor proof with source-numeric enchantments; never production qualification."""

import argparse
import base64
import copy
from datetime import datetime, timezone
from functools import partial
import gzip
import io
import json
from pathlib import Path
from types import SimpleNamespace
import traceback

import nbtlib

from matrix import WORLD, digest, fingerprint, write_json
from native117 import output_path, require_disjoint
from native117_actor_io import bind
from native117_identity import target_id
import native2612 as native
import native2612_actors as actors
import native2612_transformer_order as order
import native2612_upgrade as upgrade


SCOPE = "INSTRUMENTED_ACTOR_PLAYER_ENTITY_ORDER_PROOF"
WITNESSES = ("cart", "dropped-musket")
PHASES = ("converted", "adapter", "restarted", "runtime", "runtime-restart")
STOP_PHASES = ("conversion", "restart", "runtime-restart")
ENCHANTMENTS = {10: "basesteam:high_explosive", 11: "basesteam:powderless",
                12: "basesteam:rapid_reload", 13: "basesteam:recoil"}
MUTATIONS = ("curse", "missing", "extra", "level", "count", "wear", "payload")


def item(entity, witness, *, native_format):
    if witness == "cart":
        values = entity.get("Items", ())
        matches = [value for value in values if str(value.get("id", "")).endswith(":musket")]
        if len(matches) != 1 or matches[0].get("Slot") != 1:
            raise AssertionError("Missing/duplicate cart musket at its source slot")
        result = matches[0]
    else:
        result = entity.get("Item", {})
    if not str(result.get("id", "")).endswith(":musket"):
        raise AssertionError("Missing " + witness + " musket")
    if native_format and result.get("id") != "basesteam:musket":
        raise AssertionError("Native musket identity was not preserved")
    return result


def exact_value(expected, observed):
    if type(expected) is not type(observed):
        return False
    if isinstance(expected, dict):
        return expected.keys() == observed.keys() and all(exact_value(value, observed[key]) for key, value in expected.items())
    if isinstance(expected, list):
        return len(expected) == len(observed) and all(exact_value(left, right) for left, right in zip(expected, observed))
    return expected == observed


def source_context(level, record, expected):
    version = level.get("Data", {}).get("DataVersion")
    if type(version) is not nbtlib.Int or int(version) != 512:
        raise AssertionError("Context regression requires the approved raw original110 schema")
    registry = level.get("FML", {}).get("Registries", {}).get("minecraft:enchantments", {}).get("ids", ())
    saved = {}
    for entry in registry:
        number = entry.get("V")
        if type(number) is not nbtlib.Int or int(number) in saved or type(entry.get("K")) is not nbtlib.String:
            raise AssertionError("Ambiguous saved Forge enchantment index")
        saved[int(number)] = str(entry["K"])
    if {number: target_id(saved.get(number, "")) for number in ENCHANTMENTS} != ENCHANTMENTS:
        raise AssertionError("Saved IDs10..13 do not name the four Steam enchantments")
    rows = record.get("entities", [])
    result = {}
    for witness in WITNESSES:
        matches = [row for row in rows if row.get("witness") == witness]
        if len(matches) != 1:
            raise AssertionError("Missing/duplicate raw source witness: " + witness)
        raw = gzip.decompress(base64.b64decode(matches[0]["nbtBase64"], validate=True))
        stream = io.BytesIO(raw)
        entity = nbtlib.File.parse(stream)
        if stream.read(1):
            raise AssertionError("Trailing raw source entity data")
        stack = item(entity, witness, native_format=False)
        entries = stack.get("tag", {}).get("ench", ())
        if len(entries) != 4 or any(type(row.get("id")) is not nbtlib.Short
                or type(row.get("lvl")) is not nbtlib.Short or int(row["lvl"]) != 1 for row in entries):
            raise AssertionError("Source enchantments must remain four numeric short IDs at level1")
        ids = [int(row["id"]) for row in entries]
        if len(set(ids)) != 4 or set(ids) != set(ENCHANTMENTS):
            raise AssertionError("Source numeric enchantment witnesses were changed")
        goal = item(expected["entities"][witness], witness, native_format=True)
        if goal.get("components", {}).get("minecraft:enchantments") != dict.fromkeys(ENCHANTMENTS.values(), 1):
            raise AssertionError("Historical oracle disagrees with the independently saved registry")
        result[witness] = dict(numericIds=ids, savedNames=[saved[number] for number in ids], levels=[1] * 4,
                               expectedStack=copy.deepcopy(goal))
    return dict(sourceDataVersion=512, savedForgeIds=saved, witnesses=result, targetObservationsUsed=False)


def preflight(args):
    source = Path(args.fixture).resolve(strict=True)
    upgrade.validate_hash(args.fixture_receipt, args.fixture_receipt_sha256)
    approved = upgrade.read_json(args.fixture_receipt)
    before = fingerprint(source)
    if Path(approved["source"]).resolve() != source or approved["sourceFingerprint"] != before:
        raise AssertionError("Actor fixture differs from its approved independent receipt")
    expected, label = actors.baseline(source, "actors")
    context = source_context(nbtlib.load(source / WORLD / "level.dat"),
                             upgrade.read_json(source / ("actors-" + label + ".json")), expected)
    if fingerprint(source) != before:
        raise AssertionError("Read-only source preflight changed its fixture")
    return dict(source=str(source), fixtureReceipt=str(args.fixture_receipt),
                fixtureReceiptSha256=args.fixture_receipt_sha256.upper(),
                sourceFingerprint=before, sourceLabel=label, context=context)


def assert_witnesses(expected, observed):
    result = {}
    for witness in WITNESSES:
        if witness not in observed.get("entities", {}):
            raise AssertionError("Missing native witness: " + witness)
        actual = item(observed["entities"][witness], witness, native_format=True)
        before = expected["witnesses"][witness]["expectedStack"]
        if not exact_value(before, actual):
            raise AssertionError("Exact native musket payload changed: " + witness + ": " + repr(actual))
        result[witness] = copy.deepcopy(actual)
    return result


def negative_controls(expected, observed):
    assert_witnesses(expected, observed)
    results = {"baseline": "PASS"}
    for witness in WITNESSES:
        for mutation in MUTATIONS:
            changed = copy.deepcopy(observed)
            stack = item(changed["entities"][witness], witness, native_format=True)
            components = stack["components"]
            levels = components["minecraft:enchantments"]
            first = next(iter(levels))
            if mutation == "curse": components["minecraft:enchantments"] = {"minecraft:binding_curse": 1}
            elif mutation == "missing": del levels[first]
            elif mutation == "extra": levels["minecraft:unbreaking"] = 1
            elif mutation == "level": levels[first] = 2
            elif mutation == "count": stack["count"] += 1
            elif mutation == "wear": components["minecraft:damage"] += 1
            elif mutation == "payload": components.setdefault("minecraft:custom_data", {})["order_probe_mutation"] = 1
            try:
                assert_witnesses(expected, changed)
            except AssertionError:
                results[witness + "/" + mutation] = "REJECTED"
            else:
                raise AssertionError("Actor witness mutation accepted: " + witness + "/" + mutation)
    return dict(status="PASS", scope="DERIVED_IN_MEMORY_NATIVE_ACTOR_WITNESS_NEGATIVES",
                qualificationAllowed=False, controls=results, sourceOrWorldFilesMutated=False)


def raw_receipt(path):
    raw = upgrade.read_json(path)
    if raw.get("status") != "PASS" or type(raw.get("matchedEntities")) is not int or raw["matchedEntities"] < 2:
        raise AssertionError("Missing complete, nonempty stopped entity audit: " + str(path))
    return dict(file=str(path), sha256=digest(path), **raw)


def all_orders_proven(primary, modes):
    controls = {"baseline": "PASS", **{witness + "/" + mutation: "REJECTED"
                for witness in WITNESSES for mutation in MUTATIONS}}
    return (primary.get("status") == "PASS" and set(modes) == set(order.MODES)
            and all(mode.get("status") == "PASS" and mode.get("sourceUnchanged") is True
                    and set(mode.get("witnessChecks", {})) == set(PHASES)
                    and all(row.get("status") == "PASS" for row in mode["witnessChecks"].values())
                    and set(mode.get("rawAudits", {})) == set(STOP_PHASES)
                    and all(row.get("status") == "PASS" for row in mode["rawAudits"].values())
                    and mode.get("entityControls", {}).get("controls") == controls for mode in modes.values()))


def run_actor_campaign(args, *, server_type, stage_function, hook_only=False):
    if hook_only is not False:
        raise ValueError("Actor order proof always requires all three native actor boots")
    approved = preflight(args)
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, _ = native.java25(args.java)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    runtime = output / "server"
    report = output / "native2612-actor-order-campaign.json"
    result = dict(status="RUNNING", scope=SCOPE, qualificationAllowed=False,
                  profileSha256=digest(args.profile), sourceContext=approved["context"],
                  checks={}, witnessChecks={}, rawAudits={}, entityControls=dict(status="NOT_RUN"))
    write_json(report, result)
    expected_jars = {}

    def stage(destination, actual_target, port):
        expected_jars.update(stage_function(destination, *actual_target, port))
        return native.LAUNCHER

    class CheckedServer(server_type):
        def __init__(self, *values):
            native.verify_runtime(runtime, target[1], expected_jars)
            super().__init__(*values)

        def close(self):
            failure = None
            try:
                super().close()
            except BaseException as error:
                failure = error
            try:
                native.verify_runtime(runtime, target[1], expected_jars)
            except BaseException as audit:
                if failure is None:
                    raise
                failure.add_note("Pinned seven-jar audit also failed: " + repr(audit))
            if failure is not None:
                raise failure

    values = SimpleNamespace(**vars(args))
    values.target_manifest, values.java17, values.kind = args.profile, java, "actors"
    try:
        result["checks"] = actors.run_route(Path(args.fixture), runtime, target, values, stage=stage,
            server_type=CheckedServer, stop_audit=partial(actors.audit_stopped,
                minecraft_version=native.MINECRAFT, data_version=4790, stopping_phase="ServerStoppingEvent"),
            check_log=native.require_clean_log, minecraft=native.MINECRAFT)
        for label in PHASES:
            path = runtime / ("semantic-" + label + ".json")
            try:
                observed = upgrade.read_json(path)
                stacks = assert_witnesses(approved["context"], observed)
                result["witnessChecks"][label] = dict(status="PASS", file=str(path), sha256=digest(path), stacks=stacks)
            except (AssertionError, OSError, ValueError) as error:
                result["witnessChecks"][label] = dict(status="FAIL", file=str(path), error=repr(error))
        for label in STOP_PHASES:
            path = runtime / "stopped-entity-evidence" / label / "receipt.json"
            result["rawAudits"][label] = raw_receipt(path)
        failures = [key for key, value in result["checks"].items() if value.get("status") != "PASS"]
        failures.extend("witness-" + key for key, value in result["witnessChecks"].items() if value["status"] != "PASS")
        if not result["checks"] or failures:
            raise AssertionError("Actor order payload failed: " + repr(failures) + "; retained " + str(report))
        result["entityControls"] = negative_controls(approved["context"], upgrade.read_json(runtime / "semantic-converted.json"))
        result["status"] = "PASS"
    except BaseException as error:
        result.update(status="FAIL", error=repr(error), traceback=traceback.format_exc())
        checkpoint = runtime / "native117-comparison.json"
        if checkpoint.is_file(): result["checks"] = upgrade.read_json(checkpoint)
        raise
    finally:
        result["sourceUnchanged"] = fingerprint(Path(args.fixture)) == approved["sourceFingerprint"]
        if not result["sourceUnchanged"]: result["status"] = "FAIL"
        write_json(report, result)
    return int(result["status"] != "PASS")


def run(args):
    args.source, args.legacy_reference, args.hook_only = args.fixture, None, False
    approved = preflight(args)
    root = output_path(args.output)
    for path in (args.fixture, args.fixture_receipt, args.profile, args.seed, args.inventory, args.build_receipt, args.pre_run_seal):
        require_disjoint(root, path)
    for path in (args.fixture, args.fixture_receipt, args.profile, args.seed, args.inventory, args.build_receipt):
        require_disjoint(args.pre_run_seal, path)
    upgrade.validate_hash(args.build_receipt, args.build_receipt_sha256)
    build = upgrade.read_json(args.build_receipt)
    target = native.read_family_target(args.profile, args.seed, args.inventory, args.inventory_sha256)
    java, _ = native.java25(args.java)
    files = order.validate_build(build, args.profile, java)
    files.update(upgrade.oracle_closure())
    files.update({Path(args.fixture_receipt): digest(args.fixture_receipt), Path(args.build_receipt): digest(args.build_receipt),
                  Path(args.profile): digest(args.profile), Path(args.inventory): digest(args.inventory), java: digest(java)})
    files.update({Path(path): sha for _, path, sha in target[2].values()})
    seed_before = fingerprint(Path(args.seed))
    with Path(args.pre_run_seal).open("x", encoding="utf-8") as output:
        json.dump(dict(scope="GENUINE_PRE_RUN_ACTOR_INCLUSIVE_ORDER_CUSTODY", createdUtc=datetime.now(timezone.utc).isoformat(),
                       setupNotStarted=True, qualificationAllowed=False, source=approved, seedFingerprint=seed_before,
                       files={str(path): sha for path, sha in files.items()}, modes=list(reversed(order.MODES)) if args.bm_first else order.MODES),
                  output, indent=2, sort_keys=True)
    seal_sha = digest(args.pre_run_seal)
    facade = SimpleNamespace(**{**vars(upgrade), "SCOPE": SCOPE, "read_source": preflight,
        "run": run_actor_campaign, "oracle_closure": lambda: {**upgrade.oracle_closure(),
                        Path(args.fixture_receipt): args.fixture_receipt_sha256.upper()}})
    campaign = bind(order.run, upgrade=facade, MODES=tuple(reversed(order.MODES)) if args.bm_first else order.MODES)
    exit_code = 1
    proven = False
    try:
        order.verify_files(files)
        exit_code = campaign(args)
    finally:
        if root.is_dir():
            base = root / "native2612-transformer-order-results.json"
            primary = upgrade.read_json(base) if base.is_file() else dict(status="FAIL", error="Missing order receipt")
            modes = {}
            for mode in order.MODES:
                path = root / mode / "native2612-actor-order-campaign.json"
                if path.is_file(): modes[mode] = dict(file=str(path), sha256=digest(path), **upgrade.read_json(path))
            custody_failure = None
            try:
                order.verify_files({**files, Path(args.pre_run_seal): seal_sha})
                if fingerprint(Path(args.seed)) != seed_before or fingerprint(Path(args.fixture)) != approved["sourceFingerprint"]:
                    raise AssertionError("Pre-run protected tree changed")
            except Exception as error:
                custody_failure = repr(error)
            proven = custody_failure is None and all_orders_proven(primary, modes)
            write_json(root / "native2612-actor-order-results.json", dict(status="PASS" if proven else "FAIL", scope=SCOPE,
                qualificationAllowed=False, profileSha256=digest(args.profile), preRunSeal=str(args.pre_run_seal),
                preRunSealSha256=seal_sha, preRunCustodyFailure=custody_failure, sourceContext=approved["context"],
                orderReceipt=str(base), orderReceiptSha256=digest(base) if base.is_file() else None, actorCampaigns=modes,
                finalBothOrdersProven=proven))
    return exit_code or int(not proven)


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    for name in ("fixture", "fixture-receipt", "profile", "seed", "inventory", "java", "build-receipt", "output", "pre-run-seal"):
        cli.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    for name in ("fixture-receipt-sha256", "inventory-sha256", "build-receipt-sha256"):
        cli.add_argument("--" + name, required=True)
    cli.add_argument("--port", type=int, required=True)
    cli.add_argument("--bm-first", action="store_true", help="Run actual BM-first priority mode first; failures remain failures")
    return cli


def main():
    cli = parser()
    args = cli.parse_args()
    if not 26450 <= args.port <= 65534: cli.error("Require two assigned consecutive disposable ports")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
