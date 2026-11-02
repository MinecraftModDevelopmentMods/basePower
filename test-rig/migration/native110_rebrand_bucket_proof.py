"""Fresh Base24 dependency-only baseline + full renamed candidate, exactly four boots.

Only this helper's process-local callbacks hold the single exact diagnostic.
Ordinary auditors and frozen helpers are never edited. A failed strict receipt
is mandatory before a separately recorded proof-bound migration rerun.
"""
import argparse
from datetime import datetime, timezone
import importlib
import os
from pathlib import Path
import re
import shutil
import socket
import traceback
from unittest.mock import patch
import uuid

import nbtlib

import legacy_bucket_proof as legacy
import native110_machine_operations as operating
from native110 import identity, output_path, require_disjoint
from native110_code import INVENTORY, Server, verify_code
from native110_lifecycle import verify_staged
from native110_rebrand_connected import TARGET_IDS, bind_target
from rig_transport import WORLD, digest, stage, write_json

KIND = "REBRAND_BASE24_FRESH_FOUR_BOOT_V1"
BOOT_LABELS = {"baseline-first", "baseline-restart", "candidate-operate", "candidate-restart"}
BOOTSTRAP = {"minecraft", "mcp", "FML", "Forge"}
PROBE_SHA = "B9F756681593B508A8E3222BD592A3DFCB474303532B154045893B3C585EF1D3"
PASSIVE_PROBE_SHA = "79157E31FFAF5D66CCDC8030EB7B8C37D0E0D33FD6A601D119F59904A461AAD4"
MODS_LOADED = re.compile(r"^\[\d\d:\d\d:\d\d\] \[Server thread/INFO\] \[FML\]: Forge Mod Loader has successfully loaded \d+ mods\s*$")


def require(value, message):
    if not value: raise AssertionError(message)


def protocol_files():
    return [Path(__file__), *[Path(__file__).with_name(name) for name in
        ("legacy_bucket_proof.py", "native110_machine_operations.py", "native110_rebrand_connected.py",
         "native110_rebrand_passive.py", "native110_connected.py", "legacy_connected_oracle.py", "semantic_machine_audit.py",
         "native110.py", "native110_code.py", "native110_lifecycle.py", "native110_diagnostics.py", "rig_transport.py")]]


def pins(path):
    profile, _ = bind_target(path)
    bound = legacy.pins(path)
    require({m["id"] for m in bound["mods"]} == TARGET_IDS and len(bound["mods"]) == 6, "Full six-mod candidate required")
    for mod in bound["mods"]:
        require(identity(Path(mod["path"])) == (mod["id"], mod["version"]), "Candidate metadata changed")
    return profile, bound


def bind_candidate(document, candidate):
    require(document.get("kind") == KIND and document.get("status") == "PASS", "Not a fresh completed renamed-candidate proof")
    require(document.get("candidate") == candidate, "Proof does not bind the entire exact candidate, manifest and Forge inventory")


def require_four_boots(boots, role):
    require(len(boots) == 4 and {r.get("label") for r in boots} == BOOT_LABELS, "Exactly four distinct physical boots required")
    for boot in boots:
        diagnostic = boot.get("diagnostic", {})
        require(boot.get("status") == "PASS_BOOT" and type(boot.get("exitCode")) is int and boot["exitCode"] == 0,
                "Every physical process must exit cleanly")
        require(diagnostic.get("originalSeverity") == "ERROR" and legacy.LATE_BUCKET.fullmatch(diagnostic.get("message", "")),
                "Only the exact one Base Metals diagnostic is eligible")
    require(role.get("status") == "PASS" and role.get("operationCases") == 3 and role.get("exactColdCases") == 3
            and set(role.get("startupFlags", {})) == {"operate", "restart"}, "All three native bucket/oil cases and cold comparisons are mandatory")


def require_baseline_mods(actual, expected):
    require(set(expected) == {"basemetals", "orespawn"}, "Baseline declares only Base24 and the same OreSpawn")
    require({k: v for k, v in actual.items() if k not in BOOTSTRAP} == expected, "Baseline loaded a harness, Power or another undeclared mod")


def observer_sha(mode):
    require(mode in ("connected", "passive-source355", "synthetic-nested"), "Unknown source observer mode")
    return PROBE_SHA if mode == "connected" else PASSIVE_PROBE_SHA


def require_destination_inventory(actual, candidate, mode):
    expected = {Path(m["path"]).name: m["sha256"] for m in candidate["mods"]}
    extra = {k: v for k, v in actual.items() if k not in expected}
    require(len(extra) == 1 and next(iter(extra.values())) == observer_sha(mode),
            "Only this mode's exact authentic source observer may supplement the full candidate")
    expected.update(extra)
    require(actual == expected, "Destination differs from the complete proven candidate")


def mod_list(path):
    rows = nbtlib.load(path)["FML"]["ModList"]
    actual = {str(row["ModId"]): str(row["ModVersion"]) for row in rows}
    require(len(actual) == len(rows), "Duplicate saved loaded-mod identity")
    return actual


def audit_boot(path):
    path = Path(path)
    return legacy.held_diagnostics(path.read_text(encoding="utf-8", errors="replace").splitlines(),
                                   legacy.BASE_SHA, digest(path.parent / legacy.LAUNCHER))


def seal_sources(output):
    seals = {str(p): digest(p) for p in protocol_files()}
    for path in protocol_files():
        dest = output / "protocol-sources" / path.name
        dest.parent.mkdir(exist_ok=True)
        shutil.copy2(path, dest)
    return seals


def collect(args):
    output = output_path(args.output)
    candidate_profile, candidate = pins(args.candidate_manifest.resolve())
    dependencies = [m for m in candidate["mods"] if m["id"] in {"basemetals", "orespawn"}]
    baseline_profile = candidate_profile[:2] + ([Path(m["path"]) for m in dependencies],)
    for protected in (args.candidate_manifest, candidate_profile[0], *candidate_profile[2]): require_disjoint(output, protected)
    for port in (args.port, args.port + 1):
        with socket.socket() as listener: listener.bind(("127.0.0.1", port))
    output.mkdir(parents=True, exist_ok=False)
    token = uuid.uuid4().hex
    report = dict(kind=KIND, status=legacy.PENDING, token=token, candidate=candidate,
        baseline=dict(mods=dependencies, codeInventory=candidate["codeInventory"]), boots=[], candidateRole={}, classification=None,
        scope="Two dependency-only baseline boots without harness/Power, then full six-mod candidate oil/bucket operation and cold restart",
        startedAtUtc=datetime.now(timezone.utc).isoformat(), protocolSha256=seal_sources(output))
    receipt = output / "native110-rebrand-bucket-proof.json"
    write_json(receipt, report)
    server = None
    try:
        root = output / "baseline"
        launcher = stage(root, baseline_profile)
        props = root / "server.properties"
        props.write_text(props.read_text().replace("server-port=25584", "server-port=" + str(args.port)))
        write_json(root / INVENTORY, candidate["codeInventory"])
        write_json(output / "baseline-inputs.json", report["baseline"])
        expected = {m["id"]: m["runtimeVersion"] for m in dependencies}
        for phase in ("first", "restart"):
            verify_staged(root, baseline_profile)
            verify_code(root, candidate["codeInventory"])
            server = Server(root, args.java8, launcher, phase)
            server.send("save-all flush")
            server.wait("Saved the world", 120)
            server.close(); server = None
            diagnostic = audit_boot(root / ("console-" + phase + ".log"))
            saved = output / ("baseline-level-" + phase + ".dat")
            shutil.copy2(root / WORLD / "level.dat", saved)
            loaded = mod_list(saved)
            require_baseline_mods(loaded, expected)
            report["boots"].append(dict(label="baseline-" + phase, status="PASS_BOOT", exitCode=0,
                log="baseline/console-" + phase + ".log", diagnostic=diagnostic, savedModList=loaded,
                savedLevel=saved.name))
            write_json(receipt, report)

        def pending_candidate_boot(path):
            path = Path(path)
            require(path.parent.resolve() == (output / "candidate").resolve(), "Unexpected candidate runtime")
            phase = {"console-operate.log": "operate", "console-restart.log": "restart"}.get(path.name)
            require(phase is not None, "Unexpected candidate boot phase")
            verify_staged(path.parent, candidate_profile)
            verify_code(path.parent, candidate["codeInventory"])
            diagnostic = audit_boot(path)
            write_json(path.with_suffix(".diagnostics.json"), diagnostic)
            report["boots"].append(dict(label="candidate-" + phase, status="PASS_BOOT", exitCode=0,
                log="candidate/" + path.name, diagnostic=diagnostic))
            write_json(receipt, report)

        with patch.dict(os.environ, {"ADVWORKS_BUCKET_PROOF": token}), \
                patch.object(operating, "require_native_boot", pending_candidate_boot):
            code = operating.run(argparse.Namespace(manifest=args.candidate_manifest, output=output / "candidate",
                java8=args.java8, port=args.port + 1, group="power/bucket"))
        require(code == 0, "Full renamed candidate bucket operation/cold campaign failed")
        report["candidateRole"] = legacy.validate_role(operating, output / "candidate", token, candidate)
        require_four_boots(report["boots"], report["candidateRole"])
        require(pins(args.candidate_manifest)[1] == candidate, "Candidate inputs changed")
        require(report["protocolSha256"] == {str(p): digest(p) for p in protocol_files()}, "Proof protocol changed")
        report.update(status="PASS", classification=dict(severity="NOTICE", originalSeverity="ERROR",
            exactDiagnostic="REDUNDANT_BASE24_ENABLE_UNIVERSAL_BUCKET_AFTER_INIT",
            candidateManifestSha256=candidate["manifestSha256"], baseSha256=legacy.BASE_SHA, forgeSha256=legacy.PUBLIC_FORGE_SHA))
    except BaseException as error:
        report.update(status="FAIL", classification=None, failure=str(error), traceback=traceback.format_exc())
    finally:
        if server:
            try: server.close()
            except BaseException as error: report.update(status="FAIL", classification=None, shutdownError=str(error))
        report["completedAtUtc"] = datetime.now(timezone.utc).isoformat()
        report["filesSha256"] = {p.relative_to(output).as_posix(): digest(p) for p in output.rglob("*") if p.is_file() and p != receipt}
        write_json(receipt, report)
    return int(report["status"] != "PASS")


def verify_proof(path, checksum):
    path = path.resolve()
    require(digest(path) == checksum.upper(), "Explicit fresh proof receipt hash changed")
    document = legacy.read(path)
    _, candidate = pins(Path(document["candidate"]["manifest"]))
    bind_candidate(document, candidate)
    classification = document.get("classification") or {}
    require(classification == dict(severity="NOTICE", originalSeverity="ERROR",
        exactDiagnostic="REDUNDANT_BASE24_ENABLE_UNIVERSAL_BUCKET_AFTER_INIT",
        candidateManifestSha256=candidate["manifestSha256"], baseSha256=legacy.BASE_SHA, forgeSha256=legacy.PUBLIC_FORGE_SHA),
        "Incomplete or broadened classification")
    actual = {}
    for file in path.parent.rglob("*"):
        if file.is_file() and file != path:
            require(file.resolve().is_relative_to(path.parent), "Sealed evidence escapes the proof root")
            actual[file.relative_to(path.parent).as_posix()] = digest(file)
    require(actual and actual == document.get("filesSha256"), "Fresh proof raw evidence changed, disappeared or was added")
    require(document["protocolSha256"] == {str(p): digest(p) for p in protocol_files()}, "Proof is bound to another protocol")
    baseline = document["baseline"]
    require(baseline["codeInventory"] == candidate["codeInventory"] and baseline["mods"] ==
        [m for m in candidate["mods"] if m["id"] in {"basemetals", "orespawn"}], "Baseline dependencies/Forge differ from candidate")
    for boot in document["boots"]:
        diagnostic = audit_boot(path.parent / boot["log"])
        require(diagnostic == boot["diagnostic"], "Original boot diagnostic changed")
        if boot["label"].startswith("baseline-"):
            loaded = mod_list(path.parent / boot["savedLevel"])
            require_baseline_mods(loaded, {m["id"]: m["runtimeVersion"] for m in baseline["mods"]})
            require(loaded == boot["savedModList"], "Baseline saved loaded-mod evidence changed")
    role = legacy.validate_role(operating, path.parent / "candidate", document["token"], candidate)
    require(role == document["candidateRole"], "Candidate bucket/cold evidence changed")
    require_four_boots(document["boots"], role)
    for name in ("baseline", "candidate"):
        verify_code(path.parent / name, candidate["codeInventory"])
        expected = {Path(m["path"]).name: m["sha256"] for m in (baseline["mods"] if name == "baseline" else candidate["mods"])}
        require({p.name: digest(p) for p in (path.parent / name / "mods").glob("*.jar")} == expected, "Proof staged mod inventory changed")
    return document


def require_strict_failure(path, checksum, candidate):
    require(digest(path) == checksum.upper(), "Retained strict failure receipt changed")
    failed = legacy.read(path)
    require(failed.get("status") == "FAIL" and failed.get("targetManifestSha256") == candidate["manifestSha256"],
            "Retained strict failure belongs to another target or is not FAIL")
    message = failed.get("failure", "")
    prefix = re.match(r"^Native runtime diagnostic at line \d+: (.*)$", message)
    require(prefix and legacy.LATE_BUCKET.fullmatch(prefix.group(1)), "Strict primary failure is not solely the exact known Base24 diagnostic")
    return failed


def rerun(args):
    document = verify_proof(args.proof, args.proof_sha256)
    _, candidate = pins(args.target_manifest)
    bind_candidate(document, candidate)
    require_strict_failure(args.strict_failure, args.strict_failure_sha256, candidate)
    module = importlib.import_module("native110_rebrand_connected" if args.mode == "connected" else "native110_rebrand_passive")
    output = output_path(args.output)
    for protected in (args.proof.parent, args.strict_failure.parent): require_disjoint(output, protected)
    receipt = output.parent / (output.name + "-fresh-bucket-permit.json")
    require(not receipt.exists(), "Refusing to overwrite a permit receipt")
    report = dict(status=legacy.PENDING, kind=KIND, mode=args.mode, proof=str(args.proof.resolve()), proofSha256=args.proof_sha256.upper(),
        strictFailure=str(args.strict_failure.resolve()), strictFailureSha256=args.strict_failure_sha256.upper(),
        candidate=candidate, boots={}, classification=None, protocolSha256={str(p): digest(p) for p in protocol_files()},
        sourceObserver=dict(modid="advantagemigrationprobe", version="1.0.0", sha256=observer_sha(args.mode)),
        startedAtUtc=datetime.now(timezone.utc).isoformat())
    write_json(receipt, report)
    expected_versions = {m["id"]: m["runtimeVersion"] for m in candidate["mods"]}
    original_class = module.NativeServer if args.mode == "connected" else module.PassiveServer

    def destination(root):
        require(root.resolve() == output, "Unexpected proof-bound destination")
        actual = {p.name: digest(p) for p in (root / "mods").glob("*.jar")}
        require_destination_inventory(actual, candidate, args.mode)
        verify_code(root, candidate["codeInventory"])

    class BoundServer(original_class):
        def read(self):
            for line in self.process.stdout:
                if MODS_LOADED.fullmatch(line):
                    try:
                        self.bucket_marker.touch(exist_ok=False)
                        self.bucket_armed = True
                    except OSError as error: self.arm_error = str(error)
                self.log.write(line); self.log.flush(); self.lines.put(line)

        def __init__(self, runtime, java, launcher, label):
            self.root = Path(runtime).resolve()
            destination(self.root)
            require(label in ("converted", "restarted") and label not in report["boots"], "Unexpected/duplicate destination boot")
            self.record = dict(status=legacy.PENDING, label=label, token=uuid.uuid4().hex)
            report["boots"][label] = self.record
            self.bucket_marker = self.root / "advantage-machine-operation.allow"
            require(not self.bucket_marker.exists(), "Machine operating authority leaked into pre-init")
            self.bucket_armed = False; self.arm_error = None
            phase = "operate" if label == "converted" else "restart"
            self.flags_path = self.root / ("bucket-startup-" + phase + ".json")
            require(not self.flags_path.exists(), "Fresh per-process bucket flags are required")
            try:
                with patch.dict(os.environ, {"ADVWORKS_BUCKET_PROOF": self.record["token"]}):
                    super().__init__(runtime, java, launcher, label)
                require(self.bucket_armed and self.arm_error is None, "Post-mod-loading bucket witness was not armed")
                self.flags_sha = digest(self.flags_path)
                versions = dict(expected_versions)
                versions["advantagemigrationprobe"] = "1.0.0"
                legacy.startup_flags(legacy.read(self.flags_path), self.record["token"], versions)
            except BaseException:
                if getattr(self, "process", None) is not None and self.process.poll() is None:
                    try: self.close()
                    except BaseException: pass
                raise
            finally:
                if self.bucket_armed: self.bucket_marker.unlink(missing_ok=True)

        def close(self):
            try:
                # Preserve the inherited command restriction, clean exit and code custody;
                # replace only this explicitly proven runtime's diagnostic decision.
                try:
                    Server.close(self)
                finally:
                    if args.mode == "connected":
                        write_json(Path(self.log.name).with_suffix(".commands.json"), self.commands)
                destination(self.root)
                require(digest(args.proof) == args.proof_sha256.upper(), "Protected proof receipt changed")
                require(not hasattr(self, "flags_sha") or digest(self.flags_path) == self.flags_sha, "Fresh startup flags changed")
                versions = dict(expected_versions)
                versions["advantagemigrationprobe"] = "1.0.0"
                flags = legacy.read(self.flags_path)
                legacy.startup_flags(flags, self.record["token"], versions)
                diagnostic = audit_boot(Path(self.log.name))
                self.record.update(status="PASS_BOOT", exitCode=self.process.returncode, startupFlags=flags,
                    startupSha256=digest(self.flags_path), logSha256=digest(Path(self.log.name)), diagnostic=diagnostic)
                write_json(receipt, report)
            except BaseException as error:
                self.record.update(status="FAIL", failure=str(error)); write_json(receipt, report)
                raise

    try:
        arguments = argparse.Namespace(target_manifest=args.target_manifest, output=args.output, java8=args.java8, port=args.port)
        if args.mode == "connected":
            arguments.source_report = args.source_report; arguments.source_report_sha256 = args.source_report_sha256
        else:
            arguments.mode = args.mode; arguments.source_fixture = args.source_fixture; arguments.source_profile = args.source_profile
        with patch.object(module, "NativeServer" if args.mode == "connected" else "PassiveServer", BoundServer):
            code = module.run(arguments)
        name = "native110-rebrand-connected-results.json" if args.mode == "connected" else (
            "native110-rebrand-passive-results.json" if args.mode == "passive-source355" else "native110-rebrand-synthetic-nested-results.json")
        primary = legacy.read(output / name)
        require(code == 0 and primary.get("status") == "PASS" and primary.get("sourceUnchanged") is True,
                "Unchanged primary migration/work/preservation protocol did not pass")
        require(set(report["boots"]) == {"converted", "restarted"} and all(b.get("status") == "PASS_BOOT"
            and type(b.get("exitCode")) is int and b["exitCode"] == 0 for b in report["boots"].values()), "Two fresh validated rerun boots required")
        report.update(status="PASS", classification="FRESH_FOUR_BOOT_PROOF_BOUND_EXACT_BASE24_NOTICE_ONLY", primaryReceiptSha256=digest(output / name))
    except BaseException as error:
        report.update(status="FAIL", classification=None, failure=str(error), traceback=traceback.format_exc())
    finally:
        try:
            verify_proof(args.proof, args.proof_sha256)
            require_strict_failure(args.strict_failure, args.strict_failure_sha256, candidate)
            require(pins(args.target_manifest)[1] == candidate, "Candidate changed")
            require(report["protocolSha256"] == {str(p): digest(p) for p in protocol_files()}, "Rerun protocol changed")
            report["protectedEvidenceUnchanged"] = True
        except BaseException as error:
            report.update(status="FAIL", classification=None, protectedEvidenceUnchanged=False, custodyFailure=str(error))
        report["completedAtUtc"] = datetime.now(timezone.utc).isoformat()
        report["filesSha256"] = {p.relative_to(output).as_posix(): digest(p) for p in output.rglob("*") if p.is_file()} if output.exists() else {}
        write_json(receipt, report)
    return int(report["status"] != "PASS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    collect_parser = commands.add_parser("collect")
    collect_parser.add_argument("--candidate-manifest", type=Path, required=True)
    rerun_parser = commands.add_parser("rerun")
    rerun_parser.add_argument("--proof", type=Path, required=True)
    rerun_parser.add_argument("--proof-sha256", required=True)
    rerun_parser.add_argument("--strict-failure", type=Path, required=True)
    rerun_parser.add_argument("--strict-failure-sha256", required=True)
    rerun_parser.add_argument("--target-manifest", type=Path, required=True)
    rerun_parser.add_argument("--mode", choices=("connected", "passive-source355", "synthetic-nested"), required=True)
    rerun_parser.add_argument("--source-report", type=Path)
    rerun_parser.add_argument("--source-report-sha256")
    rerun_parser.add_argument("--source-fixture", type=Path)
    rerun_parser.add_argument("--source-profile", type=Path)
    for command in (collect_parser, rerun_parser):
        command.add_argument("--output", type=Path, required=True)
        command.add_argument("--java8", type=Path, required=True)
        command.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    if args.command == "rerun":
        if args.mode == "connected" and (not args.source_report or not args.source_report_sha256): parser.error("Connected requires its sealed original source report")
        if args.mode != "connected" and (not args.source_fixture or not args.source_profile): parser.error("Passive/synthetic mode requires the authentic source fixture and profile")
    return collect(args) if args.command == "collect" else rerun(args)


if __name__ == "__main__": raise SystemExit(main())
