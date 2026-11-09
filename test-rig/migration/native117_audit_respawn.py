"""Read-only replay of native respawn quantities, source items and saved players."""

import argparse
from pathlib import Path

import nbtlib
from actors import enchantments
from native117_actor_io import semantic
from dimensions import PLAYER_STATE, exact_item_differences
from matrix import WORLD, differences, digest, fingerprint, parse_nbt, write_json
from native117 import pinned_profile, require_clean_log
from native117_actors import ROUTES, baseline
from native117_respawn import (native_respawn_verdict, REQUEST_PROTOCOL, REQUEST_INDEX,
                               CASE_LABELS, read_json, verify_request, verify_request_files)
from respawn import spawn_reference


def request_integrity(root, sources, results):
    findings, bindings = [], {}
    expected = {route + "/" + label for route in sources for label in CASE_LABELS}
    if not sources or any(route not in ROUTES for route in sources):
        findings.append("Respawn request routes are empty or unknown")
    try:
        index = read_json(root / REQUEST_INDEX)
        if (not isinstance(index, dict) or set(index) != {"protocol", "cases"}
                or index["protocol"] != REQUEST_PROTOCOL or not isinstance(index["cases"], dict)):
            raise ValueError("Unknown respawn request index protocol")
        if set(index["cases"]) != expected or set(results) != expected:
            findings.append("Published request/result case coverage is incomplete or has extra cases")
        for route, source in sources.items():
            if route not in ROUTES:
                continue
            runtime = root / route
            try:
                base = runtime / "dimension-fixture.json"
                checksum = source.get("baseFixtureSha256")
                if (not checksum or base.is_symlink() or digest(base) != checksum
                        or digest(Path(source["path"]) / "dimension-fixture.json") != checksum):
                    raise AssertionError("Immutable root fixture bytes differ from the source")
                verify_request_files(runtime, CASE_LABELS)
            except (OSError, ValueError, AssertionError) as error:
                findings.append(route + ": " + str(error))
            for label in CASE_LABELS:
                case = route + "/" + label
                try:
                    entry = index["cases"].get(case)
                    if not isinstance(entry, dict) or set(entry) != {"request", "recordSha256"}:
                        raise AssertionError("Missing or incomplete case request binding")
                    path = runtime / ("dimensions-" + label + ".json")
                    if digest(path) != entry["recordSha256"]:
                        raise AssertionError("Native case record differs from its published binding")
                    record = read_json(path)
                    binding = verify_request(runtime, label, record, entry["request"])
                    if binding["baseFixtureSha256"] != source.get("baseFixtureSha256"):
                        raise AssertionError("Request refers to another root fixture")
                    copy = runtime / "respawn-evidence" / label / "request.json"
                    if copy.is_symlink() or digest(copy) != binding["sha256"]:
                        raise AssertionError("Archived closed request differs from the consumed request")
                    bindings[case] = binding
                except (OSError, ValueError, AssertionError, KeyError, TypeError) as error:
                    findings.append(case + ": " + str(error))
    except (OSError, ValueError, TypeError, KeyError) as error:
        findings.append("Respawn request index invalid: " + str(error))
    return {"protocol": REQUEST_PROTOCOL, "findings": findings, "bindings": bindings,
            "expectedCases": len(expected), "verifiedCases": len(bindings)}


def replay(root, target_manifest=None):
    results = read_json(root / "results.json")
    sources = read_json(root / "source-fingerprints.json")
    summary_file = root / "summary.json"
    summary = read_json(summary_file) if summary_file.is_file() else None
    if summary is None and target_manifest is None:
        raise ValueError("Aborted campaigns require an explicit pinned target manifest")
    manifest = Path(summary["targetManifest"]) if summary else target_manifest.resolve()
    if summary and digest(manifest) != summary["targetManifestSha256"]:
        raise AssertionError("Respawn target manifest changed")
    pinned_profile(manifest, "target117")
    findings, fresh = [], {}
    if summary is None:
        findings.append("Campaign summary missing: aborted run is not a completed qualification")
    elif summary.get("status") != "PASS":
        findings.append("Campaign summary does not record a complete pass")
    requests = request_integrity(root, sources, results)
    findings.extend(requests["findings"])
    if summary and (summary.get("requestProtocol") != REQUEST_PROTOCOL
                    or not (root / REQUEST_INDEX).is_file()
                    or summary.get("requestIndexSha256") != digest(root / REQUEST_INDEX)):
        findings.append("Campaign request protocol/index hash is missing or changed")
    for route, source_entry in sources.items():
        if route not in ROUTES:
            findings.append("Unknown or unsafe respawn source route")
            continue
        source = Path(source_entry["path"])
        if fingerprint(source) != source_entry["fingerprint"]:
            findings.append(route + ": protected source changed")
        runtime = root / route
        expected_file = baseline(source, "dimensions")[0]["file"]
        expected_items = {key: expected_file[key] for key in ("Inventory", "EnderItems")}
        for jar in read_json(runtime / "input-jars.json"):
            if digest(runtime / "mods" / jar["file"]) != jar["sha256"]:
                findings.append(route + ": staged mod changed")
        for label in ("respawn", "respawn-restart"):
            log = runtime / ("console-" + label + ".log")
            if not log.is_file():
                findings.append(route + "/" + label + ": server log missing")
                continue
            try:
                require_clean_log(log)
            except AssertionError as error:
                findings.append(route + "/" + label + ": " + str(error))
        for case in (case for case in results if case.startswith(route + "/")):
            label = case.split("/")[1]
            if label not in CASE_LABELS or case != route + "/" + label:
                findings.append(case + ": unsafe or unknown case label")
                continue
            folder = runtime / "respawn-evidence" / label
            record = read_json(runtime / f"dimensions-{label}.json")
            registry = enchantments(runtime)
            result = native_respawn_verdict(record, registry, spawn_reference(folder / "level.dat"))
            before = semantic(parse_nbt(record["respawn"]["before"]), PLAYER_STATE, registry)
            source_issues = differences(expected_items, before, "source-inventory")
            source_issues.extend(exact_item_differences(expected_items, before, "source-inventory"))
            result["differences"].extend(source_issues)
            observed = semantic(parse_nbt(record["respawn"]["after"]), PLAYER_STATE, registry)
            saved = semantic(nbtlib.load(folder / "player.dat").unpack(), PLAYER_STATE, registry)
            save_issues = differences(observed, saved, "saved-player") + exact_item_differences(observed, saved, "saved-player")
            result["differences"].extend(save_issues)
            if source_issues or save_issues:
                result["status"] = "FAIL"
            fresh[case] = result
            if result != results[case]:
                findings.append(case + ": raw oracle result differs from recorded result")
            if result["status"] != "PASS":
                findings.append(case + ": behavioral assertion fails")
    expected_cases = {route + "/" + boot + "-" + keep + "-" + mode
                      for route in sources for boot in ("respawn", "respawn-restart")
                      for keep in ("keep", "drop") for mode in ("forced", "bed", "missing")}
    missing = sorted(expected_cases - fresh.keys())
    if missing or (summary and len(fresh) != summary["cases"]) or len(fresh) != len(results):
        findings.append("Respawn case coverage is incomplete")
    return {"status": "FAIL" if findings else "PASS", "cases": len(fresh), "findings": findings,
            "expectedCases": len(expected_cases), "missingCases": missing,
            "results": fresh, "realClientDeathScreen": "NOT_TESTED",
            "requestIntegrity": requests,
            "targetManifest": str(manifest), "targetManifestSha256": digest(manifest)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--target-manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    result = replay(args.run.resolve(), args.target_manifest)
    write_json(args.output, result)
    print(result["status"] + ": " + str(result["cases"]) + " cases; " + str(len(result["findings"])) + " integrity findings")
    raise SystemExit(int(result["status"] != "PASS"))
