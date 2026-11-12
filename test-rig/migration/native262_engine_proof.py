"""Bind independent native platform controls and actual ordered server cold stops."""

import argparse
from datetime import datetime, timezone
from pathlib import Path
import shutil
from zipfile import ZipFile

from matrix import digest, write_json
import native262 as native
import native262_platform_diagnostics as diagnostics
import native262_endurance_replay as replay


def run(args):
    native.validate_hash(args.migration, args.migration_sha256)
    prior = native.read_json(args.migration)
    if prior.get("status") != "PASS" or prior.get("scope") != "MACHINE_MIGRATION_STOPPED_STORAGE_AND_PLAYER_AUTHORITY":
        raise AssertionError("Platform proof requires the completed actual migration and cold restart")
    native.validate_hash(args.host / "receipt.json", diagnostics.HOST_CONTROL_SHA)
    host = native.read_json(args.host / "receipt.json")
    if (host.get("minecraftVersion") != "26.2" or host.get("targetDataVersion") != 4903
            or not all(host.get(key) is True for key in ("noPower", "noHarness", "noWorld", "noServer", "noTicks",
                                                         "currentProcessMissing", "inheritedJvmEnvironmentAbsent"))):
        raise AssertionError("Host control lacks independent native runtime proof")
    netty = (args.netty / "runtime.log").read_text(encoding="utf-8")
    if "NETTY_JAVA25_CONTROL_PASS noPower=true noHarness=true noMinecraft=true namedModule=io.netty.common" not in netty:
        raise AssertionError("New native Netty binary was not independently exercised")
    native.validate_hash(args.netty_sources, diagnostics.SOURCES_JAR_SHA)
    native.validate_hash(args.netty_binary, diagnostics.COMMON_JAR_SHA)
    native.validate_hash(args.oshi_binary, diagnostics.OSHI_JAR_SHA)
    with ZipFile(args.sdk_sources) as source:
        import hashlib
        if hashlib.sha256(source.read("net/minecraft/SystemReport.java")).hexdigest().upper() != diagnostics.SYSTEM_REPORT_SOURCE_SHA:
            raise AssertionError("Native SystemReport primary source differs")
    runtime = Path(prior["runtime"])
    proof_files = [args.migration, args.host / "receipt.json", args.host / "native-system-report.txt", args.host_log,
                   args.netty / "runtime.log", args.netty / "command.json", args.netty_sources, args.netty_binary,
                   args.oshi_binary, args.sdk_sources, diagnostics.HOST_FIXTURE, diagnostics.HOST_FILE_FIXTURE,
                   diagnostics.NETTY_FIXTURE, Path(__file__).resolve(),
                   native.RIG / "platform-controls/NativeSystemReportPlatformControl.java",
                   native.RIG / "platform-controls/NettyJava25PlatformControl.java", args.focused_tests]
    for label in ("converted", "restarted"):
        if prior["phases"][label]["status"] != "PASS":
            raise AssertionError("Missing stopped native migration phase")
        for prefix in ("console", "latest", "debug"):
            path = runtime / f"{prefix}-{label}.log"
            native.require_clean_log(path)
            proof_files.append(path)
        native.require_complete_boot((runtime / f"console-{label}.log").read_text(encoding="utf-8"),
                                     stop_sent=True, returncode=0)
        proof_files.append(runtime / f"native65-process-{label}.json")
    if not args.focused_tests.read_text(encoding="utf-8").rstrip().endswith("OK"):
        raise AssertionError("Focused positive and negative classifier checks did not pass")
    args.output.mkdir(parents=True, exist_ok=False)
    closure = []
    for index, path in enumerate(replay.oracle_paths()):
        frozen = args.output / "sources" / f"{index:03}-{path.name}"
        frozen.parent.mkdir(exist_ok=True)
        shutil.copy2(path, frozen)
        if digest(path) != digest(frozen):
            raise AssertionError("Platform source closure copy changed")
        closure.append(dict(path=str(path), sha256=digest(path), frozen=str(frozen)))
    result = dict(status="STRICT_CLASSIFIER_AND_ORDERED_COLDSTOP_READY", qualificationAllowed=False,
                  minecraft="26.2", forge="65.1.0", createdUtc=datetime.now(timezone.utc).isoformat(),
                  debugNotSuppressed=True, noJvmFlagsAdded=True, rawFailedEvidenceUntouched=True,
                  sourceClosure=closure, proofFiles={str(path): digest(path) for path in proof_files},
                  coreSha256=digest(Path(native.__file__)), classifierSha256=digest(Path(diagnostics.__file__)),
                  scope="Native host/capability diagnostics and owned cold-stop readiness only; not endurance proof")
    write_json(args.output / "receipt.json", result)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("migration", "host", "host-log", "netty", "netty-sources", "netty-binary", "oshi-binary",
                "sdk-sources", "focused-tests", "output"):
        parser.add_argument("--" + key, type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--migration-sha256", required=True)
    run(parser.parse_args())
