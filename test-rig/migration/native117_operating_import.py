"""Cold-import an accepted operating fixture without reconstructing its machines."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import traceback

from matrix import WORLD, digest, fingerprint, write_json
import native116
from native116_lifecycle import verify_staged as verify_staged_116
from native117 import output_path, pinned_profile, require_disjoint
from native117_code import Server
from native117_lifecycle import prepare, verify_staged
from native117_machine_operations import validate_operation, validate_restart


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def verify_source_profile(manifest, source):
    entry = read(manifest)["target116"]
    required = {"basepower", "basesteam", "baseelectric", "basemetals", "orespawn", "advantageworkstest"}
    ids = [row["id"] for row in entry["jars"]]
    if set(ids) != required or len(ids) != len(required):
        raise ValueError("Expected the complete released-identity 1.16.5 profile")
    seed, launcher, jars = pinned_profile(manifest, "target116")
    if launcher != native116.LAUNCHER or digest(seed / launcher) != entry["launcher_sha256"].upper():
        raise AssertionError("Source Forge 1.16.5 launcher changed")
    native116.verify_code(seed, entry["serverCode"])
    if read(seed / native116.INVENTORY) != entry["serverCode"]:
        raise AssertionError("Source Forge 1.16.5 inventory receipt changed")
    for row, jar in zip(entry["jars"], jars):
        if not row["version"].endswith(".116051"):
            raise ValueError("Expected a native 1.16.5 mod version")
        native116.jar_identity(jar, row["id"], row["version"])
    verify_staged_116(source, (seed, launcher, jars))


def approved_source(source, report_sha256):
    report_path = source / "native116-machine-operation-results.json"
    if digest(report_path) != report_sha256.upper(): raise AssertionError("Source operating report changed")
    report = read(report_path)
    if (report.get("status") != "PASS" or report.get("group") != "all"
            or report.get("protocolUnchanged") is not True
            or set(report.get("phases", {})) != {"operation", "restart"}
            or any(row.get("status") != "PASS" or row.get("cases") != 132 for row in report["phases"].values())):
        raise AssertionError("Import requires the complete accepted 132-case source, not a passing subset")
    manifest = Path(report["manifest"])
    if digest(manifest) != report["manifestSha256"]: raise AssertionError("Source mod manifest changed")
    verify_source_profile(manifest, source)
    fixture_path = source / "machine-operation-fixture.json"
    if digest(fixture_path) != report["fixtureSha256"]: raise AssertionError("Source fixture authority changed")
    fixture = read(fixture_path)
    if len(fixture.get("cases", [])) != 132: raise AssertionError("Incomplete source fixture")
    catalog = read(source / "advantage-works-results/machine-catalog-measured.json")
    actual = read(source / "advantage-works-results/machine-operation-measured.json")
    if validate_operation(catalog, actual, "all")["status"] != "PASS":
        raise AssertionError("Source raw operating assertions did not pass")
    inspected = read(source / "advantage-works-results/machine-inspection-restarted.json")
    if validate_restart(fixture, inspected)["status"] != "PASS":
        raise AssertionError("Source raw cold inspection differs from its fixture")
    if not (source / WORLD / "level.dat").is_file(): raise FileNotFoundError("Missing source world")
    return fixture, report_path


def run(args):
    source = args.source.resolve()
    output = output_path(args.output)
    for protected in (source, args.target_manifest): require_disjoint(output, protected)
    fixture, source_report = approved_source(source, args.source_report_sha256)
    target = pinned_profile(args.target_manifest.resolve(), "target117")
    for protected in (target[0], *target[2]): require_disjoint(output, protected)
    before = fingerprint(source)
    launcher = prepare(output, target, [], args.port)
    shutil.copytree(source / WORLD, output / WORLD, ignore=shutil.ignore_patterns("session.lock"))
    shutil.copy2(source / "machine-operation-fixture.json", output / "machine-operation-fixture.json")
    (output / "advantage-machine-operation.allow").touch()
    (output / "migration-operate.allow").touch()
    report = dict(status="RUNNING", scope="132 accepted native116 operating setups, frozen import and full restart only; no construction or operation claimed",
                  source=str(source), sourceReportSha256=digest(source_report), sourceFingerprintBefore=before,
                  targetManifest=str(args.target_manifest.resolve()), targetManifestSha256=digest(args.target_manifest),
                  startedAtUtc=datetime.now(timezone.utc).isoformat(), phases={})
    server = None
    try:
        for label in ("converted", "restarted"):
            server = Server(output, args.java16, launcher, label)
            server.send("advmachines inspect " + label)
            server.wait("ADVMACHINES INSPECTED " + label, 360)
            inspected = read(output / f"advantage-works-results/machine-inspection-{label}.json")
            report["phases"][label] = validate_restart(fixture, inspected)
            server.send("save-all flush")
            server.close(); server = None
            write_json(output / "native117-operating-import-results.json", report)
        verify_staged(output, target)
        if digest(output / "machine-operation-fixture.json") != digest(source / "machine-operation-fixture.json"):
            raise AssertionError("Fixture authority changed during import")
        report["status"] = "PASS" if all(row["status"] == "PASS" for row in report["phases"].values()) else "FAIL"
    except BaseException as error:
        report.update(status="FAIL", failure=str(error), traceback=traceback.format_exc())
    finally:
        if server:
            try: server.close()
            except BaseException as error: report.update(status="FAIL", shutdownError=str(error))
        report["sourceUnchanged"] = fingerprint(source) == before
        if not report["sourceUnchanged"]: report.update(status="FAIL", sourceError="Protected source changed")
        report["completedAtUtc"] = datetime.now(timezone.utc).isoformat()
        write_json(output / "native117-operating-import-results.json", report)
    return int(report["status"] != "PASS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--source-report-sha256", required=True)
    parser.add_argument("--target-manifest", type=Path, required=True)
    parser.add_argument("--java16", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    raise SystemExit(run(parser.parse_args()))
