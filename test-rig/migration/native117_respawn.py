"""Retained dimension/player fixtures through native 1.17 death and respawn."""

import argparse
import copy
import itertools
import json
from pathlib import Path
import re
import shutil
import socket

import nbtlib
from actors import enchantments
from native117_actor_io import semantic, bind, parsed_actor
from dimensions import PLAYER_STATE, exact_item_differences
from native117_actors import ROUTES, baseline
from matrix import WORLD, copy_fixture, differences, digest, fingerprint, parse_nbt, write_json
from native117_code import Server
from native117 import output_path, pinned_profile, require_clean_log, require_disjoint
from respawn import respawn_verdict, spawn_reference

respawn_verdict = bind(respawn_verdict, semantic=semantic, parse_nbt=parsed_actor)
REQUEST_PROTOCOL = "native117-respawn-request-v1"
REQUEST_INDEX = "respawn-requests.json"
CASE_LABELS = tuple(boot + "-" + keep + "-" + mode
                    for boot in ("respawn", "respawn-restart") for keep in ("keep", "drop")
                    for mode in ("forced", "bed", "missing"))
REQUEST_FIELDS = {"protocol", "id", "baseFixtureSha256", "keepInventory", "bedMode"}


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key: " + key)
            result[key] = value
        return result
    return json.loads(Path(path).read_bytes(), object_pairs_hook=unique)


def case_options(label):
    if not isinstance(label, str) or label not in CASE_LABELS:
        raise ValueError("Unsafe or unknown respawn request label")
    _, keep, mode = label.rsplit("-", 2)
    return keep == "keep", mode


def request_file(runtime, label):
    case_options(label)
    runtime = Path(runtime).resolve()
    folder = runtime / "respawn-requests"
    if folder.is_symlink() or (folder.exists() and folder.resolve() != folder):
        raise ValueError("Respawn request directory must not redirect outside the runtime")
    return folder / (label + ".json")


def read_request(runtime, label):
    path = request_file(runtime, label)
    base = Path(runtime) / "dimension-fixture.json"
    if path.is_symlink() or not path.is_file() or base.is_symlink() or not base.is_file():
        raise ValueError("Respawn request and base fixture must be regular files")
    if path.stat().st_size > 4096:
        raise ValueError("Respawn request exceeds the explicit size limit")
    value = read_json(path)
    keep, mode = case_options(label)
    if (not isinstance(value, dict) or set(value) != REQUEST_FIELDS
            or value.get("protocol") != REQUEST_PROTOCOL or value.get("id") != label
            or type(value.get("keepInventory")) is not bool or value["keepInventory"] is not keep
            or value.get("bedMode") != mode
            or not isinstance(value.get("baseFixtureSha256"), str)
            or not re.fullmatch(r"[A-F0-9]{64}", value["baseFixtureSha256"])
            or digest(base) != value["baseFixtureSha256"]):
        raise ValueError("Respawn request schema, label options or immutable base hash differ")
    return dict(value, path="respawn-requests/" + label + ".json", sha256=digest(path))


def publish_request(runtime, label, keep, mode):
    expected_keep, expected_mode = case_options(label)
    if type(keep) is not bool or keep is not expected_keep or mode != expected_mode:
        raise ValueError("Respawn request options do not match the exact case label")
    path = request_file(runtime, label)
    base = Path(runtime) / "dimension-fixture.json"
    if base.is_symlink() or not base.is_file():
        raise ValueError("Immutable base fixture is not a regular file")
    value = dict(protocol=REQUEST_PROTOCOL, id=label, baseFixtureSha256=digest(base),
                 keepInventory=keep, bedMode=mode)
    path.parent.mkdir(exist_ok=True)
    # Java reads only after the command is sent; exclusive creation closes first.
    with path.open("xb") as stream:
        stream.write((json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8"))
    return read_request(runtime, label)


def verify_request(runtime, label, record, expected):
    actual = read_request(runtime, label)
    canonical = lambda value: json.dumps(value, sort_keys=True, separators=(",", ":"))
    if canonical(actual) != canonical(expected) or canonical(record.get("respawnRequest")) != canonical(actual):
        raise AssertionError("Native respawn request acknowledgement or published index differs")
    definition = read_json(Path(runtime) / "dimension-fixture.json")
    options = definition.setdefault("respawn", {})
    if not isinstance(options, dict):
        raise ValueError("Invalid base respawn options")
    options.update(keepInventory=actual["keepInventory"], bedMode=actual["bedMode"])
    native = record.get("respawn", {})
    if (record.get("operation") != "respawn" or canonical(record.get("definition")) != canonical(definition)
            or type(native.get("keepInventory")) is not bool
            or native["keepInventory"] is not actual["keepInventory"] or native.get("bedMode") != actual["bedMode"]):
        raise AssertionError("Native respawn changed the root definition or ignored its validated options")
    return actual


def verify_request_files(runtime, labels):
    labels = tuple(labels)
    if not labels or len(set(labels)) != len(labels):
        raise ValueError("Require a nonempty unique respawn case set")
    for label in labels:
        case_options(label)
    folder = Path(runtime) / "respawn-requests"
    actual = set(folder.iterdir()) if folder.is_dir() else set()
    expected = {request_file(runtime, label) for label in labels}
    if actual != expected:
        raise AssertionError("Respawn request directory has missing or unexpected case files")
    return {label: read_request(runtime, label) for label in labels}


def native_respawn_verdict(record, registry, world_spawn):
    # Only translate the exact renamed platform class. Unknown handlers still fail.
    value = copy.deepcopy(record)
    if value.get("respawn", {}).get("handler") == "ServerPlayNetHandler.PERFORM_RESPAWN":
        value["respawn"]["handler"] = "NetHandlerPlayServer.PERFORM_RESPAWN"
    return respawn_verdict(value, registry, world_spawn)


def run(args):
    from native117 import boot
    root = output_path(args.output)
    require_disjoint(root, args.fixtures)
    target = pinned_profile(args.target_manifest.resolve(), "target117")
    with socket.socket() as port:
        port.bind(("127.0.0.1", args.port))
    root.mkdir(parents=True)
    results = {}
    sources = {}
    requests = {"protocol": REQUEST_PROTOCOL, "cases": {}}
    write_json(root / REQUEST_INDEX, requests)
    for route in args.routes:
        source = args.fixtures / route
        before = fingerprint(source)
        sources[route] = {"path": str(source.resolve()), "fingerprint": before,
                          "baseFixtureSha256": digest(source / "dimension-fixture.json")}
        write_json(root / "source-fingerprints.json", sources)
        runtime = root / route
        launcher = boot(runtime, target, args.java8, args.port)
        copy_fixture(source, runtime)
        shutil.copy2(source / "dimension-fixture.json", runtime / "dimension-fixture.json")
        props = runtime / "server.properties"
        props.write_text(props.read_text().replace("allow-nether=false", "allow-nether=true"))
        definition = read_json(runtime / "dimension-fixture.json")
        base_sha = sources[route]["baseFixtureSha256"]
        if digest(runtime / "dimension-fixture.json") != base_sha:
            raise AssertionError("Copied immutable dimension fixture differs from the source")
        player_file = runtime / WORLD / "playerdata" / (definition["playerUuid"] + ".dat")
        expected_player = baseline(source, "dimensions")[0]["file"]
        expected_items = {key: expected_player[key] for key in ("Inventory", "EnderItems")}
        migrated_player = None
        try:
            for boot_label in ("respawn", "respawn-restart"):
                server = Server(runtime, args.java8, launcher, boot_label)
                try:
                    if migrated_player is None:
                        # Let the real pre-datafix world hook migrate the copy once.
                        # Independent deaths restore that native player, not raw old bytes
                        # after the hook has already completed for this process.
                        migrated_player = player_file.read_bytes()
                        bootstrap = semantic(nbtlib.load(player_file).unpack(), PLAYER_STATE, enchantments(runtime))
                        bootstrap_issues = differences(expected_items, bootstrap, "initial-migration")
                        bootstrap_issues.extend(exact_item_differences(expected_items, bootstrap, "initial-migration"))
                        if bootstrap_issues:
                            write_json(runtime / "bootstrap-failure.json", bootstrap_issues)
                            raise AssertionError("Native player bootstrap lost or changed source inventory")
                        folder = runtime / "bootstrap-evidence"
                        folder.mkdir()
                        shutil.copy2(player_file, folder / "player.dat")
                        shutil.copy2(runtime / WORLD / "level.dat", folder / "level.dat")
                    for keep, mode in itertools.product((True, False), ("forced", "bed", "missing")):
                        label = boot_label + "-" + ("keep" if keep else "drop") + "-" + mode
                        player_file.write_bytes(migrated_player)
                        if digest(runtime / "dimension-fixture.json") != base_sha:
                            raise AssertionError("Immutable dimension fixture changed before respawn")
                        binding = publish_request(runtime, label, keep, mode)
                        case = route + "/" + label
                        requests["cases"][case] = {"request": binding, "recordSha256": None}
                        write_json(root / REQUEST_INDEX, requests)
                        server.send("advdimensions respawn " + label)
                        server.wait("ADVDIMENSIONS COMPLETE " + label, 120)
                        server.send("save-all flush")
                        server.wait("Saved the", 45)
                        record_path = runtime / f"dimensions-{label}.json"
                        record = read_json(record_path)
                        verify_request(runtime, label, record, binding)
                        requests["cases"][case]["recordSha256"] = digest(record_path)
                        write_json(root / REQUEST_INDEX, requests)
                        registry = enchantments(runtime)
                        verdict = native_respawn_verdict(record, registry, spawn_reference(runtime / WORLD / "level.dat"))
                        observed_before = semantic(parse_nbt(record["respawn"]["before"]), PLAYER_STATE, registry)
                        source_issues = differences(expected_items, observed_before, "source-inventory")
                        source_issues.extend(exact_item_differences(expected_items, observed_before, "source-inventory"))
                        verdict["differences"].extend(source_issues)
                        if source_issues:
                            verdict["status"] = "FAIL"
                        saved = semantic(nbtlib.load(player_file).unpack(), PLAYER_STATE, registry)
                        observed = semantic(parse_nbt(record["respawn"]["after"]), PLAYER_STATE, registry)
                        issues = differences(observed, saved, "saved-player") + exact_item_differences(observed, saved, "saved-player")
                        verdict["differences"].extend(issues)
                        if issues:
                            verdict["status"] = "FAIL"
                        folder = runtime / "respawn-evidence" / label
                        folder.mkdir(parents=True)
                        shutil.copy2(player_file, folder / "player.dat")
                        shutil.copy2(runtime / WORLD / "level.dat", folder / "level.dat")
                        shutil.copy2(runtime / binding["path"], folder / "request.json")
                        if digest(folder / "request.json") != binding["sha256"]:
                            raise AssertionError("Archived respawn request differs")
                        results[route + "/" + label] = verdict
                        write_json(root / "results.json", results)
                        print(route + "/" + label + "=" + verdict["status"], flush=True)
                finally:
                    server.close()
                require_clean_log(runtime / ("console-" + boot_label + ".log"))
            verify_request_files(runtime, CASE_LABELS)
        finally:
            unchanged = fingerprint(source) == before
            base_unchanged = digest(runtime / "dimension-fixture.json") == base_sha
            write_json(runtime / "source-integrity.json", {"unchanged": unchanged,
                       "baseFixtureUnchanged": base_unchanged, "baseFixtureSha256": base_sha})
            if not unchanged:
                raise AssertionError("Protected respawn source changed")
            if not base_unchanged:
                raise AssertionError("Immutable dimension fixture changed during respawn")
    status = "FAIL" if any(result["status"] != "PASS" for result in results.values()) else "PASS"
    write_json(root / "summary.json", {"status": status, "cases": len(results), "results": results,
               "targetManifest": str(args.target_manifest.resolve()), "targetManifestSha256": digest(args.target_manifest),
               "sourceFixtures": "UNCHANGED", "realClientLoginAndDeathScreen": "NOT_TESTED",
               "requestProtocol": REQUEST_PROTOCOL, "requestIndexSha256": digest(root / REQUEST_INDEX),
               "oracleAdapter": "Exact ServerPlayNetHandler to NetHandlerPlayServer class rename only"})
    return int(status != "PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--target-manifest", type=Path, required=True)
    parser.add_argument("--java8", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--port", type=int, choices=(25574, *range(25584, 25588)), required=True)
    parser.add_argument("--routes", nargs="+", choices=ROUTES,
                        default=["original110", "maintained112", "original110-chained-113"])
    raise SystemExit(run(parser.parse_args()))
